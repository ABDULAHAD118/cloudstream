package com.lagradost.cloudstream.mac

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.compose.AsyncImage
import com.lagradost.cloudstream.mac.Screen.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream4.theme.CloudStreamTheme
import com.lagradost.cloudstream4.theme.CloudStreamThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data object ExtensionsTab : Screen
    data class Result(val api: MainAPI, val item: SearchResponse) : Screen
    data class Links(val api: MainAPI, val title: String, val data: String) : Screen
}

/** APIHolder isn't observable, so the UI re-reads it after extensions load or change. */
object Providers {
    var list by mutableStateOf(listOf<MainAPI>())
    var loading by mutableStateOf(true)
    fun refresh() {
        list = APIHolder.apis.toList().sortedBy { it.name.lowercase() }
    }
}

fun main() = application {
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            Extensions.loadAll()
            APIHolder.initAll()
        }
        Providers.refresh()
        Providers.loading = false
    }
    Window(
        onCloseRequest = ::exitApplication,
        title = "CloudStream",
        state = rememberWindowState(size = DpSize(1200.dp, 800.dp)),
    ) {
        CloudStreamTheme(mode = CloudStreamThemeMode.Dark) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { App() }
        }
    }
}

@Composable
fun App() {
    val stack = remember { mutableStateListOf<Screen>(Home) }
    val current = stack.last()
    fun open(screen: Screen) = stack.add(screen)
    fun tab(screen: Screen) { stack.clear(); stack.add(screen) }

    Row {
        NavigationRail {
            Spacer(Modifier.height(8.dp))
            listOf(Home to "Home", Search to "Search", ExtensionsTab to "Extensions").forEach { (screen, label) ->
                NavigationRailItem(
                    selected = stack.first() == screen,
                    onClick = { tab(screen) },
                    icon = { Text(label.take(1), fontWeight = FontWeight.Bold) },
                    label = { Text(label) },
                )
            }
        }
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            if (stack.size > 1) TextButton(onClick = { stack.removeLast() }) { Text("← Back") }
            if (Providers.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (current) {
                Home -> HomeScreen(::open)
                Search -> SearchScreen(::open)
                ExtensionsTab -> ExtensionsScreen()
                is Result -> ResultScreen(current, ::open)
                is Links -> LinksScreen(current)
            }
        }
    }
}

@Composable
fun NoProviders() {
    if (!Providers.loading && Providers.list.isEmpty())
        Text("No providers yet. Add a repository and install extensions in the Extensions tab.")
}

@Composable
fun PosterCard(item: SearchResponse, onClick: () -> Unit) {
    Column(Modifier.width(140.dp).clickable(onClick = onClick).padding(4.dp)) {
        // shortcut: posterHeaders are ignored, so a few providers' posters won't load; pass them through coil if it matters
        AsyncImage(
            model = item.posterUrl, contentDescription = item.name, contentScale = ContentScale.Crop,
            modifier = Modifier.size(132.dp, 196.dp).clip(RoundedCornerShape(8.dp)),
        )
        Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun ResultRow(title: String, items: List<SearchResponse>, onClick: (SearchResponse) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    LazyRow { items(items) { PosterCard(it) { onClick(it) } } }
}

@Composable
fun ProviderPicker(providers: List<MainAPI>, selected: MainAPI?, onSelect: (MainAPI) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(selected?.name ?: "Choose provider") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            providers.forEach { api ->
                DropdownMenuItem(text = { Text("${api.name} (${api.lang})") }, onClick = { expanded = false; onSelect(api) })
            }
        }
    }
}

@Composable
fun HomeScreen(open: (Screen) -> Unit) {
    val providers = Providers.list.filter { it.hasMainPage }
    var selected by remember(providers) { mutableStateOf(providers.firstOrNull()) }
    val rows = remember { mutableStateListOf<HomePageList>() }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(selected) {
        val api = selected ?: return@LaunchedEffect
        rows.clear(); error = null; loading = true
        withContext(Dispatchers.IO) {
            api.mainPage.map { page ->
                async {
                    runCatching { api.getMainPage(1, MainPageRequest(page.name, page.data, page.horizontalImages)) }
                        .onSuccess { res -> res?.items?.filter { it.list.isNotEmpty() }?.let { rows.addAll(it) } }
                        .onFailure { error = it.message ?: it.toString() }
                }
            }.awaitAll()
        }
        loading = false
    }

    NoProviders()
    if (providers.isNotEmpty()) ProviderPicker(providers, selected) { selected = it }
    if (loading) CircularProgressIndicator(Modifier.padding(16.dp))
    if (rows.isEmpty() && error != null) Text("Couldn't load home page: $error", color = MaterialTheme.colorScheme.error)
    LazyColumn {
        items(rows.toList()) { row -> ResultRow(row.name, row.list) { selected?.let { api -> open(Result(api, it)) } } }
    }
}

