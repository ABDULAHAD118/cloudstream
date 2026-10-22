package com.lagradost.cloudstream.mac

import com.googlecode.d2j.dex.Dex2jar
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.extractorApis
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

// Same JSON shapes as the Android app's RepositoryManager; unknown fields are ignored by the library's mapper.
data class Repository(val name: String, val description: String? = null, val iconUrl: String? = null, val pluginLists: List<String> = emptyList())

data class SitePlugin(
    val url: String,
    val status: Int = 1,
    val version: Int = 0,
    val name: String,
    val internalName: String,
    val description: String? = null,
    val language: String? = null,
    val iconUrl: String? = null,
    val tvTypes: List<String>? = null,
    val fileHash: String? = null,
    val authors: List<String> = emptyList(),
)

data class RepoEntry(val name: String, val url: String, val iconUrl: String? = null)
data class DirectoryRepo(val name: String, val url: String, val verified: Boolean, val iconUrl: String? = null)
data class InstalledPlugin(val internalName: String, val name: String, val version: Int, val repoUrl: String, val iconUrl: String? = null)
data class SavedState(val repos: List<RepoEntry> = emptyList(), val plugins: List<InstalledPlugin> = emptyList())

object Extensions {
    init {
        // Extensions built on NewPipeExtractor (YouTube) need this, as CommonActivity sets it up on Android
        org.schabi.newpipe.extractor.NewPipe.init(NewPipeDownloader)
    }

    // The property lets tests use a throwaway folder instead of the real app data
    val dataDir = File(System.getProperty("cloudstream.dataDir") ?: defaultDataDir)
        .apply { mkdirs() }
    private val pluginDir = File(dataDir, "plugins").apply { mkdirs() }
    private val stateFile = File(dataDir, "state.json")

    const val OFFICIAL_REPO = "https://raw.githubusercontent.com/recloudstream/extensions/master/repo.json"

    // The extension this fork ships with — CNC Verse (movies, series and live channels) — so a fresh app has content
    private const val BUNDLED_PLUGIN = "CNC Verse"
    private const val BUNDLED_VERSION = 115
    private const val CNC_REPO = "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/refs/heads/builds/CNC.json"
    private const val CNC_REPO_NAME = "CNC Repo(All Language)"
    private const val CNC_ICON = "https://github.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/raw/refs/heads/master/Logos/CNC%20Verse/icon.png"

    // The community repository list that the Android setup screen links to
    private const val REPO_DIRECTORY = "https://raw.githubusercontent.com/recloudstream/cs-repos/master/repos-db.json"
    // Well-known repos missing from that list (checked to load, Oct 2026)
    private val BUILTIN_REPOS = listOf(
        "https://raw.githubusercontent.com/Sushan64/NetMirror-Extension/refs/heads/builds/Netflix.json",
        "https://raw.githubusercontent.com/keyiflerolsun/Kekik-cloudstream/master/repo.json",
    )

    // First launch starts with the official repo so the Extensions tab isn't empty
    var state: SavedState = runCatching { parseJson(stateFile.readText(), SavedState::class) }
        .getOrDefault(SavedState(repos = listOf(RepoEntry("Cloudstream providers repository", OFFICIAL_REPO))))
        private set

    /** internalName -> load error, for plugins that installed but failed to start. */
    val loadErrors = mutableMapOf<String, String>()
    private val loaded = mutableMapOf<String, BasePlugin>()

    private fun save(newState: SavedState) {
        state = newState
        stateFile.writeText(newState.toJson())
    }

    /** Accepts plain https URLs and the app's `cloudstreamrepo://` share links. */
    fun normalizeRepoUrl(input: String): String {
        val url = input.trim().replace("^(cloudstreamrepo://)|(https://cs\\.repo/\\??)".toRegex(), "")
        // shortcut: the Android app also resolves bare cutt.ly / py.md short codes; paste the full URL here instead
        return if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
    }

    private val githubProject = Regex("^https://github\\.com/([^/]+)/([^/]+?)(?:\\.git)?/?$")
    private data class GithubFile(val name: String)

