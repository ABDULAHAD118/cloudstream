package com.lagradost.cloudstream.mac

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.compose.AsyncImage
import com.lagradost.cloudstream.mac.Screen.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream4.theme.CloudStreamTheme
import com.lagradost.cloudstream4.theme.CloudStreamThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data object LibraryTab : Screen
    data object ExtensionsTab : Screen
    data object SettingsTab : Screen
    data class Result(val api: MainAPI, val url: String, val name: String) : Screen {
        companion object {
            operator fun invoke(api: MainAPI, item: SearchResponse) = Result(api, item.url, item.name)
        }
    }
    data object DownloadsTab : Screen
    /** [parent] is the show being played, so the history (Up Next) can show and reopen it. */
    data class Play(val api: MainAPI?, val items: List<PlayItem>, val index: Int, val parent: PlayParent? = null) : Screen
}

data class PlayParent(val url: String, val name: String, val posterUrl: String?)

/** APIHolder isn't observable, so the UI re-reads it after extensions load or change. */
object Providers {
    var list by mutableStateOf(listOf<MainAPI>())
    var loading by mutableStateOf(true)
    fun refresh() {
        list = APIHolder.apis.toList().sortedBy { it.name.lowercase() }
    }
}

/** Upstream's CloudStream dark theme, with Apple TV-style San Francisco type (see TvStyle.kt). */
@Composable
fun AppTheme(content: @Composable () -> Unit) = CloudStreamTheme(mode = AppSettings.themeMode, primaryColor = AppSettings.primaryColor) {
    MaterialTheme(colorScheme = MaterialTheme.colorScheme, typography = TvTypography, shapes = MaterialTheme.shapes, content = content)
}

/** Held while the app runs; a second copy would keep its own lists of extensions and downloads and overwrite the first's. */
private var instanceLock: java.nio.channels.FileLock? = null

fun main() {
    QuietLog.install()
    instanceLock = runCatching {
        java.nio.channels.FileChannel.open(
            java.io.File(Extensions.dataDir, ".lock").toPath(),
            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE,
        ).tryLock()
    }.getOrNull()
    if (instanceLock == null) {
        javax.swing.JOptionPane.showMessageDialog(null, "CloudStream is already open. Close it before starting it again.", "CloudStream", javax.swing.JOptionPane.INFORMATION_MESSAGE)
        kotlin.system.exitProcess(0)
    }
    app()
}

private fun app() = application {
    LaunchedEffect(Unit) {
        // Warm up the player engine now, so the first Play doesn't wait for libVLC to start
        AppScope.launch { VideoEngine.shared() }
        withContext(Dispatchers.IO) {
            Extensions.loadAll()
            APIHolder.initAll()
        }
        Providers.refresh()
        Providers.loading = false
    }
    val windowState = rememberWindowState(size = DpSize(1280.dp, 820.dp))
    Window(onCloseRequest = ::exitApplication, title = "CloudStream", state = windowState) {
        CompositionLocalProvider(LocalWindowState provides windowState) {
            AppTheme { Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { App() } }
        }
    }
}

@Composable
fun App(start: List<Screen> = listOf(Home)) {
    val stack = remember { start.toMutableStateList() }
    Toasts.current?.let { message ->
        LaunchedEffect(message) { delay(3500); if (Toasts.current == message) Toasts.current = null }
    }
    Box(Modifier.fillMaxSize()) {
        AppContent(stack)
        Toasts.current?.let {
            Snackbar(Modifier.align(Alignment.BottomCenter).padding(24.dp), containerColor = Color(0xFF2F2F2F), contentColor = Color.White) { Text(it) }
        }
    }
}

