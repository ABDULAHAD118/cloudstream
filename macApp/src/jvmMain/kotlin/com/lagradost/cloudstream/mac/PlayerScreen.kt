package com.lagradost.cloudstream.mac

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class PlayItem(val title: String, val data: String)

val LocalWindowState = staticCompositionLocalOf<WindowState?> { null }

/** Resume positions, keyed by provider and episode data. */
object WatchProgress {
    private data class Saved(val positions: Map<String, Long> = emptyMap())
    private val file = File(Extensions.dataDir, "progress.json")
    private var positions = runCatching { parseJson(file.readText(), Saved::class).positions }.getOrDefault(emptyMap())

    fun key(api: MainAPI, item: PlayItem) = "${api.name}|${item.data}"
    fun get(key: String) = positions[key] ?: 0L

    @Synchronized
    fun set(key: String, position: Long, length: Long) {
        // Like the Android player: near the end counts as watched, so it starts over next time
        positions = if (length > 0 && position > length - 30_000) positions - key else positions + (key to position)
        file.writeText(Saved(positions).toJson())
    }
}

/** Matches the order the Android app prefers: highest quality first, unknown quality last. */
fun sortLinks(links: List<ExtractorLink>) = links.distinctBy { it.url }.sortedByDescending { it.quality }

private val speeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f)

