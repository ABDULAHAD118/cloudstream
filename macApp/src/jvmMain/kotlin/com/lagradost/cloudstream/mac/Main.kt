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
    data object ExtensionsTab : Screen
    data class Result(val api: MainAPI, val item: SearchResponse) : Screen
    data object DownloadsTab : Screen
    data class Play(val api: MainAPI?, val items: List<PlayItem>, val index: Int) : Screen
}

/** APIHolder isn't observable, so the UI re-reads it after extensions load or change. */
object Providers {
    var list by mutableStateOf(listOf<MainAPI>())
    var loading by mutableStateOf(true)
    fun refresh() {
        list = APIHolder.apis.toList().sortedBy { it.name.lowercase() }
    }
}

/** Netflix-like dark palette over upstream's CloudStream theme (its fonts and shapes stay). */
private val Background = Color(0xFF141414)
private val netflixColors = darkColorScheme(
    primary = NetflixRed, onPrimary = Color.White, secondary = Color(0xFFB3B3B3),
    background = Background, onBackground = Color.White,
    surface = Color(0xFF1F1F1F), onSurface = Color.White,
    surfaceVariant = Color(0xFF2A2A2A), onSurfaceVariant = Color(0xFFB3B3B3),
    surfaceContainer = Color(0xFF232323), surfaceContainerHigh = Color(0xFF2B2B2B),
    secondaryContainer = Color(0xFF3A3A3A), onSecondaryContainer = Color.White,
    error = Color(0xFFFF6B6B),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) = CloudStreamTheme(mode = CloudStreamThemeMode.Dark) {
    MaterialTheme(colorScheme = netflixColors, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes, content = content)
}

fun main() = application {
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
    if (current is Play) return PlayerScreen(current.api, current.items, current.index) { stack.removeLast() }

    Column(Modifier.fillMaxSize().background(Background)) {
        TopBar(
            tab = stack.first(), canGoBack = stack.size > 1, onBack = { stack.removeLast() }, onTab = ::tab,
            onSearch = { SearchState.search(Providers.list); tab(Search) },
        )
        if (Providers.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = NetflixRed)
        Box(Modifier.fillMaxSize()) {
            when (current) {
                Home -> HomeScreen(::open)
                Search -> SearchScreen(::open)
                ExtensionsTab -> Column(Modifier.padding(horizontal = 32.dp, vertical = 16.dp)) { ExtensionsScreen() }
                DownloadsTab -> Column(Modifier.padding(horizontal = 32.dp, vertical = 16.dp)) { DownloadsScreen { open(Play(null, listOf(it), 0)) } }
                is Result -> ResultScreen(current, ::open)
                is Play -> Unit
            }
        }
    }
}