@Composable
private fun AppContent(stack: SnapshotStateList<Screen>) {
    val current = stack.last()
    fun open(screen: Screen) = stack.add(screen)
    fun tab(screen: Screen) { stack.clear(); stack.add(screen) }

    // The player takes the whole window, like Android's fullscreen player
    if (current is Play) return PlayerScreen(current.api, current.items, current.index, onProgress = { item, position, length ->
        val api = current.api ?: return@PlayerScreen
        val parent = current.parent ?: return@PlayerScreen
        val index = current.items.indexOf(item).takeIf { it >= 0 } ?: return@PlayerScreen
        Library.record(HistoryEntry(api.name, parent.url, parent.name, parent.posterUrl, current.items, index, position, length))
    }) { stack.removeLast() }

    Row(Modifier.fillMaxSize()) {
        Sidebar(stack.first(), ::tab)
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (current) {
                Home -> HomeScreen(::open)
                Search -> SearchScreen(::open)
                LibraryTab -> LibraryScreen(::open)
                ExtensionsTab -> ExtensionsScreen()
                SettingsTab -> SettingsScreen()
                DownloadsTab -> Column { LargeTitle("Downloads"); Column(Modifier.padding(horizontal = 32.dp)) { DownloadsScreen { open(Play(null, listOf(it), 0)) } } }
                is Result -> ResultScreen(current, ::open) { stack.removeLast() }
                is Play -> Unit
            }
            if (Providers.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        }
    }
}