    /** A github.com project link (as people share them) is resolved to the repo manifest inside it. */
    private suspend fun resolveRepoJson(url: String): String {
        val (owner, name) = githubProject.find(url)?.destructured ?: return url
        // Repos keep the manifest under varying names (repo.json, CNC.json, ...), usually on the builds branch
        for (branch in listOf("builds", "master", "main")) {
            val files = runCatching {
                parseJson(app.get("https://api.github.com/repos/$owner/$name/contents?ref=$branch").text, Array<GithubFile>::class)
            }.getOrNull() ?: continue
            for (file in files.filter { it.name.endsWith(".json") }) {
                val raw = "https://raw.githubusercontent.com/$owner/$name/$branch/${file.name}"
                val isRepo = runCatching { parseJson(app.get(raw).text, Repository::class).pluginLists.isNotEmpty() }.getOrDefault(false)
                if (isRepo) return raw
            }
        }
        error("No CloudStream repository file found in $owner/$name")
    }

    suspend fun addRepo(input: String): RepoEntry {
        val url = resolveRepoJson(normalizeRepoUrl(input))
        val repo = parseJson(app.get(url).text, Repository::class)
        val entry = RepoEntry(repo.name, url, repo.iconUrl)
        save(state.copy(repos = state.repos.filter { it.url != url } + entry))
        return entry
    }

    /** Community repos with their names; repos that no longer respond are left out. */
    suspend fun communityRepos(): List<DirectoryRepo> = coroutineScope {
        // Entries are either a bare URL or {"url": ..., "verified": true}
        val directory = runCatching { parseJson(app.get(REPO_DIRECTORY).text, Array<Any>::class) }.getOrDefault(emptyArray()).mapNotNull { entry ->
            when (entry) {
                is String -> entry to false
                is Map<*, *> -> (entry["url"] as? String ?: return@mapNotNull null) to (entry["verified"] == true)
                else -> null
            }
        }
        (directory + BUILTIN_REPOS.filter { b -> directory.none { it.first == b } }.map { it to false }).map { (url, verified) ->
            async {
                withTimeoutOrNull(15_000) {
                    runCatching { parseJson(app.get(url).text, Repository::class).let { DirectoryRepo(it.name, url, verified, it.iconUrl) } }.getOrNull()
                }
            }
        }.awaitAll().filterNotNull().sortedWith(compareByDescending<DirectoryRepo> { it.verified }.thenBy { it.name.lowercase() })
    }

    fun removeRepo(url: String) = save(state.copy(repos = state.repos.filter { it.url != url }))

    suspend fun listPlugins(repoUrl: String): List<SitePlugin> {
        val repo = parseJson(app.get(repoUrl).text, Repository::class)
        return repo.pluginLists.flatMap { listUrl ->
            runCatching { parseJson(app.get(listUrl).text, Array<SitePlugin>::class).toList() }.getOrDefault(emptyList())
        }
    }

    private fun cs3(internalName: String) = File(pluginDir, "$internalName.cs3")
    // Bump the suffix whenever conversion changes, so jars converted the old way are redone on next load
    private fun jar(internalName: String) = File(pluginDir, "$internalName.d2j2.jar")
    private fun staleJars(internalName: String) =
        pluginDir.listFiles { f -> f.name.startsWith("$internalName.") && f.extension == "jar" }.orEmpty()

    suspend fun install(plugin: SitePlugin, repoUrl: String) {
        val bytes = app.get(plugin.url).okhttpResponse.body.bytes()
        if (plugin.fileHash != null) {
            val hash = "sha256-" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            check(hash.equals(plugin.fileHash, ignoreCase = true)) { "Hash mismatch for ${plugin.name}" }
        }
        uninstall(plugin.internalName, keepState = true)
        cs3(plugin.internalName).writeBytes(bytes)
        save(state.copy(plugins = state.plugins.filter { it.internalName != plugin.internalName } +
                InstalledPlugin(plugin.internalName, plugin.name, plugin.version, repoUrl, plugin.iconUrl)))
        load(plugin.internalName)
    }

