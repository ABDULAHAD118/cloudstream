package com.lagradost.cloudstream.mac

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI

private fun openInBrowser(url: String) {
    runCatching { Desktop.getDesktop().browse(URI(url)) }
}

private fun minutes(m: Int) = if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"

/** A title's page, laid out like the Apple TV app's: backdrop header, episodes, cast, related titles and IMDb info. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResultScreen(screen: Screen.Result, open: (Screen) -> Unit, back: () -> Unit) {
    var response by remember(screen) { mutableStateOf<LoadResponse?>(null) }
    var error by remember(screen) { mutableStateOf<String?>(null) }
    var reload by remember(screen) { mutableStateOf(0) }
    var imdb by remember(screen) { mutableStateOf<ImdbInfo?>(null) }
    var reviews by remember(screen) { mutableStateOf<ImdbReviewPage?>(null) }
    var showReviews by remember(screen) { mutableStateOf(false) }
    LaunchedEffect(screen, reload) {
        response = null; error = null
        withContext(Dispatchers.IO) {
            runCatching { screen.api.load(screen.url) }
                .onSuccess { if (it == null) error = "Provider returned nothing" else response = it }
                .onFailure { error = it.message ?: it.toString() }
        }
        // IMDb's rating, credits and reviews come after the page is up, and never block it
        val res = response ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            imdb = runCatching { Imdb.forTitle(res) }.getOrNull()
            imdb?.let { info -> reviews = runCatching { Imdb.reviews(info.id, count = 10) }.getOrNull() }
        }
    }
    Box(Modifier.fillMaxSize()) {
        val res = response
        when {
            error != null -> MessageBox("Couldn't open ${screen.name}", error, "Try again") { reload++ }
            res == null -> LoadingBox(text = "Loading ${screen.name}")
            else -> Details(screen, res, imdb, reviews, open, { reload++ }, { showReviews = true })
        }
        GlassButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", modifier = Modifier.padding(start = 20.dp, top = 20.dp), onClick = back)
    }
    if (showReviews) imdb?.let { ReviewsDialog(it) { showReviews = false } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Details(
    screen: Screen.Result, res: LoadResponse, imdb: ImdbInfo?, reviews: ImdbReviewPage?,
    open: (Screen) -> Unit, onReload: () -> Unit, onAllReviews: () -> Unit,
) {
    val api = screen.api
    val parent = PlayParent(res.url, res.name, res.backgroundPosterUrl ?: res.posterUrl)
    val play = { items: List<PlayItem>, index: Int -> open(Screen.Play(api, items, index, parent)) }
    var downloading by remember(res) { mutableStateOf<PlayItem?>(null) }
    downloading?.let { DownloadPicker(api, it, onDismiss = { downloading = null }) }

    // Series and anime: episodes grouped (dub/sub) and by season; the season is the player's playlist
    val groups: Map<String, List<Episode>> = when (res) {
        is TvSeriesLoadResponse -> mapOf("Episodes" to res.episodes)
        is AnimeLoadResponse -> res.episodes.mapKeys { it.key.name }
        else -> emptyMap()
    }.filterValues { it.isNotEmpty() }
    var group by remember(res) { mutableStateOf(groups.keys.firstOrNull()) }
    val seasonNames = (res as? EpisodeResponse)?.seasonNames.orEmpty()
    val seasons = groups[group].orEmpty().sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: Int.MAX_VALUE })).groupBy { it.season }
    // Open the season watched last, else the first
    val lastSeason = Library.history.firstOrNull { it.apiName == api.name && it.parentUrl == res.url }
        ?.let { h -> seasons.entries.firstOrNull { (_, eps) -> eps.any { it.data == h.item.data } }?.key }
    var season by remember(res, group) { mutableStateOf(lastSeason ?: seasons.keys.firstOrNull()) }
    val episodes = seasons[season].orEmpty()
    fun label(ep: Episode) = listOfNotNull(ep.episode?.let { "E$it" }, ep.name).joinToString(" · ").ifBlank { "Episode" }
    val playlist = episodes.map { PlayItem("${res.name} - ${label(it)}", it.data) }
    val single = when (res) {
        is MovieLoadResponse -> PlayItem(res.name, res.dataUrl)
        is LiveStreamLoadResponse -> PlayItem(res.name, res.dataUrl, live = true)
        else -> null
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { Header(screen, res, imdb, single, playlist, episodes.map(::label), play, onReload) { downloading = it } }
        if (single == null && groups.isEmpty()) item {
            Text("This kind of title (${res.type.name}) isn't supported in the Mac app yet.", Modifier.padding(horizontal = 48.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.error)
        }
        if (groups.isNotEmpty()) {
            item {
                // Dub and season choices as plain text tabs, the chosen one bright
                LazyRow(contentPadding = PaddingValues(start = 48.dp, end = 32.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    if (groups.size > 1) items(groups.keys.toList()) { key -> TabText(key, key == group) { group = key } }
                    if (seasons.size > 1) items(seasons.keys.toList()) { s ->
                        val name = seasonNames.firstOrNull { it.season == s }?.name ?: s?.let { "Season $it" } ?: "Other"
                        TabText(name, s == season) { season = s }
                    } else item { Text("Episodes", style = MaterialTheme.typography.titleLarge) }
                }
            }
            item {
                LazyRow(contentPadding = PaddingValues(start = 48.dp, end = 32.dp, top = 14.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    itemsIndexed(episodes, key = { _, ep -> ep.data }) { i, ep ->
                        EpisodeCard(i, ep, label(ep), res.backgroundPosterUrl ?: res.posterUrl, Library.progress(api.name, ep.data),
                            onDownload = { downloading = playlist[i] }) { play(playlist, i) }
                    }
                }
            }
        }
        // The provider's cast, or IMDb's stars when the provider has none
        val cast = res.actors.orEmpty().ifEmpty { imdb?.stars.orEmpty().map { ActorData(Actor(it.name, it.image)) } }
        if (cast.isNotEmpty()) item {
            ShelfTitle("Cast & Crew", Modifier.padding(start = 16.dp, top = 18.dp))
            LazyRow(contentPadding = PaddingValues(start = 48.dp, end = 32.dp, top = 14.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                items(cast) { actor ->
                    Column(Modifier.width(104.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        RemoteImage(actor.actor.image, null, Modifier.size(96.dp).clip(CircleShape))
                        Spacer(Modifier.height(8.dp))
                        Text(actor.actor.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        (actor.roleString ?: actor.role?.name)?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
        res.recommendations.orEmpty().takeIf { it.isNotEmpty() }?.let { recs ->
            item { Box(Modifier.padding(start = 16.dp)) { PosterRow("More Like This", recs.map { it.toPoster() }) { open(Screen.Result(api, it.url, it.name)) } } }
        }
        imdb?.let { info ->
            item { RatingsAndReviews(info, reviews, onAllReviews) }
            item { InformationSection(info) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(
    screen: Screen.Result, res: LoadResponse, imdb: ImdbInfo?, single: PlayItem?, playlist: List<PlayItem>, labels: List<String>,
    play: (List<PlayItem>, Int) -> Unit, onReload: () -> Unit, onDownload: (PlayItem) -> Unit,
) {
    val apiName = screen.api.name
    val background = MaterialTheme.colorScheme.background
    var plotExpanded by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().heightIn(min = (maxWidth * 0.52f).coerceIn(440.dp, 660.dp))) {
            RemoteImage(res.backgroundPosterUrl ?: res.posterUrl, res.posterHeaders, Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(leftScrim(background)))
            Box(Modifier.matchParentSize().background(bottomScrim(background)))
            Column(Modifier.align(Alignment.BottomStart).padding(start = 48.dp, end = 48.dp, top = 120.dp, bottom = 28.dp).widthIn(max = 680.dp)) {
                val logo = res.logoUrl
                if (logo != null) RemoteImage(logo, res.posterHeaders, Modifier.heightIn(max = 150.dp).widthIn(max = 440.dp), contentScale = ContentScale.Fit, placeholder = false)
                else Text(res.name, style = MaterialTheme.typography.displayMedium, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(14.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    val facts = listOfNotNull(
                        res.year?.toString(), res.type.label(), res.duration?.takeIf { it > 0 }?.let(::minutes),
                        (res as? EpisodeResponse)?.showStatus?.name, res.score?.toStringNull(0.1, 10, 1)?.let { "★ $it" },
                    )
                    facts.forEachIndexed { i, fact ->
                        if (i > 0) Text("·", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.6f))
                        Text(fact, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f))
                    }
                    imdb?.let { ImdbBadges(it) }
                    (res.contentRating ?: imdb?.certificate)?.let { rating ->
                        Text(rating, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
                    }
                }
                res.tags?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let { tags ->
                    Spacer(Modifier.height(6.dp))
                    Text(tags.take(5).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.65f))
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Resume where you left off, else the episode after the last one watched, else the first
                    val items = single?.let(::listOf) ?: playlist
                    val last = Library.history.firstOrNull { it.apiName == apiName && it.parentUrl == res.url }
                    val lastIndex = last?.let { h -> items.indexOfFirst { it.data == h.item.data } } ?: -1
                    val (text, index) = when {
                        lastIndex >= 0 && !last!!.finished && last.positionMs > 0 ->
                            (if (single != null) "Resume" else "Resume ${labels[lastIndex].substringBefore(" ·")}") to lastIndex
                        lastIndex >= 0 && lastIndex < items.lastIndex && single == null -> "Play ${labels[lastIndex + 1].substringBefore(" ·")}" to lastIndex + 1
                        res is LiveStreamLoadResponse -> "Watch live" to 0
                        single == null && labels.isNotEmpty() -> "Play ${labels.first().substringBefore(" ·")}" to 0
                        else -> "Play" to 0
                    }
                    if (items.isNotEmpty()) PrimaryCapsuleButton(text, Icons.Filled.PlayArrow) { play(items, index) }
                    BookmarkButton(Library.bookmark(apiName, screen.url)?.watchType) { type ->
                        Library.setBookmark(apiName, screen.url, type?.let { Bookmark(apiName, screen.url, res.name, res.posterUrl, it) })
                    }
                    if (res is MovieLoadResponse && single != null) GlassButton(Icons.Filled.Download, "Download", text = "Download") { onDownload(single) }
                    res.trailers.firstOrNull()?.let { trailer -> GlassButton(Icons.Filled.Theaters, "Trailer", text = "Trailer") { openInBrowser(trailer.extractorUrl) } }
                    GlassButton(Icons.Filled.OpenInBrowser, "Open in browser") { openInBrowser(res.url) }
                    GlassButton(Icons.Filled.Refresh, "Reload", onClick = onReload)
                }
                (res.plot?.takeIf { it.isNotBlank() }?.let(::htmlToText) ?: imdb?.plot)?.let {
                    Spacer(Modifier.height(18.dp))
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.85f),
                        maxLines = if (plotExpanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.handCursor().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { plotExpanded = !plotExpanded })
                }
                Spacer(Modifier.height(10.dp))
                Text(apiName, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun BookmarkButton(current: WatchType?, onSelect: (WatchType?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        GlassButton(if (current != null) Icons.Filled.Check else Icons.Filled.Add, current?.label ?: "Add to Library", text = current?.label) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            WatchType.entries.forEach { type ->
                DropdownMenuItem(text = { Text(type.label, fontWeight = if (type == current) FontWeight.Bold else null) }, onClick = { open = false; onSelect(type) }, modifier = Modifier.handCursor())
            }
            if (current != null) DropdownMenuItem(text = { Text("Remove from Library") }, onClick = { open = false; onSelect(null) }, modifier = Modifier.handCursor())
        }
    }
}

@Composable
private fun TabText(text: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Text(text, style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = if (selected) 1f else if (hovered) 0.8f else 0.45f),
        modifier = Modifier.hoverable(interaction).handCursor().clickable(interactionSource = interaction, indication = null, onClick = onClick))
}

@Composable
private fun SubtleCapsule(icon: ImageVector, text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.pressScale(interaction).hoverable(interaction)
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = if (hovered) 0.16f else 0.09f), RoundedCornerShape(50))
            .handCursor().clickable(interactionSource = interaction, indication = null, onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** An episode as an Apple TV card: a still with progress, the episode number, title and summary. */
