package com.lagradost.cloudstream.mac

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import java.io.File

/** Same bookmark categories as the Android app. */
enum class WatchType(val label: String) {
    WATCHING("Watching"), PLANTOWATCH("Plan to watch"), COMPLETED("Completed"), ONHOLD("On hold"), DROPPED("Dropped"),
}

data class Bookmark(
    val apiName: String, val url: String, val name: String, val posterUrl: String?,
    val watchType: WatchType, val updatedAt: Long = System.currentTimeMillis(),
)

/** Where a movie or episode was left, with its show's playlist so Continue watching can resume straight away. */
data class HistoryEntry(
    val apiName: String, val parentUrl: String, val name: String, val posterUrl: String?,
    val items: List<PlayItem>, val index: Int, val positionMs: Long, val durationMs: Long,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val item get() = items[index]
    val progress get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    // Same rule as WatchProgress: the last 30 s count as watched
    val finished get() = durationMs > 0 && positionMs > durationMs - 30_000
}

/** Bookmarks and watch history, saved like the Android app's library (bookmarks.json, history.json). */
object Library {
    private data class Saved(val bookmarks: List<Bookmark> = emptyList(), val history: List<HistoryEntry> = emptyList())
    private val file = File(Extensions.dataDir, "library.json")
    private val saved = runCatching { parseJson(file.readText(), Saved::class) }.getOrDefault(Saved())
    var bookmarks by mutableStateOf(saved.bookmarks)
        private set
    var history by mutableStateOf(saved.history)
        private set

    private fun save() = runCatching { file.writeText(Saved(bookmarks, history).toJson()) }

    fun bookmark(apiName: String, url: String) = bookmarks.firstOrNull { it.apiName == apiName && it.url == url }

    @Synchronized
    fun setBookmark(apiName: String, url: String, bookmark: Bookmark?) {
        bookmarks = listOfNotNull(bookmark) + bookmarks.filterNot { it.apiName == apiName && it.url == url }
        save()
    }

    /** The saved spot for one episode, keyed like WatchProgress by provider and episode data. */
    fun progress(apiName: String, data: String) = history.firstOrNull { it.apiName == apiName && it.item.data == data }

    @Synchronized
    fun record(entry: HistoryEntry) {
        if (entry.durationMs <= 0 && entry.positionMs <= 0) return // never started
        history = listOf(entry) + history.filterNot { it.apiName == entry.apiName && it.item.data == entry.item.data }
        save()
    }

    @Synchronized
    fun removeFromContinueWatching(apiName: String, parentUrl: String) {
        history = history.map { if (it.apiName == apiName && it.parentUrl == parentUrl) it.copy(positionMs = it.durationMs) else it }
        save()
    }

    @Synchronized
    fun clearHistory() {
        history = emptyList()
        save()
    }

    /** One per show, the latest, minus finished ones. */
    val continueWatching get() = history.sortedByDescending { it.updatedAt }.distinctBy { it.apiName to it.parentUrl }.filterNot { it.finished }
}

fun Bookmark.toPoster() = PosterItem(name, url, apiName, posterUrl, subtitle = apiName)

@Composable
fun LibraryScreen(open: (Screen) -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val types = WatchType.entries
    val historyTab = types.size
    Column(Modifier.fillMaxSize()) {
        LargeTitle("Library")
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 16.dp, containerColor = Color.Transparent) {
            types.forEachIndexed { i, type ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text("${type.label} (${Library.bookmarks.count { it.watchType == type }})") }, modifier = Modifier.handCursor())
            }
            Tab(selected = tab == historyTab, onClick = { tab = historyTab }, text = { Text("History") }, modifier = Modifier.handCursor())
        }
        val items = if (tab == historyTab) {
            Library.history.sortedByDescending { it.updatedAt }.distinctBy { it.apiName to it.parentUrl }
                .map { PosterItem(it.name, it.parentUrl, it.apiName, it.posterUrl, subtitle = it.apiName, progress = it.progress) }
        } else {
            Library.bookmarks.filter { it.watchType == types[tab] }.map { it.toPoster() }
        }
        if (items.isEmpty()) MessageBox(
            if (tab == historyTab) "Nothing watched yet" else "Nothing in ${types[tab].label}",
            if (tab == historyTab) "What you play shows up here." else "Use the + button on any title to add it here.",
        ) else LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp), contentPadding = PaddingValues(24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items.size) { i -> val item = items[i]; PosterCard(item, 150.dp) { openDetails(item, open) } }
        }
    }
}

/** Opens a poster's title page, once its extension is loaded. */
fun openDetails(item: PosterItem, open: (Screen) -> Unit) {
    val api = Providers.list.firstOrNull { it.name == item.apiName } ?: return Toasts.show("${item.apiName} isn't installed.")
    open(Screen.Result(api, item.url, item.name))
}

/** "Up Next": where you stopped, resuming straight into the player. */
@Composable
fun ContinueWatchingRow(entries: List<HistoryEntry>, open: (Screen) -> Unit) {
    if (entries.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp)) {
        ShelfTitle("Up Next")
        Spacer(Modifier.height(12.dp))
        LazyRow(contentPadding = PaddingValues(32.dp, 10.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            items(entries, key = { "${it.apiName}|${it.parentUrl}" }) { entry ->
                val api = Providers.list.firstOrNull { it.name == entry.apiName }
                ContinueWatchingCard(
                    entry,
                    onPlay = { if (api == null) Toasts.show("${entry.apiName} isn't installed.") else open(Screen.Play(api, entry.items, entry.index, entry.parent())) },
                    onDetails = { api?.let { open(Screen.Result(it, entry.parentUrl, entry.name)) } },
                    onRemove = { Library.removeFromContinueWatching(entry.apiName, entry.parentUrl) },
                )
            }
        }
    }
}

fun HistoryEntry.parent() = PlayParent(parentUrl, name, posterUrl)

@Composable
private fun ContinueWatchingCard(entry: HistoryEntry, onPlay: () -> Unit, onDetails: () -> Unit, onRemove: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(Modifier.width(320.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).tvLift(interaction, hoverScale = 1.05f).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .handCursor().clickable(interactionSource = interaction, indication = null, onClick = onPlay),
        ) {
            WideImage(entry.posterUrl, null, Modifier.fillMaxSize())
            if (hovered) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
                Box(
                    Modifier.align(Alignment.Center).size(52.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape).border(2.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.PlayArrow, "Resume", tint = Color.White, modifier = Modifier.size(30.dp)) }
                Row(Modifier.align(Alignment.TopEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CardAction(Icons.Outlined.Info, "Details", onDetails)
                    CardAction(Icons.Filled.Close, "Remove from Up Next", onRemove)
                }
            }
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.3f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(entry.progress.coerceIn(0.02f, 1f)).background(MaterialTheme.colorScheme.primary))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(entry.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val episode = entry.item.title.removePrefix(entry.name).removePrefix(" - ").takeIf { entry.items.size > 1 && it.isNotBlank() }
        val minutes = ((entry.durationMs - entry.positionMs) / 60_000).toInt()
        val left = when {
            entry.durationMs <= 0 -> null
            minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m left"
            minutes >= 1 -> "$minutes min left"
            else -> "Almost done"
        }
        Text(listOfNotNull(episode, left).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CardAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(30.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape).handCursor().clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}
