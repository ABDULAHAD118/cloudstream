package com.lagradost.cloudstream.mac

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/** Something to play: an extension's episode [data], or a downloaded [file] (played offline with its [subtitleFiles]). */
data class PlayItem(
    val title: String, val data: String, val file: String? = null, val subtitleFiles: List<String> = emptyList(),
    /** Names for a downloaded file's audio tracks, in order, when the file itself doesn't carry them. */
    val audioNames: List<String> = emptyList(),
    /** A live channel: no timeline, skipping or saved position. */
    val live: Boolean = false,
)

val LocalWindowState = staticCompositionLocalOf<WindowState?> { null }

/** Resume positions, keyed by provider and episode data. */
object WatchProgress {
    private data class Saved(val positions: Map<String, Long> = emptyMap())
    private val file = File(Extensions.dataDir, "progress.json")
    private var positions = runCatching { parseJson(file.readText(), Saved::class).positions }.getOrDefault(emptyMap())

    fun key(api: MainAPI?, item: PlayItem) = "${api?.name ?: "downloads"}|${item.data}"
    fun get(key: String) = positions[key] ?: 0L

    @Synchronized
    fun set(key: String, position: Long, length: Long) {
        // Like the Android player: near the end counts as watched, so it starts over next time
        positions = if (length > 0 && position > length - 30_000) positions - key else positions + (key to position)
        file.writeText(Saved(positions).toJson())
    }
}

/**
 * The player's quality, saved like Android's preferred-quality setting. [AUTO] lets libVLC's adaptive logic follow
 * the connection on HLS/DASH and picks the best source; a number fixes the quality at that height or the best below it.
 */
object QualityPreference {
    const val AUTO = Int.MAX_VALUE
    val choices = listOf(AUTO to "Auto", 2160 to "4K", 1440 to "1440p", 1080 to "1080p", 720 to "720p", 480 to "480p", 360 to "360p", 240 to "240p", 144 to "144p")
    private val prefs get() = Preferences.named("cloudstream_mac")
    var max: Int
        // The old "max_quality" defaulted to 2160 ("best available"), which now means Auto; lower picks carry over
        get() = prefs.getInt("quality", prefs.getInt("max_quality", AUTO).takeIf { it < 2160 } ?: AUTO)
        set(value) = prefs.edit().putInt("quality", value).apply()
}

/**
 * False for WebM video that needs a separate audio track (YouTube's VP9, its only 1440p/4K): libVLC 3 can't seek
 * that pair at all (tested: it falls back to 0:00 and stays), while MP4/H.264 with the same audio seeks fine.
 */
fun canSeek(link: ExtractorLink): Boolean {
    val webm = link.url.contains("mime=video%2Fwebm", true) || link.url.contains("mime=video/webm", true) ||
        link.url.substringBefore('?').endsWith(".webm", true)
    return !(webm && link.audioTracks.isNotEmpty())
}

/**
 * False for DASH that needs headers beyond Referer and User-Agent (MovieBox's X-MB-Token): DASH skips the proxy
 * (see [playbackUrl]) and libVLC can't send them, so the host answers 403. For DRM, true only for ClearKey with its
 * key, which the proxy decrypts (see [Cenc]); libVLC can't decrypt the rest (Widevine) and shows a black picture.
 */
fun canPlay(link: ExtractorLink): Boolean =
    if (link is DrmExtractorLink) Cenc.keyOf(link) != null
    else link.type != ExtractorLinkType.DASH || link.headers.keys.all { it.equals("Referer", true) || it.equals("User-Agent", true) }

/**
 * Best first up to [maxQuality], like Android, preferring sources that can seek (resume and skipping need it);
 * sources above the cap follow, lowest first, still pickable under Source. Sources libVLC can't open go last.
 */
fun sortLinks(links: List<ExtractorLink>, maxQuality: Int = QualityPreference.max) = links.distinctBy { it.url }.sortedWith(
    compareBy<ExtractorLink> { !canPlay(it) }.thenBy { it.quality > maxQuality }.thenBy { !canSeek(it) }
        .thenBy { if (it.quality > maxQuality) it.quality else -it.quality }
)

/**
 * Holds off display sleep while a video plays, as QuickTime and IINA do; otherwise macOS sleeps the screen after its
 * idle timeout, since watching doesn't touch the mouse or keyboard. caffeinate -w exits with the app if it crashes.
 */
@Composable
fun KeepScreenAwake(active: Boolean) = DisposableEffect(active) {
    // shortcut: macOS only; on Windows the display can sleep mid-video, add SetThreadExecutionState if that bites
    val caffeinate = if (active && !isWindows) runCatching {
        ProcessBuilder("/usr/bin/caffeinate", "-d", "-i", "-w", ProcessHandle.current().pid().toString()).start()
    }.getOrNull() else null
    onDispose { caffeinate?.destroy() }
}

private val speeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f)