@Composable
fun SearchScreen(open: (Screen) -> Unit) {
    var query by remember { mutableStateOf("") }
    val results = remember { mutableStateListOf<Pair<MainAPI, List<SearchResponse>>>() }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun search() {
        if (query.isBlank()) return
        results.clear(); searching = true
        scope.launch(Dispatchers.IO) {
            Providers.list.map { api ->
                async {
                    // Slow or broken providers shouldn't hold up the rest
                    val found = withTimeoutOrNull(30_000) { runCatching { api.search(query, 1)?.items }.getOrNull() }
                    if (!found.isNullOrEmpty()) results.add(api to found)
                }
            }.awaitAll()
            searching = false
        }
    }

    NoProviders()
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, label = { Text("Search all providers") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions { search() },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = ::search) { Text("Search") }
    }
    if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
    if (!searching && results.isEmpty() && query.isNotBlank()) Text("No results yet.", Modifier.padding(top = 8.dp))
    LazyColumn {
        items(results.toList()) { (api, items) -> ResultRow(api.name, items) { open(Result(api, it)) } }
    }
}

@Composable
fun ResultScreen(screen: Result, open: (Screen) -> Unit) {
    var response by remember(screen) { mutableStateOf<LoadResponse?>(null) }
    var error by remember(screen) { mutableStateOf<String?>(null) }
    LaunchedEffect(screen) {
        withContext(Dispatchers.IO) {
            runCatching { screen.api.load(screen.item.url) }
                .onSuccess { if (it == null) error = "Provider returned nothing" else response = it }
                .onFailure { error = it.message ?: it.toString() }
        }
    }
    error?.let { Text("Couldn't load: $it", color = MaterialTheme.colorScheme.error); return }
    val res = response ?: run { CircularProgressIndicator(); return }
    val play = { title: String, data: String -> open(Links(screen.api, title, data)) }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(300.dp).verticalScroll(rememberScrollState())) {
            AsyncImage(
                model = res.posterUrl, contentDescription = res.name, contentScale = ContentScale.Crop,
                modifier = Modifier.size(280.dp, 410.dp).clip(RoundedCornerShape(12.dp)),
            )
            Text(res.name, style = MaterialTheme.typography.headlineSmall)
            Text(listOfNotNull(res.year?.toString(), res.type.name, res.score?.toString()?.let { "★ $it" }).joinToString(" · "))
            res.tags?.takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(", "), style = MaterialTheme.typography.bodySmall) }
            // Plots are HTML on Android; plain text is enough here
            res.plot?.let { Text(it.replace(Regex("<[^>]+>"), "").trim(), Modifier.padding(top = 8.dp)) }
        }
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            when (res) {
                is MovieLoadResponse -> Button(onClick = { play(res.name, res.dataUrl) }) { Text("▶ Play") }
                is LiveStreamLoadResponse -> Button(onClick = { play(res.name, res.dataUrl) }) { Text("▶ Watch live") }
                is TvSeriesLoadResponse -> EpisodeList(mapOf("Episodes" to res.episodes), res.name, play)
                is AnimeLoadResponse -> EpisodeList(res.episodes.mapKeys { it.key.name }, res.name, play)
                else -> Text("This kind of title (${res.type.name}) isn't supported in the Mac app yet.")
            }
        }
    }
}

@Composable
fun EpisodeList(groups: Map<String, List<Episode>>, showName: String, play: (String, String) -> Unit) {
    val nonEmpty = groups.filterValues { it.isNotEmpty() }
    if (nonEmpty.isEmpty()) { Text("No episodes."); return }
    var group by remember(nonEmpty) { mutableStateOf(nonEmpty.keys.first()) }
    val seasons = nonEmpty.getValue(group).groupBy { it.season }
    var season by remember(group) { mutableStateOf(seasons.keys.first()) }

    Row {
        if (nonEmpty.size > 1) nonEmpty.keys.forEach { key ->
            FilterChip(selected = key == group, onClick = { group = key }, label = { Text(key) }, modifier = Modifier.padding(end = 6.dp))
        }
    }
    if (seasons.size > 1) LazyRow {
        items(seasons.keys.toList()) { s ->
            FilterChip(selected = s == season, onClick = { season = s }, label = { Text(s?.let { "Season $it" } ?: "Other") },
                modifier = Modifier.padding(end = 6.dp))
        }
    }
    LazyColumn {
        items(seasons[season].orEmpty()) { ep ->
            val label = listOfNotNull(ep.episode?.let { "E$it" }, ep.name).joinToString(" · ").ifBlank { "Episode" }
            ListItem(
                headlineContent = { Text(label) },
                supportingContent = ep.description?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
                modifier = Modifier.clickable { play("$showName - $label", ep.data) },
            )
        }
    }
}