@Composable
fun NoProviders() {
    if (!Providers.loading && Providers.list.isEmpty()) Column(Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Extension, null, tint = Color(0xFF777777), modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(12.dp))
        Text("No providers yet", style = MaterialTheme.typography.titleLarge)
        Text("Add a repository and install extensions in the Extensions tab.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun CenteredLoader(text: String? = null) = LoadingBox(text = text)

@Composable
fun ReloadButton(onClick: () -> Unit) = IconButton(onClick = onClick) { Icon(Icons.Filled.Refresh, "Reload") }

@Composable
fun ProviderPicker(providers: List<MainAPI>, selected: MainAPI?, onSelect: (MainAPI) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        GlassButton(Icons.Filled.ArrowDropDown, "Choose provider", text = selected?.name ?: "Choose provider") { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            providers.forEach { api ->
                DropdownMenuItem(text = { Text("${api.name} (${api.lang})", fontWeight = if (api == selected) FontWeight.Bold else null) },
                    onClick = { expanded = false; onSelect(api) }, modifier = Modifier.handCursor())
            }
        }
    }
}

/**
 * The Apple TV hero: a backdrop of a featured title with its logo or name, a short summary and the main actions.
 * Slides advance every 8 s and wait while the pointer is over them.
 */
@Composable
private fun HeroCarousel(api: MainAPI, items: List<HeroItem>, open: (Screen) -> Unit) {
    var index by remember { mutableStateOf(0) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = MaterialTheme.colorScheme.background
    val safe = index.coerceIn(0, items.lastIndex)
    LaunchedEffect(safe, hovered, items.size) {
        if (!hovered && items.size > 1) { delay(8_000); index = (safe + 1) % items.size }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().hoverable(interaction)) {
        Box(Modifier.fillMaxWidth().height((maxWidth * 0.48f).coerceIn(380.dp, 620.dp))) {
            Crossfade(targetState = safe, animationSpec = tween(700)) { i ->
                val d = items[i].details
                WideImage(d?.backgroundPosterUrl ?: d?.posterUrl ?: items[i].search.posterUrl, d?.posterHeaders ?: items[i].search.posterHeaders,
                    Modifier.fillMaxSize(), fitAlignment = BiasAlignment(0.75f, 0f))
            }
            Box(Modifier.fillMaxSize().background(leftScrim(background)))
            Box(Modifier.fillMaxSize().background(bottomScrim(background)))
            Crossfade(targetState = safe, animationSpec = tween(450), modifier = Modifier.align(Alignment.BottomStart).padding(start = 72.dp, bottom = 44.dp, end = 72.dp)) { i ->
                HeroText(api, items[i], open)
            }
            if (items.size > 1) {
                Row(Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 52.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    items.indices.forEach { i -> Dot(i == safe) { index = i } }
                }
                AnimatedVisibility(hovered, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp)) {
                    GlassButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous") { index = (safe - 1 + items.size) % items.size }
                }
                AnimatedVisibility(hovered, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) {
                    GlassButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next") { index = (safe + 1) % items.size }
                }
            }
        }
    }
}

@Composable
private fun HeroText(api: MainAPI, item: HeroItem, open: (Screen) -> Unit) {
    val d = item.details
    val details = Result(api, item.search)
    Column(Modifier.widthIn(max = 620.dp)) {
        val logo = d?.logoUrl
        if (logo != null) RemoteImage(logo, d.posterHeaders, Modifier.heightIn(max = 140.dp).widthIn(max = 420.dp), contentScale = ContentScale.Fit, placeholder = false)
        else Text(d?.name ?: item.search.name, style = MaterialTheme.typography.displayMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        val meta = listOfNotNull(
            d?.year?.toString(), (d?.type ?: item.search.type)?.label(),
            d?.duration?.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" },
            d?.score?.toStringNull(0.1, 10, 1)?.let { "★ $it" },
            d?.tags?.filter { it.isNotBlank() }?.take(3)?.takeIf { it.isNotEmpty() }?.joinToString(", "),
        )
        if (meta.isNotEmpty()) {
            Text(meta.joinToString("  ·  "), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.8f))
            Spacer(Modifier.height(10.dp))
        }
        d?.plot?.let {
            Text(htmlToText(it), style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.85f), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(20.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryCapsuleButton("Play", Icons.Filled.PlayArrow) {
                // Movies play straight away; shows open their page to pick an episode
                val data = (d as? MovieLoadResponse)?.dataUrl ?: (d as? LiveStreamLoadResponse)?.dataUrl
                if (d != null && data != null) open(Play(api, listOf(PlayItem(d.name, data)), 0, PlayParent(d.url, d.name, d.backgroundPosterUrl ?: d.posterUrl)))
                else open(details)
            }
            GlassButton(Icons.Outlined.Info, "Details", text = "Details") { open(details) }
            val bookmark = Library.bookmark(api.name, item.search.url)
            GlassButton(if (bookmark != null) Icons.Filled.Check else Icons.Filled.Add, if (bookmark != null) "In Library" else "Add to Library") {
                Library.setBookmark(api.name, item.search.url,
                    if (bookmark != null) null else Bookmark(api.name, item.search.url, d?.name ?: item.search.name, d?.posterUrl ?: item.search.posterUrl, WatchType.PLANTOWATCH))
            }
        }
    }
}

@Composable
fun HomeScreen(open: (Screen) -> Unit) {
    val providers = Providers.list.filter { it.hasMainPage }
    val selected = providers.firstOrNull { it.name == HomeState.selectedName } ?: providers.firstOrNull()
    LaunchedEffect(selected) { selected?.let(HomeState::show) }
    val continueWatching = Library.continueWatching

    if (providers.isEmpty() && continueWatching.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            LargeTitle("Home")
            if (Providers.loading) LoadingBox(text = "Loading extensions…")
            else MessageBox("No extensions yet", "CloudStream doesn't include any content. Add a repository and install extensions to start browsing.",
                "Open Extensions") { open(ExtensionsTab) }
        }
        return
    }
    val rows = HomeState.rows
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
            if (selected != null && HomeState.hero.isNotEmpty()) item(key = "hero") { HeroCarousel(selected, HomeState.hero, open) }
            else item(key = "title") { LargeTitle("Home") }
            if (continueWatching.isNotEmpty()) item(key = "continue") { ContinueWatchingRow(continueWatching, open) }
            when {
                HomeState.loading && rows.isEmpty() -> item { LoadingBox(Modifier.fillMaxWidth().padding(64.dp), "Loading ${selected?.name}…") }
                HomeState.error != null && rows.isEmpty() -> item {
                    MessageBox("Couldn't load ${selected?.name}", HomeState.error, "Try again", Modifier.fillMaxWidth()) { selected?.let(HomeState::load) }
                }
            }
            items(rows, key = { "${it.request.data}|${it.name}" }) { row ->
                PosterRow(
                    row.name, row.items.map { it.toPoster() }, horizontal = row.request.horizontalImages,
                    loadingMore = row.request in HomeState.loadingMore,
                    onReachEnd = if (row.hasNext && selected != null) ({ HomeState.loadMore(selected, row) }) else null,
                ) { item -> selected?.let { open(Result(it, item.url, item.name)) } }
            }
        }
        // Provider choice floats over the top right, like the Apple TV app's account controls
        if (providers.isNotEmpty()) Row(Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ProviderPicker(providers, selected) { HomeState.select(it) }
            GlassButton(Icons.Filled.Refresh, "Reload") { selected?.let(HomeState::load) }
        }
    }
}