fun formatTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayerScreen(
    api: MainAPI?, items: List<PlayItem>, startIndex: Int,
    onProgress: (PlayItem, Long, Long) -> Unit = { _, _, _ -> }, onBack: () -> Unit,
) {
    // Starting libVLC loads ~340 plugins; that happens off the UI thread (and normally already at launch)
    val engine by produceState<Result<VideoEngine>?>(null) { value = withContext(Dispatchers.IO) { VideoEngine.shared() } }
    val ready = engine
    when {
        ready == null -> Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            // The first start after installing can take minutes (macOS checks each libVLC plugin), so allow leaving
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(12.dp).handCursor()) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Text("Starting player…", color = Color.White) }
        }
        ready.isFailure -> Column(Modifier.padding(24.dp)) {
            TextButton(onClick = onBack, modifier = Modifier.handCursor()) { Text("← Back") }
            Text(ready.exceptionOrNull()?.message.orEmpty(), color = MaterialTheme.colorScheme.error)
        }
        else -> PlayerContent(ready.getOrThrow(), api, items, startIndex, onProgress, onBack)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PlayerContent(
    engine: VideoEngine, api: MainAPI?, items: List<PlayItem>, startIndex: Int,
    onProgress: (PlayItem, Long, Long) -> Unit, onBack: () -> Unit,
) {
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
    var pointerAt by remember { mutableStateOf(Offset.Unspecified) }
    val focus = remember { FocusRequester() }
    val progressKey = WatchProgress.key(api, item)
    var startedAt by remember { mutableStateOf(0L) }
    var reload by remember { mutableStateOf(0) }
    var maxQuality by remember { mutableStateOf(QualityPreference.max) }
    var currentAudio by remember { mutableStateOf(0) }
    var downloading by remember { mutableStateOf(false) }
    var openMenu by remember { mutableStateOf<String?>(null) } // "more" or "subtitles"; keeps the controls up
    var contextMenuAt by remember { mutableStateOf<Offset?>(null) }
    var pauseFlash by remember { mutableStateOf(0 to false) } // (count, paused) for the centre icon
    var seekFlash by remember { mutableStateOf(0 to 0L) } // (count, ms) for the double-click and arrow-key feedback
    var volumeFlash by remember { mutableStateOf(0) } // count, for the centre volume level after a key

    fun play(link: ExtractorLink, startAt: Long, audioIndex: Int = defaultAudio(link.audioTracks.map { it.url })) {
        current = link
        currentAudio = audioIndex
        message = if (!canPlay(link) && link is DrmExtractorLink) "${link.name} is DRM-protected, which this player can't decrypt. Pick another source." else null
        val headers = Player.headersFor(link)
        val clearKey = (link as? DrmExtractorLink)?.let(Cenc::keyOf)
        val url = if (clearKey != null) HeaderProxy.wrapClearKey(link.url, headers, clearKey) else playbackUrl(link.url, link.type, headers)
        startedAt = startAt
        // Like ExoPlayer merging the extension's audio tracks: libVLC plays the chosen one next to the video
        val audio = link.audioTracks.getOrNull(audioIndex)?.let { track ->
            playbackUrl(track.url, ExtractorLinkType.VIDEO, headers + track.headers.orEmpty())
        }
        engine.open(url, headers["Referer"], headers["User-Agent"] ?: USER_AGENT, startAt, audio, maxQuality.takeIf { it != QualityPreference.AUTO })
        if (link.type == ExtractorLinkType.M3U8) scope.launch(Dispatchers.IO) {
            runCatching { hlsDurationMs(link.url) { u -> runBlocking { app.get(u, headers = headers).text } } }
                .getOrNull()?.let { if (current == link) engine.useKnownLength(it) }
        }
    }

    // The extension's word until the stream plays, then the stream's: a live clock has no length or runs past it
    // (DASH counts from 1970). Highlights are labelled live but are recordings, and older Up Next entries lack the flag.
    // A function, as the save on leaving runs from the first composition's closure
    fun isLive() = if (engine.started) engine.length <= 0 || engine.time > engine.length + 5_000 else item.live
    val live = isLive()

    // Where "now" is in a live stream: the clock when it reached the live edge, carried on by the wall clock (a
    // paused stream falls behind). Each open at the edge sets it again; a stream that runs ahead of it moves it.
    var liveAnchor by remember { mutableStateOf<Pair<Long, Long>?>(null) } // (clock, wall time)
    fun liveEdge() = liveAnchor?.let { (clock, wall) -> clock + (System.currentTimeMillis() - wall) }
    LaunchedEffect(engine.started) { if (engine.started && isLive() && liveAnchor == null) liveAnchor = engine.time to System.currentTimeMillis() }
    LaunchedEffect(engine.time) { if (liveEdge()?.let { engine.time > it } == true) liveAnchor = engine.time to System.currentTimeMillis() }
    var liveTick by remember { mutableStateOf(0) } // a paused live stream still falls behind; redraw each second
    LaunchedEffect(live) { while (live) { delay(1000); liveTick++ } }

    fun saveProgress() {
        if (isLive()) return // a live channel has no place to come back to, so it stays out of Up Next
        WatchProgress.set(progressKey, engine.time, engine.length)
        onProgress(item, engine.time, engine.length)
    }

    fun goTo(newIndex: Int) {
        if (newIndex !in items.indices) return
        saveProgress()
        index = newIndex
    }

    // The engine is shared and stays alive; leaving only stops playback
    DisposableEffect(Unit) { onDispose { saveProgress(); engine.stop(); window?.placement = WindowPlacement.Floating } }

    LaunchedEffect(index, reload) {
        // Reload fetches the sources again and carries on from the saved position
        links.clear(); subtitles.clear(); loadingLinks = true; current = null
        withContext(Dispatchers.IO) {
            // A downloaded file plays offline: one local source, plus the subtitles saved next to it
            item.file?.let { path ->
                links.add(newExtractorLink("Downloaded", "Downloaded file", path, ExtractorLinkType.VIDEO))
                item.subtitleFiles.map(::File).filter { it.exists() }.forEach { f ->
                    subtitles.add(newSubtitleFile(f.name.removePrefix(File(path).nameWithoutExtension).trim('.'), f.path) to f)
                }
                return@withContext
            }
            runCatching {
                api ?: error("No extension for this video")
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
        val best = sortLinks(links, maxQuality).firstOrNull()
        if (best == null) message = message ?: "No links found for this episode." else play(best, WatchProgress.get(progressKey))
    }

    // A dead link tries the next source, like switching sources by hand on Android
    fun tryNextSource(why: String) {
        if (System.getenv("CS_VLC_LOG") == "1") println("player: ${current?.name} $why (index $index, ${links.size} links)")
        val sorted = sortLinks(links, maxQuality)
        val next = sorted.getOrNull(sorted.indexOf(current) + 1)
        if (next != null) { message = "${current?.name} $why, trying ${next.name}…"; play(next, startedAt) }
        else message = "None of the ${sorted.size} sources would play. Try another episode or Reload sources."
    }
    LaunchedEffect(engine.failed) { if (engine.failed) tryNextSource("failed") }
    // libVLC can buffer forever on a dead server instead of failing, so give each source a deadline
    LaunchedEffect(current) {
        if (current == null) return@LaunchedEffect
        delay(45_000) // slow hosts such as archive.org can take ~20 s before the first frame
        if (!engine.playing && engine.buffering && !engine.failed) tryNextSource("didn't start")
    }
    // A stream that stops moving mid-playback (often right after a seek) is reopened at the same spot,
    // like ExoPlayer retrying a stalled connection
    LaunchedEffect(current) {
        val link = current ?: return@LaunchedEffect
        var last = -1L
        var stalledFor = 0
        while (true) {
            delay(5_000)
            // Only after it has played once: a stream that is still opening is the deadline's business, and cutting
            // its connection every 20 s kept slow live manifests from ever finishing (the reopen interrupts TLS)
            stalledFor = if (engine.playing && engine.started && !engine.ended && engine.time == last) stalledFor + 5 else 0
            last = engine.time
            if (stalledFor >= 20) {
                stalledFor = 0
                message = "Playback stalled, reconnecting…"
                // Live can't resume mid-window safely (an ad break, a dropped segment): rejoin at the live edge
                if (isLive()) liveAnchor = null
                play(link, if (isLive()) 0L else engine.time, currentAudio)
            }
        }
    }
    // Hindi audio by default when the stream itself has languages (HLS audio groups, MKV). Tracks show up once it's
    // playing, so look for a few seconds after each open; choosing another under Audio afterwards sticks.
    // Only after the resume seek has landed and played a second: switching sooner restarted multi-language HLS at
    // 0:00 or stalled it
    LaunchedEffect(engine.opens) {
        if (current == null) return@LaunchedEffect
        snapshotFlow { engine.started }.first { it }
        val resumedAt = engine.time
        snapshotFlow { engine.time }.first { it >= resumedAt + 1000 }
        repeat(20) {
            delay(500)
            val tracks = withContext(Dispatchers.IO) { engine.audioTracks() }.filter { it.id() >= 0 }
            if (tracks.size > 1) {
                hindiTrack(audioLabels(tracks, item.audioNames))?.let { i -> if (tracks[i].id() != engine.currentAudio()) engine.selectAudio(tracks[i].id()) }
                return@LaunchedEffect
            }
        }
    }
    LaunchedEffect(engine.ended) { if (engine.ended && AppSettings.autoPlayNext && index < items.lastIndex) goTo(index + 1) }
    // An open menu, or the pointer on the seek bar (its preview), holds the controls up
    var overSeekBar by remember { mutableStateOf(false) }
    val menuOpen = openMenu != null || contextMenuAt != null || overSeekBar
    // A menu holds the controls up; closing it restarts the 3 s countdown
    LaunchedEffect(lastMove, engine.playing, menuOpen) {
        controlsVisible = true
        if (engine.playing && !menuOpen) { delay(3000); controlsVisible = false }
    }
    // Menus take keyboard focus; take it back when they close so the shortcuts keep working
    LaunchedEffect(menuOpen) { if (!menuOpen) focus.requestFocus() }
    KeepScreenAwake(engine.playing)
    val preview = remember { engine.createPreview() }
    DisposableEffect(preview) { onDispose { preview.release() } }
    LaunchedEffect(engine.opened) { preview.open(engine.opened.takeIf { it.isNotEmpty() }) }

    fun togglePause() {
        pauseFlash = (pauseFlash.first + 1) to engine.playing
        engine.togglePause()
    }

    fun goLive() {
        liveAnchor = null
        current?.let { play(it, 0, currentAudio) } // opening afresh starts at the live edge
    }

    /** Plays [behind] ms before "now", within the window the server keeps (libVLC reports it as the length). */
    fun liveSeekBehind(behind: Long) {
        val edge = liveEdge() ?: return
        if (engine.length <= 0 || behind < 3_000) return goLive() // no window, or close enough to the edge
        engine.seekLive(edge - behind.coerceAtMost((engine.length - 5_000).coerceAtLeast(0)))
    }

    fun skipBy(ms: Long) {
        if (isLive()) {
            if (engine.length <= 0) return // no window to move in
            liveSeekBehind((liveEdge() ?: return) - engine.time - ms)
        } else engine.skip(ms)
        seekFlash = (seekFlash.first + 1) to ms
    }

    fun setVolume(value: Int) {
        engine.changeVolume(value)
        volumeFlash++
    }

    fun toggleFullscreen() {
        window?.placement = if (window?.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
    }

    // Subtitles are drawn by the app (see SubtitleParser); libVLC's own subtitle output is off for streams
    val subtitleOptions = remember(index) { mutableStateListOf<SubtitleOption>() }
    var subtitle by remember(index) { mutableStateOf<SubtitleOption?>(null) }
    var cues by remember(index) { mutableStateOf(emptyList<Cue>()) }
    var subtitleSize by remember { mutableStateOf(SubtitlePreference.size) }
    var subtitleDelay by remember { mutableStateOf(0L) }
    fun chooseSubtitle(option: SubtitleOption?) {
        subtitle = option
        cues = emptyList()
        SubtitlePreference.language = option?.label
        if (option == null) return
        scope.launch(Dispatchers.IO) {
            runCatching { option.load() }
                .onSuccess { if (subtitle == option) cues = it }
                .onFailure { Toasts.show("Couldn't load subtitles: ${it.message ?: it}") }
        }
    }
    // Extension subtitles arrive as files; HLS streams declare their own tracks in the master playlist
    LaunchedEffect(index, subtitles.size) {
        subtitles.forEach { (sub, file) ->
            if (subtitleOptions.none { it.key == file.path })
                subtitleOptions += SubtitleOption(file.path, sub.lang) { SubtitleParser.parse(file.readText()) }
        }
    }
    LaunchedEffect(current) {
        val link = current?.takeIf { it.type == ExtractorLinkType.M3U8 } ?: return@LaunchedEffect
        val headers = Player.headersFor(link)
        val tracks = withContext(Dispatchers.IO) {
            runCatching { HlsSubtitles.tracks(link.url, app.get(link.url, headers = headers).text) }.getOrDefault(emptyList())
        }
        tracks.forEach { t ->
            if (subtitleOptions.none { it.key == t.playlistUrl })
                subtitleOptions += SubtitleOption(t.playlistUrl, t.name) { HlsSubtitles.load(t) { u -> runBlocking { app.get(u, headers = headers).text } } }
        }
    }
    // Keep the language picked on the previous episode
    LaunchedEffect(subtitleOptions.size) {
        if (subtitle == null) SubtitlePreference.language?.let { lang -> subtitleOptions.firstOrNull { it.label == lang } }?.let(::chooseSubtitle)
    }

    if (downloading) DownloadPicker(
        api, item, onDismiss = { downloading = false },
        links = links.toList().takeIf { it.isNotEmpty() }, subtitles = subtitles.map { it.first },
    )

    val menuActions = PlayerMenuActions(
        engine = engine, links = sortLinks(links, maxQuality), current = current, maxQuality = maxQuality, currentAudio = currentAudio,
        audioNames = item.audioNames, live = live,
        onQuality = { q ->
            maxQuality = q; QualityPreference.max = q
            // Switch to the best source under the new cap, keeping the position; if that's the current one and
            // it's adaptive (HLS/DASH), reopen it so libVLC re-picks its variant under the cap
            val best = sortLinks(links, q).firstOrNull()
            if (best != null && best != current) play(best, engine.time)
            else current?.takeIf { it.type != ExtractorLinkType.VIDEO }?.let { play(it, engine.time) }
        },
        onSource = { play(it, engine.time) },
        onExtensionAudio = { i -> current?.let { play(it, engine.time, i) } },
        onReload = { saveProgress(); reload++ },
        onDownload = { downloading = true },
    )

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(focus).focusable()
            .onPointerEvent(PointerEventType.Move) { e ->
                // Hiding the controls changes the layout under a still pointer, which sends a Move without moving;
                // counting that would show them again at once, so the bar blinked every 3 s
                val at = e.changes.first().position
                if (at != pointerAt) { pointerAt = at; lastMove = System.currentTimeMillis() }
            }
            .onPointerEvent(PointerEventType.Press) { e ->
                focus.requestFocus() // a click on a control would otherwise leave the shortcuts without focus
                // Right-click anywhere opens the player menu at the pointer
                if (e.buttons.isSecondaryPressed) contextMenuAt = e.changes.first().position
            }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                lastMove = System.currentTimeMillis()
                when (e.key) {
                    Key.Spacebar, Key.K -> togglePause()
                    Key.DirectionLeft, Key.J -> skipBy(-10_000)
                    Key.DirectionRight, Key.L -> skipBy(10_000)
                    Key.DirectionUp -> setVolume(engine.volume + 5)
                    Key.DirectionDown -> setVolume(engine.volume - 5)
                    Key.M -> setVolume(if (engine.volume == 0) 100 else 0)
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
        // Read in the draw phase: a new video frame only repaints this, rather than recomposing the whole player
        Canvas(
            Modifier.fillMaxSize().semantics { contentDescription = item.title }.pointerInput(Unit) {
                    // Double-click: the left half goes back 10 s, the right half forward, like YouTube
                    detectTapGestures(onTap = { togglePause() }, onDoubleTap = { at ->
                        skipBy(if (at.x < size.width / 2) -10_000L else 10_000L)
                    })
                },
        ) {
            val frame = engine.frame ?: return@Canvas
            val scale = minOf(size.width / frame.width, size.height / frame.height) // fit, like ContentScale.Fit
            val w = (frame.width * scale).toInt(); val h = (frame.height * scale).toInt()
            drawImage(frame, dstOffset = IntOffset((size.width.toInt() - w) / 2, (size.height.toInt() - h) / 2), dstSize = IntSize(w, h))
        }

        Flash(pauseFlash.first, Modifier.align(Alignment.Center)) {
            Icon(if (pauseFlash.second) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Color.White,
                modifier = Modifier.size(88.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(16.dp))
        }
        Flash(seekFlash.first, Modifier.align(if (seekFlash.second < 0) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 80.dp)) {
            Text(if (seekFlash.second < 0) "−10s" else "+10s", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50)).padding(horizontal = 20.dp, vertical = 12.dp))
        }
        Flash(volumeFlash, Modifier.align(Alignment.Center)) {
            Row(Modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50)).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(volumeIcon(engine.volume), null, tint = Color.White, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Text("${engine.volume}%", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }

        val showControls = controlsVisible || !engine.playing || menuOpen
        // The subtitle line sits above the controls while they're shown
        SubtitleParser.at(cues, engine.time - subtitleDelay)?.let { line ->
            SubtitleText(line, subtitleSize, Modifier.align(Alignment.BottomCenter).padding(bottom = if (showControls) 130.dp else 48.dp, start = 48.dp, end = 48.dp))
        }

        // Short waits (a skip into what's already downloaded) don't flash the spinner, like YouTube
        val buffering = engine.buffering && !engine.failed && current != null
        var showBuffering by remember { mutableStateOf(false) }
        LaunchedEffect(buffering) { if (buffering) delay(500); showBuffering = buffering }
        if (loadingLinks || showBuffering) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = PlayerAccent)
                Spacer(Modifier.height(12.dp))
                if (loadingLinks) Text("Loading sources… (${links.size} found)", color = Color.White)
                else {
                    val speed = if (current?.type == ExtractorLinkType.M3U8) HeaderProxy.recentSpeedKBps() else null
                    Text("Buffering… ${engine.bufferPercent}%", color = Color.White)
                    // Seen with real hosts: tens of KB/s, below what 720p needs
                    if (speed != null && speed < 80) Text(
                        "This source is slow right now. A lower quality (⋮ → Quality) plays more smoothly.",
                        color = Color.LightGray, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        AnimatedVisibility(showControls, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) { Box(Modifier.fillMaxSize()) {
            // Top: back and title over a fade, like Netflix
            Row(
                Modifier.fillMaxWidth().align(Alignment.TopStart)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HoverLabel("Back (Esc)", Alignment.BottomCenter) { IconButton(onClick = onBack, modifier = Modifier.handCursor()) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) } }
                Column(Modifier.weight(1f)) {
                    Text(item.title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    current?.let {
                        Text("${it.source} · ${it.name} · ${if (it.quality > 0) "${it.quality}p" else "auto"}", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            message?.let {
                Text(it, color = Color(0xFFFFB4AB), modifier = Modifier.align(Alignment.TopCenter).padding(top = 76.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 6.dp))
            }
            PlayerBar(
                engine, Modifier.align(Alignment.BottomCenter), title = item.title, preview = preview, onSeekBarActive = { overSeekBar = it },
                live = if (!live) null else liveTick.let {
                    LiveBar(liveEdge()?.let { (it - engine.time).coerceAtLeast(0) } ?: 0, engine.length.coerceAtLeast(0), ::liveSeekBehind, ::goLive)
                },
                hasNext = index < items.lastIndex, nextTitle = items.getOrNull(index + 1)?.title,
                onNext = { goTo(index + 1) }, onFullscreen = ::toggleFullscreen, onTogglePause = ::togglePause,
                fullscreen = window?.placement == WindowPlacement.Fullscreen,
                subtitleMenu = {
                    SubtitleMenu(openMenu == "subtitles", { openMenu = if (it) "subtitles" else null }, subtitleOptions, subtitle, subtitleSize, subtitleDelay,
                        onPick = ::chooseSubtitle,
                        onSize = { subtitleSize = it; SubtitlePreference.size = it },
                        onDelay = { subtitleDelay = it })
                },
                moreMenu = { PlayerMoreMenu(menuActions, openMenu == "more") { openMenu = if (it) "more" else null } },
            )
        } }

        // Right-click menu, anchored where the click happened
        contextMenuAt?.let { at ->
            Box(Modifier.offset { IntOffset(at.x.toInt(), at.y.toInt()) }.size(1.dp)) {
                PlayerMenuContent(menuActions, expanded = true, onDismiss = { contextMenuAt = null })
            }
        }
    }
}

/**
 * What libVLC is given to open. Every http(s) stream goes through [HeaderProxy], so libVLC never does network
 * or TLS itself: inside the JVM its TLS handshakes failed with "Interrupted system call" (the JVM's own signals
 * interrupt them), which showed up as endless buffering or every source "failing". The proxy uses OkHttp,
 * sends all of the extension's headers, follows redirects and prefetches HLS segments.
 * shortcut: DASH manifests aren't rewritten by the proxy, so DASH still goes direct.
 */
fun playbackUrl(url: String, type: ExtractorLinkType, headers: Map<String, String>): String =
    if (url.startsWith("http") && type != ExtractorLinkType.DASH) HeaderProxy.wrap(url, headers) else url

/** One subtitle choice; [load] fetches and parses it when picked. */
class SubtitleOption(val key: String, val label: String, val load: suspend () -> List<Cue>)
/** Subtitle size and language, remembered like Android's subtitle settings. */
object SubtitlePreference {
    private val prefs get() = Preferences.named("cloudstream_mac")
    var size: Int
        get() = prefs.getInt("subtitle_size", 24)
        set(v) = prefs.edit().putInt("subtitle_size", v).apply()
    var language: String?
        get() = prefs.getString("subtitle_language", null)
        set(v) = prefs.edit().putString("subtitle_language", v).apply()
}

/** The player's played-part and highlight colour: the accent picked in Settings. */
val PlayerAccent: Color @Composable get() = MaterialTheme.colorScheme.primary

@Composable
private fun SubtitleText(text: String, size: Int, modifier: Modifier) {
    Text(
        text, modifier = modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
        color = Color.White, fontSize = size.sp, textAlign = TextAlign.Center, lineHeight = (size * 1.25).sp,
        style = LocalTextStyle.current.copy(shadow = Shadow(Color.Black, Offset(1f, 1f), 4f)),
    )
}

@Composable
private fun PlayerBar(
    engine: VideoEngine, modifier: Modifier, title: String, live: LiveBar?, preview: SeekPreview?, onSeekBarActive: (Boolean) -> Unit,
    hasNext: Boolean, nextTitle: String?, onNext: () -> Unit,
    fullscreen: Boolean, onFullscreen: () -> Unit, onTogglePause: () -> Unit,
    subtitleMenu: @Composable () -> Unit, moreMenu: @Composable () -> Unit,
) {
    Column(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
            .padding(horizontal = 20.dp).padding(top = 40.dp, bottom = 10.dp),
    ) {
        // A live clock counts from 1970, so live gets a timeline of the window the server keeps, ending at "now"
        if (live != null) Row(verticalAlignment = Alignment.CenterVertically) {
            if (live.window > 0) {
                SeekBar(live.window - live.behind, live.window, { live.seekBehind(live.window - it) }, Modifier.weight(1f), null, onSeekBarActive,
                    label = { "-${formatTime(live.window - it)}" }) // where in the window, as "behind live"
                Spacer(Modifier.width(14.dp))
                if (live.behind > LIVE_SLACK) Text("−${formatTime(live.behind)}", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(end = 10.dp))
            }
            LiveBadge(live.behind <= LIVE_SLACK, live.goLive)
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            SeekBar(engine.time, engine.length, engine::seekTo, Modifier.weight(1f), preview, onSeekBarActive)
            Spacer(Modifier.width(14.dp))
            Text("${formatTime(engine.time)} / ${formatTime(engine.length)}", color = Color.White, fontSize = 14.sp)
        }
        Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            val tint = Color.White
            Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                HoverLabel(if (engine.playing) "Pause (Space)" else "Play (Space)") {
                    IconButton(onClick = onTogglePause, modifier = Modifier.size(52.dp).handCursor()) {
                        Icon(if (engine.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (engine.playing) "Pause" else "Play", tint = tint, modifier = Modifier.size(40.dp))
                    }
                }
                val canSkip = live == null || live.window > 0
                if (canSkip) HoverLabel("Back 10 seconds (←)") { IconButton(onClick = { if (live != null) live.seekBehind(live.behind + 10_000) else engine.skip(-10_000) }, modifier = Modifier.handCursor()) { Icon(Icons.Filled.Replay10, "Back 10 seconds", tint = tint, modifier = Modifier.size(30.dp)) } }
                if (canSkip) HoverLabel("Forward 10 seconds (→)") { IconButton(onClick = { if (live != null) live.seekBehind(live.behind - 10_000) else engine.skip(10_000) }, modifier = Modifier.handCursor()) { Icon(Icons.Filled.Forward10, "Forward 10 seconds", tint = tint, modifier = Modifier.size(30.dp)) } }
                VolumeControl(engine.volume, engine::changeVolume)
            }
            // Netflix puts the title in the middle of the bar
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).widthIn(max = 420.dp))
            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                if (hasNext) HoverLabel("Next episode (N)${nextTitle?.let { ": $it" } ?: ""}") {
                    IconButton(onClick = onNext, modifier = Modifier.handCursor()) { Icon(Icons.Filled.SkipNext, "Next episode${nextTitle?.let { ": $it" } ?: ""}", tint = tint, modifier = Modifier.size(30.dp)) }
                }
                subtitleMenu()
                moreMenu()
                HoverLabel(if (fullscreen) "Exit full screen (F)" else "Full screen (F)") {
                    IconButton(onClick = onFullscreen, modifier = Modifier.handCursor()) { Icon(if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, "Fullscreen", tint = tint, modifier = Modifier.size(30.dp)) }
                }
            }
        }
    }
}

/** Within this of the live edge counts as live: libVLC plays a few seconds behind it anyway. */
private const val LIVE_SLACK = 5_000L

/** A live stream's place: [behind] ms before "now", in the [window] ms the server keeps. */
class LiveBar(val behind: Long, val window: Long, val seekBehind: (Long) -> Unit, val goLive: () -> Unit)

/** Red "LIVE" at the live edge; behind it, a "Go live" button back to it, like YouTube's. */
@Composable
private fun LiveBadge(atEdge: Boolean, goLive: () -> Unit) {
    val badge = @Composable { modifier: Modifier ->
        Row(Modifier.clip(RoundedCornerShape(50)).then(modifier).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).background(if (atEdge) Color(0xFFE53935) else Color.Gray, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(if (atEdge) "LIVE" else "Go live", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
    }
    if (atEdge) badge(Modifier) else HoverLabel("Jump to live") { badge(Modifier.handCursor().clickable(onClick = goLive).background(Color.White.copy(alpha = 0.12f))) }
}

/** Everything the ⋮ and right-click menus can do. */
class PlayerMenuActions(
    val engine: VideoEngine, val links: List<ExtractorLink>, val current: ExtractorLink?, val maxQuality: Int, val currentAudio: Int,
    val audioNames: List<String>, val live: Boolean,
    val onQuality: (Int) -> Unit, val onSource: (ExtractorLink) -> Unit, val onExtensionAudio: (Int) -> Unit,
    val onReload: () -> Unit, val onDownload: () -> Unit,
)

@Composable
private fun PlayerMoreMenu(actions: PlayerMenuActions, open: Boolean, onOpen: (Boolean) -> Unit) {
    Box {
        HoverLabel("Settings") { IconButton(onClick = { onOpen(true) }, modifier = Modifier.handCursor()) { Icon(Icons.Filled.MoreVert, "More options", tint = Color.White) } }
        PlayerMenuContent(actions, open) { onOpen(false) }
    }
}

/** The menu's pages: a list of settings, each opening its choices. */
@Composable
private fun PlayerMenuContent(actions: PlayerMenuActions, expanded: Boolean, onDismiss: () -> Unit) {
    var page by remember { mutableStateOf<String?>(null) }
    val close = { page = null; onDismiss() }
    val e = actions.engine
    DropdownMenu(expanded, close) {
        @Composable fun back() = DropdownMenuItem(text = { Text("Back", fontWeight = FontWeight.Bold) }, onClick = { page = null },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }, modifier = Modifier.handCursor())
        @Composable fun choice(label: String, selected: Boolean, onClick: () -> Unit) = DropdownMenuItem(
            text = { Text(label) }, onClick = { onClick(); close() },
            leadingIcon = { if (selected) Icon(Icons.Filled.Check, null) else Spacer(Modifier.size(24.dp)) }, modifier = Modifier.handCursor(),
        )
        // What Auto is playing now, from the decoded picture: 9/16 of the width so 1920×800 films read as 1080p, the
        // height less libVLC's padding to 16 lines (1080 arrives as 1088), snapped to the usual names
        val autoLevel by remember { derivedStateOf { e.frame?.let { f ->
            val lines = maxOf(f.width * 9 / 16, f.height - 15)
            QualityPreference.choices.map { it.first }.filter { it != QualityPreference.AUTO }.minBy { kotlin.math.abs(it - lines) }
        } } }
        val autoLabel = "Auto" + (autoLevel?.let { " (${it}p)" } ?: "")
        val qualityLabel = if (actions.maxQuality == QualityPreference.AUTO) autoLabel
            else QualityPreference.choices.firstOrNull { it.first == actions.maxQuality }?.second ?: "${actions.maxQuality}p"
        when (page) {
            null -> {
                @Composable fun entry(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String?, onClick: () -> Unit) =
                    DropdownMenuItem(
                        text = { Row { Text(label, Modifier.weight(1f)); value?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } } },
                        leadingIcon = { Icon(icon, null) }, onClick = onClick, modifier = Modifier.widthIn(min = 260.dp).handCursor(),
                    )
                entry(Icons.Filled.Speed, "Speed", "${e.rate}x") { page = "speed" }
                entry(Icons.Filled.HighQuality, "Quality", qualityLabel) { page = "quality" }
                entry(Icons.Filled.VideoLibrary, "Source", "${actions.links.size}") { page = "source" }
                val extensionAudio = actions.current?.audioTracks.orEmpty()
                if (extensionAudio.size > 1 || e.audioTracks().count { it.id() >= 0 } > 1) entry(Icons.Filled.Audiotrack, "Audio", null) { page = "audio" }
                // Same as the Android player's "Skip OP" button
                if (!actions.live) entry(Icons.Filled.FastForward, "Skip intro", "+85 s") { e.skip(85_000); close() }
                entry(Icons.Filled.Refresh, "Reload sources", null) { actions.onReload(); close() }
                if (!actions.live) entry(Icons.Filled.Download, "Download", null) { actions.onDownload(); close() }
            }
            "speed" -> { back(); speeds.forEach { s -> choice("${s}x", s == e.rate) { e.setSpeed(s) } } }
            "quality" -> {
                back()
                QualityPreference.choices.forEach { (q, label) ->
                    choice(if (q == QualityPreference.AUTO) autoLabel else label, q == actions.maxQuality) { actions.onQuality(q) }
                }
            }
            "source" -> {
                back()
                actions.links.forEach { link ->
                    val note = if (!canPlay(link)) " · can't play" else if (canSeek(link)) "" else " · no seeking"
                    choice("${link.name} · ${if (link.quality > 0) "${link.quality}p" else "auto"}$note", link == actions.current) { actions.onSource(link) }
                }
            }
            "audio" -> {
                back()
                val extensionAudio = actions.current?.audioTracks.orEmpty()
                if (extensionAudio.size > 1) extensionAudio.forEachIndexed { i, a -> choice(audioLabel(a.url, i), i == actions.currentAudio) { actions.onExtensionAudio(i) } }
                else e.audioTracks().filter { it.id() >= 0 }.let { tracks ->
                    tracks.zip(audioLabels(tracks, actions.audioNames)).forEach { (t, label) -> choice(label, t.id() == e.currentAudio()) { e.selectAudio(t.id()) } }
                }
            }
        }
    }
}

/** Shows [content] each time [key] changes, then fades it out, like YouTube's tap feedback. */
@Composable
private fun Flash(key: Int, modifier: Modifier, content: @Composable () -> Unit) {
    if (key == 0) return
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(key) { alpha.snapTo(1f); delay(300); alpha.animateTo(0f, tween(400)) }
    Box(modifier.graphicsLayer { this.alpha = alpha.value }) { content() }
}

@Composable
private fun SubtitleMenu(
    open: Boolean, onOpen: (Boolean) -> Unit, options: List<SubtitleOption>, selected: SubtitleOption?, size: Int, delayMs: Long,
    onPick: (SubtitleOption?) -> Unit, onSize: (Int) -> Unit, onDelay: (Long) -> Unit,
) {
    Box {
        HoverLabel("Subtitles") {
            IconButton(onClick = { onOpen(true) }, modifier = Modifier.handCursor()) {
                Icon(Icons.Filled.Subtitles, "Subtitles", tint = if (selected != null) PlayerAccent else Color.White)
            }
        }
        DropdownMenu(open, { onOpen(false) }) {
            @Composable fun item(label: String, isSelected: Boolean, close: Boolean = true, onClick: () -> Unit) = DropdownMenuItem(
                text = { Text(label) }, onClick = { onClick(); if (close) onOpen(false) },
                leadingIcon = { if (isSelected) Icon(Icons.Filled.Check, null) else Spacer(Modifier.size(24.dp)) },
                modifier = Modifier.widthIn(min = 240.dp).handCursor(),
            )
            item("Off", selected == null) { onPick(null) }
            if (options.isEmpty()) DropdownMenuItem(text = { Text("No subtitles for this video", color = MaterialTheme.colorScheme.onSurfaceVariant) }, onClick = {}, enabled = false)
            options.forEach { o -> item(o.label, o == selected) { onPick(o) } }
            HorizontalDivider()
            item("Text size: ${size}", false, close = false) {}
            Row(Modifier.padding(horizontal = 12.dp)) {
                listOf(18 to "Small", 24 to "Medium", 32 to "Large", 40 to "Huge").forEach { (v, l) ->
                    FilterChip(selected = v == size, onClick = { onSize(v) }, label = { Text(l) }, modifier = Modifier.padding(end = 4.dp).handCursor())
                }
            }
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Delay ${"%+.1f".format(delayMs / 1000.0)} s", Modifier.weight(1f))
                TextButton(onClick = { onDelay(delayMs - 500) }, modifier = Modifier.handCursor()) { Text("−0.5") }
                TextButton(onClick = { onDelay(delayMs + 500) }, modifier = Modifier.handCursor()) { Text("+0.5") }
            }
        }
    }
}

private val mimeParam = Regex("""mime=audio(?:/|%2F)(\w+)""")
private val itagParam = Regex("""[?&]itag=(\d+)""")
// YouTube audio formats, best first: Opus 160k, AAC 128k, Opus 70k/50k, AAC 48k
private val youtubeAudioPreference = listOf("251", "140", "250", "249", "139")

/** Extension audio tracks carry only a URL, so label them by number and, when the URL says, format. */
fun audioLabel(url: String, index: Int) = mimeParam.find(url)?.groupValues?.get(1)?.let { "Audio ${index + 1} ($it)" } ?: "Audio ${index + 1}"

// "Hindi", "hin", "hi" as track names or language tags ("Hindi - [hin]", "Track 2 - [hi]", "hi-IN")
private val hindiLabel = Regex("""(?i)\bhindi\b|\[(hin|hi)(-[a-z]+)?]|^(hin|hi)(-[a-z]+)?$""")
// YouTube's dubbed audio says its language in the URL: xtags=...lang=hi, or lang=hi
private val hindiUrl = Regex("""(?i)lang(=|%3D)hi(\b|-|%2D|$)""")

/**
 * What to call libVLC's audio [tracks]: the saved [names] of a downloaded file's tracks when there's one per track
 * (libVLC lists them in the order they were merged), otherwise libVLC's own descriptions.
 */
fun audioLabels(tracks: List<uk.co.caprica.vlcj.player.base.TrackDescription>, names: List<String>): List<String> =
    if (names.size == tracks.size) names else tracks.map { it.description() }

/** The Hindi one among [labels] (libVLC's track descriptions), or null when there's none. */
fun hindiTrack(labels: List<String>): Int? = labels.indexOfFirst { hindiLabel.containsMatchIn(it.trim()) }.takeIf { it >= 0 }

/**
 * The track to start with: Hindi when there is one, otherwise the first; on YouTube the best of its formats, since
 * its first is the lowest bitrate.
 */
fun defaultAudio(urls: List<String>): Int {
    // shortcut: only YouTube's itag and lang are understood; other extensions list their main track first anyway
    val itags = urls.map { itagParam.find(it)?.groupValues?.get(1) }
    val candidates = urls.indices.filter { hindiUrl.containsMatchIn(urls[it]) }.ifEmpty { urls.indices.toList() }
    return youtubeAudioPreference.firstNotNullOfOrNull { tag -> candidates.firstOrNull { itags[it] == tag } } ?: candidates.firstOrNull() ?: 0
}