/** Netflix-style top bar: logo, tabs, and a search box that searches every provider. */
@Composable
private fun TopBar(tab: Screen, canGoBack: Boolean, onBack: () -> Unit, onTab: (Screen) -> Unit, onSearch: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).background(Color.Black).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canGoBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
        Text("CLOUDSTREAM", color = NetflixRed, fontWeight = FontWeight.Black, fontSize = 24.sp, letterSpacing = 1.sp,
            modifier = Modifier.padding(end = 28.dp).clickable { onTab(Home) })
        listOf(Home to "Home", Search to "Search", DownloadsTab to "Downloads", ExtensionsTab to "Extensions").forEach { (screen, label) ->
            val selected = tab == screen
            Text(
                label, color = if (selected) Color.White else Color(0xFFB3B3B3),
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { onTab(screen) }.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        if (tab != Search) OutlinedTextField(
            value = SearchState.query, onValueChange = { SearchState.query = it },
            placeholder = { Text("Titles, people, genres") }, singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions { if (SearchState.query.isNotBlank()) onSearch() },
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color(0xFF555555), focusedBorderColor = Color.White,
                unfocusedContainerColor = Color(0xFF141414), focusedContainerColor = Color(0xFF141414)),
            modifier = Modifier.width(340.dp).height(52.dp).testTag("home-search"),
        )
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
fun CenteredLoader(text: String? = null) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = NetflixRed)
        text?.let { Spacer(Modifier.height(12.dp)); Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun PosterCard(item: SearchResponse, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Column(Modifier.width(160.dp).padding(horizontal = 5.dp)) {
        Box(
            Modifier.size(150.dp, 225.dp)
                .graphicsLayer { val s = if (hovered) 1.06f else 1f; scaleX = s; scaleY = s }
                .clip(RoundedCornerShape(6.dp)).background(Color(0xFF2A2A2A))
                .then(if (hovered) Modifier.border(BorderStroke(2.dp, Color.White), RoundedCornerShape(6.dp)) else Modifier)
                .hoverable(hover).clickable(onClick = onClick).testTag("poster"),
            contentAlignment = Alignment.Center,
        ) {
            Text(item.name, color = Color(0xFF777777), textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(8.dp))
            // shortcut: posterHeaders are ignored, so a few providers' posters won't load; pass them through coil if it matters
            AsyncImage(model = item.posterUrl, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            item.quality?.let { q ->
                Text(q.name, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(NetflixRed, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
            }
        }
        Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
            color = if (hovered) Color.White else Color(0xFFB3B3B3), modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
fun ResultRow(
    title: String, items: List<SearchResponse>, onClick: (SearchResponse) -> Unit,
    onLoadMore: (() -> Unit)? = null, loadingMore: Boolean = false,
) {
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 32.dp, top = 20.dp, bottom = 8.dp))
    LazyRow(contentPadding = PaddingValues(horizontal = 27.dp)) {
        items(items) { PosterCard(it) { onClick(it) } }
        // Android loads the next page when you scroll to the end; here it's a card at the end of the row
        if (onLoadMore != null) item {
            Box(Modifier.size(150.dp, 225.dp).padding(horizontal = 5.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF1F1F1F)),
                contentAlignment = Alignment.Center) {
                if (loadingMore) CircularProgressIndicator(color = NetflixRed)
                else OutlinedButton(onClick = onLoadMore, border = BorderStroke(1.dp, Color(0xFF777777))) { Text("Load more", color = Color.White) }
            }
        }
    }
}

@Composable
fun ReloadButton(onClick: () -> Unit) = IconButton(onClick = onClick) { Icon(Icons.Filled.Refresh, "Reload") }

@Composable
fun ProviderPicker(providers: List<MainAPI>, selected: MainAPI?, onSelect: (MainAPI) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, border = BorderStroke(1.dp, Color.White.copy(alpha = 0.6f)),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Black.copy(alpha = 0.4f), contentColor = Color.White)) {
            Text(selected?.name ?: "Choose provider")
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            providers.forEach { api ->
                DropdownMenuItem(text = { Text("${api.name} (${api.lang})") }, onClick = { expanded = false; onSelect(api) })
            }
        }
    }
}

