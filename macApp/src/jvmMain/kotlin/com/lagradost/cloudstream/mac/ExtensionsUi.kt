package com.lagradost.cloudstream.mac

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Each repository's extension list, shared by the overview (counts, search, icons, updates) and the repo pages. */
object RepoPlugins {
    val lists = mutableStateMapOf<String, List<SitePlugin>>()
    val errors = mutableStateMapOf<String, String>()
    val loading = mutableStateListOf<String>()

    fun refresh(url: String) {
        if (url in loading) return
        loading += url; errors.remove(url)
        AppScope.launch {
            runCatching { Extensions.listPlugins(url) }
                .onSuccess { lists[url] = it }
                .onFailure { errors[url] = it.message ?: it.toString() }
            loading -= url
        }
    }

    fun loadMissing() = Extensions.state.repos.filter { it.url !in lists }.forEach { refresh(it.url) }

    /** The repo's current entry for an installed extension, for its icon and newer versions. */
    fun latest(p: InstalledPlugin) = lists[p.repoUrl]?.firstOrNull { it.internalName == p.internalName }
}

/** Repo icons are often templates with the size left open, as on Android. */
private fun iconUrl(url: String?) = url?.replace("%size%", "128")?.replace("%exact_size%", "128")

@Composable
private fun ExtensionIcon(url: String?, size: Int = 40) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Extension, null, tint = secondaryText, modifier = Modifier.size((size * 0.55).dp))
        if (!url.isNullOrBlank()) RemoteImage(iconUrl(url), null, Modifier.fillMaxSize(), placeholder = false)
    }
}

@Composable
fun ExtensionsScreen() {
    var openRepo by remember { mutableStateOf<String?>(null) }
    val repo = Extensions.state.repos.firstOrNull { it.url == openRepo }
    if (repo != null) RepositoryScreen(repo) { openRepo = null } else ExtensionsOverview { openRepo = it }
}

/** Runs extension work off the UI thread with one busy label at a time, and refreshes what it changed. */
private class ExtensionTasks {
    var busy by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var version by mutableStateOf(0) // bumped after each task, so lists re-read Extensions.state

    fun run(label: String, block: suspend () -> Unit) {
        busy = label; message = null
        AppScope.launch {
            runCatching { block() }.onFailure { message = "$label failed: ${it.message ?: it}" }
            Providers.refresh(); version++; busy = null
        }
    }
}

private val tasks = ExtensionTasks()

@Composable
private fun ExtensionsOverview(onOpenRepo: (String) -> Unit) {
    // Repo pages open from Installed repos, so coming back lands there again
    var tab by remember { mutableStateOf(1) }
    var directory by remember { mutableStateOf<List<DirectoryRepo>?>(null) }
    var reload by remember { mutableStateOf(0) }
    tasks.version // re-read state after installs and removals
    val repos = Extensions.state.repos
    val installed = Extensions.state.plugins
    LaunchedEffect(repos) { RepoPlugins.loadMissing() }
    LaunchedEffect(reload) {
        directory = null
        directory = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Extensions.communityRepos() } }.getOrDefault(emptyList())
    }
    val updates = installed.filter { p -> (RepoPlugins.latest(p)?.version ?: 0) > p.version }

    // The header and tabs stay put; each tab scrolls on its own
    Column(Modifier.fillMaxSize()) {
        LargeTitle("Extensions") {
            ReloadButton { reload++; repos.forEach { RepoPlugins.refresh(it.url) } }
            Button(enabled = updates.isNotEmpty() && tasks.busy == null, onClick = {
                tasks.run("Updating ${updates.size} extensions") {
                    updates.forEach { p -> RepoPlugins.latest(p)?.let { Extensions.install(it, p.repoUrl) } }
                }
            }, modifier = Modifier.handCursor()) { Icon(Icons.Filled.Update, null); Spacer(Modifier.width(6.dp)); Text(if (updates.isEmpty()) "Up to date" else "Update all (${updates.size})") }
        }
        Text("Extensions are the same files the Android app uses. Add a repository from the community list or by link, then install its extensions.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 32.dp))
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 16.dp, containerColor = Color.Transparent, modifier = Modifier.padding(top = 8.dp)) {
            listOf("Community repos", "Installed repos (${repos.size})", "Installed extensions (${installed.size})").forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) }, modifier = Modifier.handCursor())
            }
        }
        Column(Modifier.padding(horizontal = 32.dp)) {
            tasks.busy?.let { Text("$it…", Modifier.padding(top = 8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
            tasks.message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        }
        when (tab) {
            0 -> CommunityReposTab(directory)
            1 -> InstalledReposTab(onOpenRepo)
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp)) {
                if (installed.isEmpty()) item { Hint("No extensions installed. Install them from a repository under Installed repos.") }
                items(installed, key = { "installed|${it.internalName}" }) { p -> InstalledRow(p) }
            }
        }
    }
}

