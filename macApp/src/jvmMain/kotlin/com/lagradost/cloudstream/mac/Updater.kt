package com.lagradost.cloudstream.mac

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Keeps the installed app up to date from this fork's GitHub releases: on launch it looks at the latest release,
 * downloads the file for this machine in the background and checks it against GitHub's SHA-256 digest. Restarting
 * swaps it in with a small script that waits for the app to quit, then reopens the new version.
 */
object Updater {
    private const val REPO = "ABDULAHAD118/cloudstream"

    /** Set by jpackage's launcher; null when running from Gradle, where there's nothing to update. */
    val currentVersion: String? = System.getProperty("jpackage.app-version")

    class Ready(val version: String, val file: File)

    /** A verified update waiting for a restart. */
    var ready by mutableStateOf<Ready?>(null)
        private set
    /** What the last check found, for Settings → About. */
    var status by mutableStateOf<String?>(null)
        private set
    private var checking = false

    private data class Asset(
        @JsonProperty("name") val name: String,
        @JsonProperty("browser_download_url") val url: String,
        @JsonProperty("digest") val digest: String? = null,
    )
    private data class Release(@JsonProperty("tag_name") val tag: String, @JsonProperty("assets") val assets: List<Asset> = emptyList())

    /** True when [candidate] ("1.0.12") is a later version than [installed] ("1.0.9"). */
    fun isNewer(candidate: String, installed: String): Boolean {
        val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
        val b = installed.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** The release file for this machine, as desktop-release.yml names them. */
    fun assetSuffix(windows: Boolean = isWindows, arch: String = System.getProperty("os.arch").orEmpty()) = when {
        windows -> "-windows.zip"
        arch == "aarch64" || arch == "arm64" -> "-apple-silicon.dmg"
        else -> "-intel.dmg"
    }

    suspend fun check() = withContext(Dispatchers.IO) {
        if (!Network.online) return@withContext run { status = "You're offline — connect to check for updates" }
        val installed = currentVersion ?: return@withContext run { status = "Updates work in the installed app only" }
        if (checking || ready != null) return@withContext
        checking = true
        try {
            status = "Checking for updates…"
            val release = parseJson<Release>(app.get("https://api.github.com/repos/$REPO/releases/latest",
                headers = mapOf("Accept" to "application/vnd.github+json")).text)
            val version = release.tag.removePrefix("v")
            if (!isNewer(version, installed)) return@withContext run { status = "You have the latest version ($installed)" }
            val asset = release.assets.firstOrNull { it.name.endsWith(assetSuffix()) }
                ?: return@withContext run { status = "CloudStream $version has no download for this computer yet" }
            // Only a file matching GitHub's own digest gets installed
            val expected = asset.digest?.removePrefix("sha256:")?.lowercase()
                ?: return@withContext run { status = "CloudStream $version can't be verified, so it wasn't downloaded" }
            val dir = File(Extensions.dataDir, "updates").apply { mkdirs() }
            dir.listFiles()?.filter { it.name != asset.name }?.forEach { it.deleteRecursively() }
            val file = File(dir, asset.name)
            if (!file.exists() || sha256(file) != expected) {
                file.delete()
                status = "Downloading CloudStream $version…"
                Downloads.fetchFile(asset.url, emptyMap(), file) { status = "Downloading CloudStream $version… ${(it * 100).toInt()}%" }
            }
            if (sha256(file) != expected) { file.delete(); error("the download didn't match its checksum") }
            ready = Ready(version, file)
            status = "CloudStream $version is ready to install"
        } catch (t: Throwable) {
            status = "Couldn't check for updates: ${t.message ?: t}"
        } finally {
            checking = false
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Starts the swap script and quits; the script waits for this process to end, installs, and reopens the app. */
    fun installAndRestart() {
        val update = ready ?: return
        val launcher = File(System.getProperty("jpackage.app-path") ?: return)
        val pid = ProcessHandle.current().pid().toString()
        val dir = update.file.parentFile
        val command = if (isWindows) {
            // CloudStream\CloudStream.exe: the whole folder is the app
            val appDir = launcher.parentFile.takeIf { File(it, "CloudStream.exe").exists() } ?: return
            val script = File(dir, "install.ps1").apply { writeText(WINDOWS_SCRIPT) }
            listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
                "-File", script.path, pid, update.file.path, appDir.path)
        } else {
            // CloudStream.app/Contents/MacOS/CloudStream: replace the whole bundle
            val bundle = launcher.parentFile?.parentFile?.parentFile?.takeIf { it.name.endsWith(".app") } ?: return
            val script = File(dir, "install.sh").apply { writeText(MAC_SCRIPT) }
            listOf("/bin/bash", script.path, pid, update.file.path, bundle.path)
        }
        ProcessBuilder(command).redirectErrorStream(true).redirectOutput(File(dir, "install.log")).start()
        // Downloads in progress pick up where they stopped on the next launch
        kotlin.system.exitProcess(0)
    }

    // Copies into a temporary bundle first, so a failed copy leaves the installed app as it was
    private val MAC_SCRIPT = """
        set -u
        pid="${'$'}1"; dmg="${'$'}2"; app="${'$'}3"
        while kill -0 "${'$'}pid" 2>/dev/null; do sleep 0.5; done
        mnt="${'$'}(mktemp -d)"
        if hdiutil attach -nobrowse -readonly -mountpoint "${'$'}mnt" "${'$'}dmg"; then
          rm -rf "${'$'}app.new"
          if ditto "${'$'}mnt/CloudStream.app" "${'$'}app.new"; then
            rm -rf "${'$'}app" && mv "${'$'}app.new" "${'$'}app" && rm -f "${'$'}dmg"
          fi
          hdiutil detach -quiet "${'$'}mnt"
        fi
        open "${'$'}app"
    """.trimIndent()

    // Copies over the app folder without deleting anything else in it (/E, not /MIR), then reopens it
    private val WINDOWS_SCRIPT = """
        param([int]${'$'}AppPid, [string]${'$'}Zip, [string]${'$'}AppDir)
        Wait-Process -Id ${'$'}AppPid -ErrorAction SilentlyContinue
        ${'$'}tmp = Join-Path ${'$'}env:TEMP "cloudstream-update"
        Remove-Item ${'$'}tmp -Recurse -Force -ErrorAction SilentlyContinue
        Expand-Archive -Path ${'$'}Zip -DestinationPath ${'$'}tmp -Force
        if (Test-Path (Join-Path ${'$'}tmp "CloudStream\CloudStream.exe")) {
          robocopy (Join-Path ${'$'}tmp "CloudStream") ${'$'}AppDir /E /R:5 /W:1 /NFL /NDL /NJH /NJS | Out-Null
          if (${'$'}LASTEXITCODE -lt 8) { Remove-Item ${'$'}Zip -Force -ErrorAction SilentlyContinue }
        }
        Remove-Item ${'$'}tmp -Recurse -Force -ErrorAction SilentlyContinue
        Start-Process (Join-Path ${'$'}AppDir "CloudStream.exe")
    """.trimIndent()
}