@Composable
fun LinksScreen(screen: Links) {
    val links = remember(screen) { mutableStateListOf<ExtractorLink>() }
    val subtitles = remember(screen) { mutableStateListOf<SubtitleFile>() }
    var loading by remember(screen) { mutableStateOf(true) }
    var message by remember(screen) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(screen) {
        withContext(Dispatchers.IO) {
            runCatching {
                screen.api.loadLinks(screen.data, false, { subtitles.add(it) }, { links.add(it) })
            }.onFailure { message = "Loading links failed: ${it.message ?: it}" }
        }
        loading = false
    }

    Text(screen.title, style = MaterialTheme.typography.titleLarge)
    Text(
        "Player: ${Player.available ?: "none found (install IINA or VLC)"} · ${subtitles.size} subtitles",
        style = MaterialTheme.typography.bodySmall,
    )
    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
    if (!loading && links.isEmpty()) Text("No links found.")
    LazyColumn {
        items(links.distinctBy { it.url }.sortedByDescending { it.quality }) { link ->
            ListItem(
                headlineContent = { Text(link.name) },
                supportingContent = { Text("${link.source} · ${if (link.quality > 0) "${link.quality}p" else "auto"} · ${link.type}") },
                modifier = Modifier.clickable {
                    message = "Opening in ${Player.available ?: "player"}…"
                    scope.launch(Dispatchers.IO) { message = Player.play(link, screen.title, subtitles.toList()) ?: "Playing in ${Player.available}" }
                },
            )
        }
    }
}

@Composable
fun ExtensionsScreen() {
    val scope = rememberCoroutineScope()
    var repos by remember { mutableStateOf(Extensions.state.repos) }
    var installed by remember { mutableStateOf(Extensions.state.plugins) }
    var repoInput by remember { mutableStateOf("") }
    var selectedRepo by remember { mutableStateOf(repos.firstOrNull()) }
    var plugins by remember { mutableStateOf<List<SitePlugin>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val ext = Extensions

    fun refresh() {
        repos = ext.state.repos; installed = ext.state.plugins; Providers.refresh()
    }
    fun task(label: String, block: suspend () -> Unit) {
        busy = label; message = null
        scope.launch(Dispatchers.IO) {
            runCatching { block() }.onFailure { message = "$label failed: ${it.message ?: it}" }
            refresh(); busy = null
        }
    }

    LaunchedEffect(selectedRepo) {
        plugins = null
        val repo = selectedRepo ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching { ext.listPlugins(repo.url) }.onSuccess { plugins = it }.onFailure { message = "Couldn't read ${repo.name}: ${it.message}" }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(repoInput, { repoInput = it }, label = { Text("Repository URL (repo.json or cloudstreamrepo://…)") },
            singleLine = true, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Button(enabled = repoInput.isNotBlank() && busy == null, onClick = {
            task("Adding repository") { selectedRepo = ext.addRepo(repoInput); repoInput = "" }
        }) { Text("Add") }
    }
    busy?.let { Text("$it…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

    Row(Modifier.fillMaxSize().padding(top = 8.dp)) {
        LazyColumn(Modifier.width(280.dp)) {
            item { Text("Repositories", style = MaterialTheme.typography.titleMedium) }
            items(repos) { repo ->
                ListItem(
                    headlineContent = { Text(repo.name) },
                    supportingContent = { Text(repo.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { TextButton(onClick = { ext.removeRepo(repo.url); refresh() }) { Text("Remove") } },
                    tonalElevation = if (repo == selectedRepo) 4.dp else 0.dp,
                    modifier = Modifier.clickable { selectedRepo = repo },
                )
            }
            item { Text("Installed (${installed.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp)) }
            items(installed) { p ->
                ListItem(
                    headlineContent = { Text(p.name) },
                    supportingContent = ext.loadErrors[p.internalName]?.let { err ->
                        { Text("Failed to load: $err", color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                    },
                    trailingContent = {
                        TextButton(enabled = busy == null, onClick = { task("Removing ${p.name}") { ext.uninstall(p.internalName) } }) { Text("Remove") }
                    },
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        LazyColumn(Modifier.weight(1f)) {
            val list = plugins
            if (selectedRepo != null && list == null) item { CircularProgressIndicator() }
            items(list.orEmpty().sortedBy { it.name.lowercase() }) { p ->
                val have = installed.firstOrNull { it.internalName == p.internalName }
                ListItem(
                    headlineContent = { Text(p.name + if (p.status == 0) " (down)" else "") },
                    supportingContent = {
                        Text(listOfNotNull(p.language, p.tvTypes?.joinToString(), p.description).joinToString(" · "),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    trailingContent = {
                        val label = when {
                            have == null -> "Install"
                            have.version < p.version -> "Update"
                            else -> "Reinstall"
                        }
                        Button(enabled = busy == null, onClick = { task("Installing ${p.name}") { ext.install(p, selectedRepo!!.url) } }) { Text(label) }
                    },
                )
            }
        }
    }
}