@Composable
private fun EpisodeCard(index: Int, ep: Episode, title: String, fallbackImage: String?, progress: HistoryEntry?, onDownload: () -> Unit, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(Modifier.width(300.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).tvLift(interaction, hoverScale = 1.05f).clip(RoundedCornerShape(12.dp))
                .handCursor().clickable(interactionSource = interaction, indication = null, onClick = onClick).testTag("episode-$index"),
        ) {
            RemoteImage(ep.posterUrl ?: fallbackImage, null, Modifier.fillMaxSize())
            if (hovered) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
                Box(Modifier.align(Alignment.Center).size(48.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape).border(2.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center) { Icon(Icons.Filled.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(28.dp)) }
                Box(Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape)) {
                    IconButton(onClick = onDownload, modifier = Modifier.handCursor()) { Icon(Icons.Filled.Download, "Download episode", tint = Color.White) }
                }
            }
            if (progress?.finished == true) Icon(Icons.Filled.CheckCircle, "Watched", tint = Color.White, modifier = Modifier.align(Alignment.TopStart).padding(8.dp).size(22.dp))
            else if (progress != null && progress.progress > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.3f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress.progress.coerceIn(0.02f, 1f)).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        val number = listOfNotNull(ep.season?.takeIf { it > 0 }?.let { "SEASON $it" }, ep.episode?.let { "EPISODE $it" }).joinToString(", ")
        if (number.isNotEmpty()) Text(number, style = MaterialTheme.typography.labelSmall, color = secondaryText)
        Text(ep.name ?: title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        ep.description?.let { Text(htmlToText(it), style = MaterialTheme.typography.bodySmall, color = secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        ep.runTime?.let { Text("$it min", style = MaterialTheme.typography.labelSmall, color = secondaryText, modifier = Modifier.padding(top = 2.dp)) }
    }
}

/** IMDb's yellow badge with the rating and vote count, and the Metascore in its colour. */
@Composable
private fun ImdbBadges(imdb: ImdbInfo) {
    imdb.rating?.let { rating ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("IMDb", color = Color.Black, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.background(Color(0xFFF5C518), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
            Spacer(Modifier.width(6.dp))
            Text("%.1f".format(rating), style = MaterialTheme.typography.labelLarge, color = Color.White)
            imdb.votes?.let { Text("  ${compactCount(it)}", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f)) }
        }
    }
    imdb.metascore?.let { score ->
        val color = when {
            score >= 61 -> Color(0xFF54A72A)
            score >= 40 -> Color(0xFFFFCC33)
            else -> Color(0xFFFF0000)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$score", color = if (score in 40..60) Color.Black else Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(color, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
            Spacer(Modifier.width(5.dp))
            Text("Metascore", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f))
        }
    }
}

/** The Apple TV "Information" block: facts and credits from IMDb, in columns. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InformationSection(imdb: ImdbInfo) {
    val facts = buildList {
        imdb.released?.let { add("Released" to it) }
        if (imdb.endYear != null && imdb.year != null) add("Years" to "${imdb.year}–${imdb.endYear}")
        imdb.runtimeMinutes?.takeIf { it > 0 }?.let { add("Run Time" to if (it >= 60) "${it / 60} hr ${it % 60} min" else "$it min") }
        imdb.certificate?.let { add("Rated" to it) }
        if (imdb.genres.isNotEmpty()) add("Genres" to imdb.genres.joinToString(", "))
        if (imdb.seasons != null || imdb.episodes != null) add("Episodes" to listOfNotNull(
            imdb.seasons?.let { "$it season${if (it > 1) "s" else ""}" }, imdb.episodes?.let { "$it episodes" }).joinToString(", "))
        if (imdb.creators.isNotEmpty()) add("Created By" to imdb.creators.joinToString(", ") { it.name })
        if (imdb.directors.isNotEmpty()) add((if (imdb.directors.size > 1) "Directors" else "Director") to imdb.directors.joinToString(", ") { it.name })
        if (imdb.writers.isNotEmpty()) add("Writers" to imdb.writers.take(4).joinToString(", ") { it.name })
        if (imdb.stars.isNotEmpty()) add("Starring" to imdb.stars.take(5).joinToString(", ") { it.name })
        if (imdb.countries.isNotEmpty()) add("Region of Origin" to imdb.countries.joinToString(", "))
        if (imdb.languages.isNotEmpty()) add("Languages" to imdb.languages.take(4).joinToString(", "))
        imdb.awards?.let { add("Awards" to it) }
        imdb.budget?.let { add("Budget" to it) }
        imdb.grossWorldwide?.let { add("Box Office" to "$it worldwide") }
        imdb.rating?.let { r -> add("IMDb Rating" to "%.1f/10".format(r) + (imdb.votes?.let { " from ${compactCount(it)} ratings" } ?: "")) }
        imdb.popularity?.let { add("Popularity" to "#$it on IMDb") }
    }
    Column(Modifier.fillMaxWidth().padding(start = 48.dp, end = 32.dp, top = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Information", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            SubtleCapsule(Icons.Filled.OpenInBrowser, "View on IMDb") { openInBrowser(imdb.url) }
        }
        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            facts.forEach { (label, value) ->
                Column(Modifier.width(240.dp)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(3.dp))
                    Text(value, style = MaterialTheme.typography.bodySmall, color = secondaryText)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Information from IMDb", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f))
    }
}