fun formatTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayerScreen(api: MainAPI, items: List<PlayItem>, startIndex: Int, onBack: () -> Unit) {
    val loadError = remember { VideoEngine.loadError }
    if (loadError != null) {
        Column(Modifier.padding(24.dp)) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text(loadError, color = MaterialTheme.colorScheme.error)
        }
        return
    }
    val engine = remember { VideoEngine() }
    val window = LocalWindowState.current
    val scope = rememberCoroutineScope()

    var index by remember { mutableStateOf(startIndex) }
    val item = items[index]
    val links = remember(index) { mutableStateListOf<ExtractorLink>() }
    val subtitles = remember(index) { mutableStateListOf<Pair<SubtitleFile, File>>() }
    var loadingLinks by remember(index) { mutableStateOf(true) }
    var current by remember(index) { mutableStateOf<ExtractorLink?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var lastMove by remember { mutableStateOf(System.currentTimeMillis()) }
    val focus = remember { FocusRequester() }
    val progressKey = WatchProgress.key(api, item)

    fun play(link: ExtractorLink, startAt: Long) {
        current = link
        message = null
        val headers = Player.headersFor(link)
        val extra = headers.keys.any { !it.equals("Referer", true) && !it.equals("User-Agent", true) }
        // Most links only need Referer/User-Agent, which libVLC sends itself; the rest go through the proxy
        val url = if (extra) HeaderProxy.wrap(link.url, headers) else link.url
        engine.open(url, headers["Referer"], headers["User-Agent"] ?: USER_AGENT, startAt)
    }

    fun saveProgress() = WatchProgress.set(progressKey, engine.time, engine.length)

    fun goTo(newIndex: Int) {
        if (newIndex !in items.indices) return
        saveProgress()
        index = newIndex
    }

    DisposableEffect(Unit) { onDispose { saveProgress(); engine.close(); window?.placement = WindowPlacement.Floating } }

    LaunchedEffect(index) {
        withContext(Dispatchers.IO) {
            runCatching {
                api.loadLinks(item.data, false, { sub ->
                    // libVLC can't send headers for subtitle files, so fetch them ourselves
                    scope.launch(Dispatchers.IO) {
                        runCatching { // a broken subtitle shouldn't stop playback
                            val dir = File(Extensions.dataDir, "subtitles").apply { mkdirs() }
                            val ext = sub.url.substringBefore('?').substringAfterLast('.', "srt").take(4)
                            val file = File(dir, "${sub.lang.replace(Regex("[^\\w-]"), "_")}-${sub.url.hashCode()}.$ext")
                            file.writeText(app.get(sub.url, headers = sub.headers ?: emptyMap()).text)
                            subtitles.add(sub to file)
                        }
                    }
                }, { links.add(it) })
            }.onFailure { message = "Loading links failed: ${it.message ?: it}" }
        }
        loadingLinks = false
        val best = sortLinks(links).firstOrNull()
        if (best == null) message = message ?: "No links found for this episode." else play(best, WatchProgress.get(progressKey))
    }

    // A dead link tries the next source, like switching sources by hand on Android
    LaunchedEffect(engine.failed) {
        if (!engine.failed) return@LaunchedEffect
        val sorted = sortLinks(links)
        val next = sorted.getOrNull(sorted.indexOf(current) + 1)
        if (next != null) { message = "${current?.name} failed, trying ${next.name}…"; play(next, engine.time) }
        else message = "None of the ${sorted.size} sources would play. Try “Open in external player”."
    }
    LaunchedEffect(engine.ended) { if (engine.ended && index < items.lastIndex) goTo(index + 1) }
    LaunchedEffect(lastMove, engine.playing) {
        controlsVisible = true
        if (engine.playing) { delay(3000); controlsVisible = false }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun toggleFullscreen() {
        window?.placement = if (window?.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(focus).focusable()
            .onPointerEvent(PointerEventType.Move) { lastMove = System.currentTimeMillis() }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                lastMove = System.currentTimeMillis()
                when (e.key) {
                    Key.Spacebar, Key.K -> engine.togglePause()
                    Key.DirectionLeft, Key.J -> engine.skip(-10_000)
                    Key.DirectionRight, Key.L -> engine.skip(10_000)
                    Key.DirectionUp -> engine.changeVolume(engine.volume + 10)
                    Key.DirectionDown -> engine.changeVolume(engine.volume - 10)
                    Key.M -> engine.changeVolume(if (engine.volume == 0) 100 else 0)
                    Key.F -> toggleFullscreen()
                    Key.Escape -> if (window?.placement == WindowPlacement.Fullscreen) toggleFullscreen() else onBack()
                    Key.N -> goTo(index + 1)
                    Key.LeftBracket -> speeds.lastOrNull { it < engine.rate }?.let(engine::setSpeed)
                    Key.RightBracket -> speeds.firstOrNull { it > engine.rate }?.let(engine::setSpeed)
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        engine.frame?.let { frame ->
            Image(
                frame, contentDescription = item.title, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures(onTap = { engine.togglePause() }, onDoubleTap = { toggleFullscreen() })
                },
            )
        }
        if (loadingLinks || (engine.buffering && !engine.failed && current != null)) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text(if (loadingLinks) "Loading links… (${links.size} found)" else "Buffering…", color = Color.White)
            }
        }
        if (controlsVisible || !engine.playing) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text("← Back", color = Color.White) }
                    Column(Modifier.weight(1f)) {
                        Text(item.title, color = Color.White, style = MaterialTheme.typography.titleMedium)
                        current?.let { Text("${it.source} · ${it.name} · ${if (it.quality > 0) "${it.quality}p" else "auto"}", color = Color.LightGray, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                message?.let { Text(it, color = Color(0xFFFFB4AB), modifier = Modifier.background(Color.Black.copy(alpha = 0.6f)).padding(8.dp)) }
                Spacer(Modifier.weight(1f))
                PlayerControls(
                    engine, links = sortLinks(links), current = current, subtitles = subtitles,
                    hasNext = index < items.lastIndex, nextTitle = items.getOrNull(index + 1)?.title,
                    onSource = { play(it, engine.time) },
                    onNext = { goTo(index + 1) },
                    onFullscreen = ::toggleFullscreen,
                    onExternal = { link ->
                        engine.player.controls().setPause(true)
                        scope.launch(Dispatchers.IO) { message = Player.play(link, item.title, subtitles.map { it.first }) }
                    },
                )
            }
        }
    }
}

