package com.lagradost.cloudstream.mac

import android.content.Context
import com.googlecode.d2j.dex.Dex2jar
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.extractorApis
import java.io.File
import java.net.URLClassLoader
import java.security.MessageDigest
import java.util.zip.ZipFile

// Same JSON shapes as the Android app's RepositoryManager; unknown fields are ignored by the library's mapper.
data class Repository(val name: String, val description: String? = null, val pluginLists: List<String> = emptyList())

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
)

data class RepoEntry(val name: String, val url: String)
data class InstalledPlugin(val internalName: String, val name: String, val version: Int, val repoUrl: String)
data class SavedState(val repos: List<RepoEntry> = emptyList(), val plugins: List<InstalledPlugin> = emptyList())

object Extensions {
    // The property lets tests use a throwaway folder instead of the real app data
    val dataDir = File(System.getProperty("cloudstream.dataDir") ?: "${System.getProperty("user.home")}/Library/Application Support/CloudStream")
        .apply { mkdirs() }
    private val pluginDir = File(dataDir, "plugins").apply { mkdirs() }
    private val stateFile = File(dataDir, "state.json")

    var state: SavedState = runCatching { parseJson(stateFile.readText(), SavedState::class) }.getOrDefault(SavedState())
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

    suspend fun addRepo(input: String): RepoEntry {
        val url = normalizeRepoUrl(input)
        val repo = parseJson(app.get(url).text, Repository::class)
        val entry = RepoEntry(repo.name, url)
        save(state.copy(repos = state.repos.filter { it.url != url } + entry))
        return entry
    }

    fun removeRepo(url: String) = save(state.copy(repos = state.repos.filter { it.url != url }))

    suspend fun listPlugins(repoUrl: String): List<SitePlugin> {
        val repo = parseJson(app.get(repoUrl).text, Repository::class)
        return repo.pluginLists.flatMap { listUrl ->
            runCatching { parseJson(app.get(listUrl).text, Array<SitePlugin>::class).toList() }.getOrDefault(emptyList())
        }
    }

    private fun cs3(internalName: String) = File(pluginDir, "$internalName.cs3")
    private fun jar(internalName: String) = File(pluginDir, "$internalName.jar")

    suspend fun install(plugin: SitePlugin, repoUrl: String) {
        val bytes = app.get(plugin.url).okhttpResponse.body.bytes()
        if (plugin.fileHash != null) {
            val hash = "sha256-" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            check(hash.equals(plugin.fileHash, ignoreCase = true)) { "Hash mismatch for ${plugin.name}" }
        }
        uninstall(plugin.internalName, keepState = true)
        cs3(plugin.internalName).writeBytes(bytes)
        save(state.copy(plugins = state.plugins.filter { it.internalName != plugin.internalName } +
                InstalledPlugin(plugin.internalName, plugin.name, plugin.version, repoUrl)))
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
        jar(internalName).delete()
        if (!keepState) save(state.copy(plugins = state.plugins.filter { it.internalName != internalName }))
    }

    fun loadAll() = state.plugins.forEach { load(it.internalName) }

    private fun load(internalName: String) {
        val cs3 = cs3(internalName)
        try {
            val jar = jar(internalName)
            if (!jar.exists()) {
                Dex2jar.from(cs3).topoLogicalSort().skipDebug(false).to(jar.toPath())
            }
            val manifestJson = ZipFile(cs3).use { zip ->
                val entry = zip.getEntry("manifest.json") ?: error("No manifest.json in $internalName")
                zip.getInputStream(entry).reader().readText()
            }
            val manifest = parseJson(manifestJson, BasePlugin.Manifest::class)
            val loader = URLClassLoader(arrayOf(jar.toURI().toURL()), javaClass.classLoader)
            val instance = loader.loadClass(manifest.pluginClassName).getDeclaredConstructor().newInstance() as BasePlugin
            instance.filename = cs3.absolutePath
            loaded[internalName] = instance
            if (instance is Plugin) instance.load(Context()) else instance.load()
            loadErrors.remove(internalName)
        } catch (t: Throwable) {
            // LinkageErrors here usually mean the extension uses an Android API the desktop app doesn't stub
            loadErrors[internalName] = t.toString()
            t.printStackTrace()
        }
    }
}