private fun addRepo(url: String, then: () -> Unit = {}) =
    tasks.run("Adding repository") { val entry = Extensions.addRepo(url); RepoPlugins.refresh(entry.url); then() }

@Composable
private fun CommunityReposTab(directory: List<DirectoryRepo>?) {
    var query by remember { mutableStateOf("") }
    tasks.version
    val repos = Extensions.state.repos
    Column(Modifier.fillMaxSize()) {
        SearchField(query, { query = it }, "Search community repositories")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            if (directory == null) item { Hint("Loading…") }
            items(directory.orEmpty().filter { matches(query.trim(), it.name, it.url) }, key = { "community|${it.url}" }) { d ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ExtensionIcon(d.iconUrl)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name + if (d.verified) " ✓" else "", style = MaterialTheme.typography.bodyLarge)
                        Text(d.url, style = MaterialTheme.typography.bodySmall, color = secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (repos.any { it.url == d.url }) TextButton(enabled = false, onClick = {}) { Text("Added") }
                    else TextButton(enabled = tasks.busy == null, onClick = { addRepo(d.url) }, modifier = Modifier.handCursor()) { Text("Add") }
                }
            }
        }
    }
}

@Composable
private fun InstalledReposTab(onOpenRepo: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    var types by remember { mutableStateOf(emptySet<com.lagradost.cloudstream3.TvType>()) }
    var language by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    tasks.version
    val repos = Extensions.state.repos
    val all = repos.flatMap { repo -> RepoPlugins.lists[repo.url].orEmpty().map { repo to it } }
    val shown = filterPlugins(all.map { it.second }, types, language, query).toSet()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp)) {
                OutlinedTextField(input, { input = it }, label = { Text("Repository URL (repo.json, GitHub project or cloudstreamrepo:// link)") },
                    singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (input.isNotBlank()) addRepo(input) { input = "" } }), modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Button(enabled = input.isNotBlank() && tasks.busy == null, onClick = { addRepo(input) { input = "" } }, modifier = Modifier.handCursor()) { Text("Add") }
            }
        }
        if (repos.isEmpty()) item { Hint("No repositories added. Pick one under Community repos, or paste a link above.") }
        items(repos, key = { it.url }) { repo -> RepoCard(repo) { onOpenRepo(repo.url) } }
        if (repos.isNotEmpty()) {
            item {
                SectionTitle("Extensions (${shown.size})")
                Column(Modifier.padding(horizontal = 32.dp)) {
                    PluginFilters(all.map { it.second }, types, { types = it }, language, { language = it }, query, { query = it })
                }
            }
            if (shown.isEmpty()) item { Hint(if (all.isEmpty()) "Loading…" else "No extensions match.") }
            items(all.filter { it.second in shown }.sortedBy { it.second.name.lowercase() }, key = { (repo, p) -> "found|${repo.url}|${p.internalName}" }) { (repo, p) ->
                PluginRow(p, repo.url, repoName = repo.name.takeIf { repos.size > 1 })
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, placeholder: String) =
    OutlinedTextField(query, onQuery, placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }, modifier = Modifier.handCursor()) { Icon(Icons.Filled.Clear, "Clear search") } },
        singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 12.dp))

/** True when every word of the query appears in at least one of the fields. Blank matches all. */
private fun matches(query: String, vararg fields: String?): Boolean {
    if (query.isBlank()) return true
    val haystack = fields.filterNotNull().joinToString(" ").lowercase()
    return query.lowercase().split(' ').filter { it.isNotBlank() }.all { it in haystack }
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 20.dp, bottom = 6.dp))

@Composable
private fun Hint(text: String) = Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 32.dp))

@Composable
private fun RepoCard(repo: RepoEntry, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 4.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            ExtensionIcon(repo.iconUrl)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(repo.name, style = MaterialTheme.typography.titleSmall)
                val error = RepoPlugins.errors[repo.url]
                Text(
                    when {
                        repo.url in RepoPlugins.loading -> "Loading…"
                        error != null -> error
                        else -> "${RepoPlugins.lists[repo.url]?.size ?: 0} extensions · ${repo.url}"
                    },
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { RepoPlugins.refresh(repo.url) }, modifier = Modifier.handCursor()) { Icon(Icons.Filled.Refresh, "Refresh") }
            TextButton(onClick = { Extensions.removeRepo(repo.url); RepoPlugins.lists.remove(repo.url); tasks.version++ }, modifier = Modifier.handCursor()) { Text("Remove") }
        }
    }
}