@Composable
private fun PlayerControls(
    engine: VideoEngine,
    links: List<ExtractorLink>,
    current: ExtractorLink?,
    subtitles: List<Pair<SubtitleFile, File>>,
    hasNext: Boolean,
    nextTitle: String?,
    onSource: (ExtractorLink) -> Unit,
    onNext: () -> Unit,
    onFullscreen: () -> Unit,
    onExternal: (ExtractorLink) -> Unit,
) {
    val white = ButtonDefaults.textButtonColors(contentColor = Color.White)
    Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 12.dp, vertical = 4.dp)) {
        var dragging by remember { mutableStateOf<Float?>(null) }
        val length = engine.length.coerceAtLeast(1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatTime(dragging?.let { (it * length).toLong() } ?: engine.time), color = Color.White)
            Slider(
                value = dragging ?: (engine.time.toFloat() / length).coerceIn(0f, 1f),
                onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { engine.seekTo((it * length).toLong()) }; dragging = null },
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text(formatTime(engine.length), color = Color.White)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { engine.skip(-10_000) }, colors = white) { Text("⟲ 10") }
            TextButton(onClick = engine::togglePause, colors = white) { Text(if (engine.playing) "❚❚ Pause" else "▶ Play") }
            TextButton(onClick = { engine.skip(10_000) }, colors = white) { Text("10 ⟳") }
            // Same as the Android player's "Skip OP" button
            TextButton(onClick = { engine.skip(85_000) }, colors = white) { Text("Skip OP") }
            Spacer(Modifier.weight(1f))
            Menu("Speed ${engine.rate}x", speeds.map { "${it}x" to (it == engine.rate) }) { engine.setSpeed(speeds[it]) }
            Menu("Sources (${links.size})", links.map { "${it.name} · ${if (it.quality > 0) "${it.quality}p" else "auto"}" to (it == current) }) { onSource(links[it]) }
            SubtitleMenu(engine, subtitles)
            val audio = engine.audioTracks().filter { it.id() >= 0 }
            if (audio.size > 1) Menu("Audio", audio.map { it.description() to (it.id() == engine.currentAudio()) }) { engine.selectAudio(audio[it].id()) }
            Text("Vol", color = Color.White)
            Slider(engine.volume.toFloat(), { engine.changeVolume(it.toInt()) }, valueRange = 0f..200f, modifier = Modifier.width(100.dp))
            if (hasNext) TextButton(onClick = onNext, colors = white) { Text("Next ⏭") }
            current?.let { link -> TextButton(onClick = { onExternal(link) }, colors = white) { Text(Player.available?.let { "Open in $it" } ?: "External") } }
            TextButton(onClick = onFullscreen, colors = white) { Text("⛶") }
        }
        nextTitle?.let { Text("Next: $it", color = Color.LightGray, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun SubtitleMenu(engine: VideoEngine, subtitles: List<Pair<SubtitleFile, File>>) {
    var added by remember { mutableStateOf(setOf<File>()) }
    // Embedded tracks plus extension subtitles; an extension subtitle becomes a track once it's added
    val tracks = engine.subtitleTracks()
    val pending = subtitles.filter { it.second !in added }
    val options = listOf("Off" to (engine.currentSubtitle() < 0)) +
            tracks.filter { it.id() >= 0 }.map { it.description() to (it.id() == engine.currentSubtitle()) } +
            pending.map { it.first.lang to false }
    Menu("Subtitles", options) { i ->
        val embedded = tracks.filter { it.id() >= 0 }
        when {
            i == 0 -> engine.selectSubtitle(-1)
            i - 1 < embedded.size -> engine.selectSubtitle(embedded[i - 1].id())
            else -> pending[i - 1 - embedded.size].second.let { engine.addSubtitle(it); added = added + it }
        }
    }
}

@Composable
private fun Menu(label: String, options: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text(label) }
        DropdownMenu(open, { open = false }) {
            options.forEachIndexed { i, (text, selected) ->
                DropdownMenuItem(text = { Text((if (selected) "✓ " else "    ") + text) }, onClick = { open = false; onPick(i) })
            }
        }
    }
}