    fun uninstall(internalName: String, keepState: Boolean = false) {
        loaded.remove(internalName)?.let { instance ->
            runCatching { instance.beforeUnload() }
            val path = instance.filename
            APIHolder.allProviders.filter { it.sourcePlugin == path }.forEach {
                APIHolder.allProviders.remove(it)
                APIHolder.removePluginMapping(it)
            }
            extractorApis.removeAll { it.sourcePlugin == path }
        }
        loadErrors.remove(internalName)
        cs3(internalName).delete()
        staleJars(internalName).forEach { it.delete() }
        if (!keepState) save(state.copy(plugins = state.plugins.filter { it.internalName != internalName }))
    }

    fun loadAll() = state.plugins.forEach { load(it.internalName) }

    /**
     * Installs the extension this build ships with, once per data folder, before the plugins are loaded.
     * The marker makes a later removal final: it isn't put back on the next launch.
     */
    fun seedBundled() = seedBundled(pluginDir, File(dataDir, "bundled-extension"), state)?.let { save(it) }

    internal fun seedBundled(pluginDir: File, marker: File, state: SavedState): SavedState? {
        if (marker.exists()) return null
        val file = File(pluginDir, "$BUNDLED_PLUGIN.cs3")
        if (!file.exists()) {
            // Copied from the app's own resources, so this works on a first launch with no connection
            val bytes = Extensions::class.java.getResourceAsStream("/bundled/cnc-verse.cs3")?.use { it.readBytes() } ?: return null
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }
        marker.writeText("$BUNDLED_PLUGIN $BUNDLED_VERSION\n")
        return state.copy(
            repos = state.repos.filter { it.url != CNC_REPO } + RepoEntry(CNC_REPO_NAME, CNC_REPO),
            // Someone who installed it from the repo already keeps their (possibly newer) entry
            plugins = if (state.plugins.any { it.internalName == BUNDLED_PLUGIN }) state.plugins
            else state.plugins + InstalledPlugin(BUNDLED_PLUGIN, BUNDLED_PLUGIN, BUNDLED_VERSION, CNC_REPO, CNC_ICON),
        )
    }

    private fun load(internalName: String) {
        val cs3 = cs3(internalName)
        try {
            val jar = jar(internalName)
            if (!jar.exists()) {
                staleJars(internalName).forEach { it.delete() }
                // Keep names as-is: the default turns '-' into '_', breaking Kotlin's constructor-impl/box-impl calls
                Dex2jar.from(cs3).topoLogicalSort().skipDebug(false).dontSanitizeNames(true).to(jar.toPath())
            }
            val manifestJson = ZipFile(cs3).use { zip ->
                val entry = zip.getEntry("manifest.json") ?: error("No manifest.json in $internalName")
                zip.getInputStream(entry).reader().readText()
            }
            val manifest = parseJson(manifestJson, BasePlugin.Manifest::class)
            val loader = ExtensionClassLoader(jar, javaClass.classLoader)
            val instance = loader.loadClass(manifest.pluginClassName).getDeclaredConstructor().newInstance() as BasePlugin
            instance.filename = cs3.absolutePath
            loaded[internalName] = instance
            if (instance is Plugin) instance.load(loader.hostContext()) else instance.load()
            loadErrors.remove(internalName)
        } catch (t: Throwable) {
            // LinkageErrors here usually mean the extension uses an Android API the desktop app doesn't stub
            loadErrors[internalName] = t.toString()
            t.printStackTrace()
        }
    }
}

/** Same rules as the Android PluginsViewModel: any selected type matches, "Others" also matches untyped plugins. */
fun filterPlugins(plugins: List<SitePlugin>, types: Set<TvType>, language: String?, query: String): List<SitePlugin> =
    plugins
        // shortcut: NSFW extensions are always hidden, as on Android with its adult toggle off; add a setting if wanted
        .filter { it.tvTypes?.contains(TvType.NSFW.name) != true }
        .filter { p ->
            types.isEmpty() || p.tvTypes?.any { t -> types.any { it.name == t } } == true ||
                    (TvType.Others in types && p.tvTypes.isNullOrEmpty())
        }
        .filter { language == null || (it.language?.lowercase() ?: "none") == language }
        .filter { query.isBlank() || it.name.contains(query, true) || it.description?.contains(query, true) == true }
        .sortedBy { it.name.lowercase() }