@Composable
private fun InstalledRow(p: InstalledPlugin) {
    val latest = RepoPlugins.latest(p)
    Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 6.dp).testTag("installed-${p.internalName}"), verticalAlignment = Alignment.CenterVertically) {
        ExtensionIcon(p.iconUrl ?: latest?.iconUrl, 36)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("${p.name} · v${p.version}", style = MaterialTheme.typography.bodyLarge)
            StatusText(p.internalName)
        }
        if (latest != null && latest.version > p.version) IconButton(enabled = tasks.busy == null, onClick = { tasks.run("Updating ${p.name}") { Extensions.install(latest, p.repoUrl) } }, modifier = Modifier.handCursor()) {
            Icon(Icons.Filled.Update, "Update")
        }
        IconButton(enabled = tasks.busy == null, onClick = { tasks.run("Removing ${p.name}") { Extensions.uninstall(p.internalName) } }, modifier = Modifier.handCursor()) { Icon(Icons.Filled.Delete, "Remove") }
    }
    HorizontalDivider(Modifier.padding(horizontal = 32.dp))
}

@Composable
private fun StatusText(internalName: String) {
    val error = Extensions.loadErrors[internalName]
    val providers = Providers.list.filter { it.sourcePlugin?.endsWith("/$internalName.cs3") == true }.map { it.name }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (error != null) {
            Icon(Icons.Filled.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(error?.let { "Failed to load: $it" } ?: if (providers.isEmpty()) "Loaded" else "Providers: " + providers.joinToString(),
            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RepositoryScreen(repo: RepoEntry, onBack: () -> Unit) {
    var types by remember { mutableStateOf(emptySet<com.lagradost.cloudstream3.TvType>()) }
    var language by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(repo.url) { if (repo.url !in RepoPlugins.lists) RepoPlugins.refresh(repo.url) }
    val plugins = RepoPlugins.lists[repo.url]
    val error = RepoPlugins.errors[repo.url]
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 20.dp, end = 24.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = onBack)
            Spacer(Modifier.width(14.dp))
            ExtensionIcon(repo.iconUrl)
            Spacer(Modifier.width(12.dp))
            Text(repo.name, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            ReloadButton { RepoPlugins.refresh(repo.url) }
        }
        Column(Modifier.padding(horizontal = 32.dp, vertical = 12.dp)) {
            PluginFilters(plugins.orEmpty(), types, { types = it }, language, { language = it }, query, { query = it })
            tasks.busy?.let { Text("$it…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
            tasks.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        when {
            plugins == null && error != null -> MessageBox("Couldn't load repository", error, "Try again") { RepoPlugins.refresh(repo.url) }
            plugins == null -> LoadingBox()
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(filterPlugins(plugins, types, language, query), key = { it.internalName }) { PluginRow(it, repo.url) }
            }
        }
    }
}

@Composable
private fun PluginRow(plugin: SitePlugin, repoUrl: String, repoName: String? = null) {
    tasks.version
    val installed = Extensions.state.plugins.firstOrNull { it.internalName == plugin.internalName }
    Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp).testTag("plugin-${plugin.internalName}"), verticalAlignment = Alignment.CenterVertically) {
        ExtensionIcon(plugin.iconUrl)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(plugin.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                listOfNotNull(
                    "v${plugin.version}", plugin.language?.uppercase(), plugin.tvTypes?.joinToString(),
                    when (plugin.status) { 0 -> "Down"; 2 -> "Slow"; 3 -> "Beta"; else -> null },
                    plugin.authors.takeIf { it.isNotEmpty() }?.joinToString(prefix = "by "), repoName?.let { "from $it" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            plugin.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            if (installed != null) StatusText(plugin.internalName)
        }
        Spacer(Modifier.width(12.dp))
        val enabled = tasks.busy == null
        when {
            installed == null -> FilledTonalButton(enabled = enabled, onClick = { tasks.run("Installing ${plugin.name}") { Extensions.install(plugin, repoUrl) } }, modifier = Modifier.handCursor()) {
                Icon(Icons.Filled.Download, null); Spacer(Modifier.width(4.dp)); Text("Install")
            }
            plugin.version > installed.version -> FilledTonalButton(enabled = enabled, onClick = { tasks.run("Updating ${plugin.name}") { Extensions.install(plugin, repoUrl) } }, modifier = Modifier.handCursor()) {
                Icon(Icons.Filled.Update, null); Spacer(Modifier.width(4.dp)); Text("Update")
            }
            else -> IconButton(enabled = enabled, onClick = { tasks.run("Removing ${plugin.name}") { Extensions.uninstall(plugin.internalName) } }, modifier = Modifier.handCursor()) {
                Icon(Icons.Filled.Delete, "Uninstall")
            }
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 32.dp))
}