/** The big banner at the top of Home, showing the first title of the first row. */
@Composable
private fun Hero(item: SearchResponse, picker: @Composable () -> Unit, onOpen: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(440.dp)) {
        AsyncImage(item.posterUrl, item.name, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Background, Background.copy(alpha = 0.6f), Color.Transparent))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Background))))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 32.dp, bottom = 32.dp).widthIn(max = 560.dp)) {
            picker()
            Spacer(Modifier.height(16.dp))
            Text(item.name, fontSize = 44.sp, fontWeight = FontWeight.Black, lineHeight = 48.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            item.type?.let { Text(it.label(), color = Color(0xFFB3B3B3), modifier = Modifier.padding(top = 4.dp)) }
            Row(Modifier.padding(top = 18.dp)) {
                Button(onClick = onOpen, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) {
                    Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Button(onClick = onOpen, colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.25f), contentColor = Color.White)) {
                    Icon(Icons.Filled.Info, null); Spacer(Modifier.width(6.dp)); Text("More info", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun HomeScreen(open: (Screen) -> Unit) {
    val providers = Providers.list.filter { it.hasMainPage }
    val selected = providers.firstOrNull { it.name == HomeState.selectedName } ?: providers.firstOrNull()
    LaunchedEffect(selected) { selected?.let(HomeState::show) }

    if (providers.isEmpty()) { if (Providers.loading) CenteredLoader("Loading extensions…") else NoProviders(); return }
    val picker = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderPicker(providers, selected) { HomeState.select(it) }
            ReloadButton { selected?.let(HomeState::load) }
        }
    }
    val rows = HomeState.rows
    if (rows.isEmpty()) {
        Column(Modifier.padding(horizontal = 32.dp, vertical = 16.dp)) { picker() }
        when {
            HomeState.loading -> CenteredLoader("Loading ${selected?.name}…")
            HomeState.error != null -> Text("Couldn't load home page: ${HomeState.error}", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(32.dp))
        }
        return
    }
    val hero = rows.first().items.first()
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
        item { Hero(hero, picker) { selected?.let { open(Result(it, hero)) } } }
        items(rows) { row ->
            ResultRow(
                row.name, row.items, { selected?.let { api -> open(Result(api, it)) } },
                onLoadMore = if (row.hasNext && selected != null) ({ HomeState.loadMore(selected, row) }) else null,
                loadingMore = row.request in HomeState.loadingMore,
            )
        }
    }
}

@Composable
fun SearchScreen(open: (Screen) -> Unit) {
    val search = { SearchState.search(Providers.list) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 32.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = SearchState.query, onValueChange = { SearchState.query = it }, label = { Text("Search all providers") },
                singleLine = true, leadingIcon = { Icon(Icons.Filled.Search, null) }, shape = RoundedCornerShape(10.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions { search() }, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Button(onClick = search, modifier = Modifier.height(52.dp)) { Text("Search", fontWeight = FontWeight.Bold) }
            if (SearchState.searched.isNotEmpty()) ReloadButton { SearchState.query = SearchState.searched; search() }
        }
        NoProviders()
        if (SearchState.searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 32.dp), color = NetflixRed)
        if (!SearchState.searching && SearchState.results.isEmpty() && SearchState.searched.isNotEmpty())
            Text("No results for “${SearchState.searched}”.", Modifier.padding(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            items(SearchState.results) { row ->
                ResultRow(
                    row.api.name, row.items, { open(Result(row.api, it)) },
                    onLoadMore = if (row.hasNext) ({ SearchState.loadMore(row) }) else null,
                    loadingMore = row.api in SearchState.loadingMore,
                )
            }
        }
    }
}

@Composable
fun ResultScreen(screen: Result, open: (Screen) -> Unit) {
    var response by remember(screen) { mutableStateOf<LoadResponse?>(null) }
    var error by remember(screen) { mutableStateOf<String?>(null) }
    var reload by remember(screen) { mutableStateOf(0) }
    LaunchedEffect(screen, reload) {
        response = null; error = null
        withContext(Dispatchers.IO) {
            runCatching { screen.api.load(screen.item.url) }
                .onSuccess { if (it == null) error = "Provider returned nothing" else response = it }
                .onFailure { error = it.message ?: it.toString() }
        }
    }
    error?.let {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
            Text("Couldn't load: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
            Button(onClick = { reload++ }) { Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Try again") }
        }
        return
    }
    val res = response ?: return CenteredLoader()
    val play = { items: List<PlayItem>, index: Int -> open(Play(screen.api, items, index)) }
    var downloading by remember(screen) { mutableStateOf<PlayItem?>(null) }
    downloading?.let { DownloadPicker(screen.api, it, onDismiss = { downloading = null }) }

    // Series and anime: episodes grouped (dub/sub) and by season; the season is the player's playlist
    val groups: Map<String, List<Episode>> = when (res) {
        is TvSeriesLoadResponse -> mapOf("Episodes" to res.episodes)
        is AnimeLoadResponse -> res.episodes.mapKeys { it.key.name }
        else -> emptyMap()
    }.filterValues { it.isNotEmpty() }
    var group by remember(res) { mutableStateOf(groups.keys.firstOrNull()) }
    val seasons = groups[group].orEmpty().groupBy { it.season }
    var season by remember(res, group) { mutableStateOf(seasons.keys.firstOrNull()) }
    val episodes = seasons[season].orEmpty()
    fun label(ep: Episode) = listOfNotNull(ep.episode?.let { "E$it" }, ep.name).joinToString(" · ").ifBlank { "Episode" }
    val playlist = episodes.map { PlayItem("${res.name} - ${label(it)}", it.data) }

    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Box(Modifier.fillMaxWidth().heightIn(min = 460.dp)) {
                AsyncImage(res.backgroundPosterUrl ?: res.posterUrl, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter,
                    modifier = Modifier.matchParentSize().graphicsLayer { alpha = 0.55f })
                Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(Background, Background.copy(alpha = 0.7f), Color.Transparent))))
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Background))))
                Row(Modifier.padding(32.dp)) {
                    AsyncImage(res.posterUrl, res.name, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(240.dp, 360.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A2A2A)))
                    Spacer(Modifier.width(32.dp))
                    Column(Modifier.weight(1f).padding(top = 8.dp)) {
                        Text(res.name, fontSize = 40.sp, fontWeight = FontWeight.Black, lineHeight = 44.sp)
                        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            res.score?.let { Text("★ $it", color = Color(0xFF46D369), fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp)) }
                            listOfNotNull(res.year?.toString(), res.type.label(), res.duration?.let { "${it / 60}h ${it % 60}m".removePrefix("0h ") }).forEach {
                                Text(it, color = Color(0xFFB3B3B3), modifier = Modifier.padding(end = 12.dp))
                            }
                            ReloadButton { reload++ }
                        }
                        res.tags?.takeIf { it.isNotEmpty() }?.let {
                            Text(it.take(8).joinToString(" • "), color = Color(0xFFB3B3B3), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                        }
                        // Plots are HTML on Android; plain text is enough here
                        res.plot?.let { Text(htmlToText(it), Modifier.padding(top = 16.dp).widthIn(max = 720.dp), maxLines = 6, overflow = TextOverflow.Ellipsis) }
                        Row(Modifier.padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                            val first: Pair<List<PlayItem>, Int>? = when (res) {
                                is MovieLoadResponse -> listOf(PlayItem(res.name, res.dataUrl)) to 0
                                is LiveStreamLoadResponse -> listOf(PlayItem(res.name, res.dataUrl)) to 0
                                else -> playlist.takeIf { it.isNotEmpty() }?.let { it to 0 }
                            }
                            first?.let { (items, i) ->
                                Button(onClick = { play(items, i) }, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                                    modifier = Modifier.height(48.dp)) {
                                    Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp))
                                    Text(if (res is LiveStreamLoadResponse) "Watch live" else if (groups.isEmpty()) "Play" else "Play ${label(episodes.first()).substringBefore(" ·")}", fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.width(12.dp))
                                if (res is MovieLoadResponse) OutlinedButton(onClick = { downloading = items[i] }, modifier = Modifier.height(48.dp),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.6f)), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                                    Icon(Icons.Filled.Download, null); Spacer(Modifier.width(6.dp)); Text("Download")
                                }
                            }
                            if (first == null && groups.isEmpty()) Text("This kind of title (${res.type.name}) isn't supported in the Mac app yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (groups.isNotEmpty()) {
            item {
                Row(Modifier.padding(horizontal = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Episodes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 16.dp))
                    if (groups.size > 1) groups.keys.forEach { key ->
                        FilterChip(selected = key == group, onClick = { group = key }, label = { Text(key) }, modifier = Modifier.padding(end = 6.dp))
                    }
                }
                if (seasons.size > 1) LazyRow(contentPadding = PaddingValues(horizontal = 32.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    items(seasons.keys.toList()) { s ->
                        FilterChip(selected = s == season, onClick = { season = s }, label = { Text(s?.let { "Season $it" } ?: "Other") },
                            modifier = Modifier.padding(end = 6.dp))
                    }
                }
            }
            itemsIndexed(episodes) { i, ep -> EpisodeRow(i, ep, label(ep), res.posterUrl, { play(playlist, i) }, { downloading = playlist[i] }) }
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

@Composable
private fun EpisodeRow(index: Int, ep: Episode, title: String, fallbackImage: String?, onPlay: () -> Unit, onDownload: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp).clip(RoundedCornerShape(8.dp))
            .background(if (hovered) Color(0xFF2A2A2A) else Color.Transparent)
            .hoverable(hover).clickable(onClick = onPlay).padding(8.dp).testTag("episode-$index"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${ep.episode ?: index + 1}", fontSize = 22.sp, color = Color(0xFFB3B3B3), modifier = Modifier.width(40.dp))
        Box(Modifier.size(176.dp, 99.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2A2A2A))) {
            AsyncImage(ep.posterUrl ?: fallbackImage, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (hovered) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(40.dp).border(2.dp, Color.White, CircleShape).padding(4.dp))
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Row {
                Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                ep.runTime?.let { Text("${it}m", color = Color(0xFFB3B3B3)) }
            }
            ep.description?.let { Text(it, color = Color(0xFFB3B3B3), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp)) }
        }
        IconButton(onClick = onDownload) { Icon(Icons.Filled.Download, "Download episode") }
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
    var types by remember { mutableStateOf(emptySet<TvType>()) }
    var language by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
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

    var directory by remember { mutableStateOf<List<DirectoryRepo>?>(null) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) {
        directory = null
        directory = withContext(Dispatchers.IO) { runCatching { ext.communityRepos() }.getOrDefault(emptyList()) }
    }

    LaunchedEffect(selectedRepo, reload) {
        plugins = null
        val repo = selectedRepo ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching { ext.listPlugins(repo.url) }.onSuccess { plugins = it }.onFailure { message = "Couldn't read ${repo.name}: ${it.message}" }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(repoInput, { repoInput = it }, label = { Text("Repository URL (repo.json, GitHub project or cloudstreamrepo:// link)") },
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
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Your repositories", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    ReloadButton { reload++ }
                }
            }
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
                    modifier = Modifier.testTag("installed-${p.internalName}"),
                    headlineContent = { Text(p.name) },
                    supportingContent = ext.loadErrors[p.internalName]?.let { err ->
                        { Text("Failed to load: $err", color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                    },
                    trailingContent = {
                        TextButton(enabled = busy == null, onClick = { task("Removing ${p.name}") { ext.uninstall(p.internalName) } }) { Text("Remove") }
                    },
                )
            }
            item {
                Text("Community repositories", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                if (directory == null) Text("Loading…", style = MaterialTheme.typography.bodySmall)
            }
            items(directory.orEmpty().filter { d -> repos.none { it.url == d.url } }) { d ->
                ListItem(
                    headlineContent = { Text(d.name + if (d.verified) " ✓" else "") },
                    supportingContent = { Text(d.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        TextButton(enabled = busy == null, onClick = { task("Adding ${d.name}") { selectedRepo = ext.addRepo(d.url) } }) { Text("Add") }
                    },
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            PluginFilters(plugins.orEmpty(), types, { types = it }, language, { language = it }, query, { query = it })
            LazyColumn {
                val list = plugins
                if (selectedRepo != null && list == null) item { CircularProgressIndicator() }
                items(filterPlugins(list.orEmpty(), types, language, query)) { p ->
                    val have = installed.firstOrNull { it.internalName == p.internalName }
                    ListItem(
                        modifier = Modifier.testTag("plugin-${p.internalName}"),
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
}

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
            OutlinedButton(onClick = { open = true }) { Text(language?.let { if (it == "none") "No language" else it } ?: "All languages") }
            DropdownMenu(open, { open = false }) {
                DropdownMenuItem(text = { Text("All languages") }, onClick = { open = false; onLanguage(null) })
                languages.forEach { lang ->
                    DropdownMenuItem(text = { Text(if (lang == "none") "No language" else lang) }, onClick = { open = false; onLanguage(lang) })
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
                label = { Text(type.label()) },
            )
        }
    }
}