@Composable
fun SearchScreen(open: (Screen) -> Unit) {
    val search = { SearchState.search(Providers.list) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize()) {
        LargeTitle("Search") { if (SearchState.searched.isNotEmpty()) GlassButton(Icons.Filled.Refresh, "Reload") { SearchState.query = SearchState.searched; search() } }
        // A filled, rounded field like Apple's search fields
        TextField(
            value = SearchState.query, onValueChange = { SearchState.query = it },
            placeholder = { Text("Search all providers", style = MaterialTheme.typography.bodyLarge) },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (SearchState.query.isNotEmpty()) IconButton(onClick = { SearchState.query = "" }, modifier = Modifier.handCursor()) { Icon(Icons.Filled.Clear, "Clear") } },
            singleLine = true, textStyle = MaterialTheme.typography.bodyLarge, shape = RoundedCornerShape(12.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f),
                unfocusedContainerColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.07f),
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions { search() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp).focusRequester(focus).testTag("home-search"),
        )
        NoProviders()
        if (SearchState.searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp))
        when {
            !SearchState.searching && SearchState.results.isEmpty() && SearchState.searched.isNotEmpty() ->
                MessageBox("No results for “${SearchState.searched}”")
            SearchState.searched.isEmpty() && Providers.list.isNotEmpty() ->
                MessageBox("Search for movies, shows and anime", "Results from every installed provider appear here.")
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            items(SearchState.results, key = { it.api.name }) { row ->
                PosterRow(
                    row.api.name, row.items.map { it.toPoster() }, loadingMore = row.api in SearchState.loadingMore,
                    onReachEnd = if (row.hasNext) ({ SearchState.loadMore(row) }) else null,
                ) { open(Result(row.api, it.url, it.name)) }
            }
        }
    }
}

private val htmlEntity = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")
private val namedEntities = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "hellip" to "…", "mdash" to "—", "ndash" to "–")

/** Plots are HTML on Android (Html.fromHtml); here tags are dropped, line breaks kept and entities decoded. */
fun htmlToText(html: String): String = html
    .replace(Regex("(?i)<br\\s*/?>|</p>"), "\n").replace(Regex("<[^>]+>"), "")
    .replace(htmlEntity) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x", true) -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
            else -> namedEntities[e.lowercase()]
        } ?: m.value
    }.trim()

/** "TvSeries" -> "TV Series", like the Android chip labels. */
fun TvType.label() = name.replace(Regex("([a-z])([A-Z])"), "$1 $2").replace("Tv ", "TV ")

@Composable
fun PluginFilters(
    plugins: List<SitePlugin>,
    types: Set<TvType>, onTypes: (Set<TvType>) -> Unit,
    language: String?, onLanguage: (String?) -> Unit,
    query: String, onQuery: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(query, onQuery, label = { Text("Search extensions") }, singleLine = true, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        var open by remember { mutableStateOf(false) }
        val languages = plugins.map { it.language?.lowercase() ?: "none" }.distinct().sorted()
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.handCursor()) { Text(language?.let { if (it == "none") "No language" else it } ?: "All languages") }
            DropdownMenu(open, { open = false }) {
                DropdownMenuItem(text = { Text("All languages") }, onClick = { open = false; onLanguage(null) }, modifier = Modifier.handCursor())
                languages.forEach { lang ->
                    DropdownMenuItem(text = { Text(if (lang == "none") "No language" else lang) }, onClick = { open = false; onLanguage(lang) }, modifier = Modifier.handCursor())
                }
            }
        }
    }
    // Wrapping instead of a sideways-scrolling row, which is awkward with a mouse
    FlowRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TvType.entries.filter { it != TvType.NSFW }.forEach { type ->
            FilterChip(
                selected = type in types,
                onClick = { onTypes(if (type in types) types - type else types + type) },
                label = { Text(type.label()) }, modifier = Modifier.handCursor(),
            )
        }
    }
}
