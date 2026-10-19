package com.lagradost.cloudstream.mac

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.base.TrackDescription
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.io.File
import java.nio.ByteBuffer

/** libVLC playback that draws each frame into Compose, since native video views can't be embedded in Compose on macOS. */
class VideoEngine : AutoCloseable {
    companion object {
        /** Null when libVLC was found, otherwise a message for the user. */
        val loadError: String? by lazy {
            // The DMG bundles libVLC under resources/vlc; `run` from Gradle falls back to an installed VLC.app
            System.getProperty("compose.application.resources.dir")?.let { File(it, "vlc/lib") }
                ?.takeIf { it.isDirectory }?.let { System.setProperty("jna.library.path", it.path) }
            if (NativeDiscovery().discover()) null
            else "The video engine (libVLC) wasn't found. Install VLC from videolan.org."
        }

        private val sharedEngine by lazy { runCatching { loadError?.let { error(it) }; VideoEngine() } }

        /** The app's one engine. Slow to create, so main() warms it up in the background; blocks until ready. */
        fun shared(): Result<VideoEngine> = sharedEngine
    }

    private val factory = MediaPlayerFactory("--no-video-title-show", "--quiet")
    val player = factory.mediaPlayers().newEmbeddedMediaPlayer()

    // CS_VLC_LOG=1 prints libVLC's own warnings and errors, for diagnosing streams that won't play
    private val nativeLog = if (System.getenv("CS_VLC_LOG") == "1") factory.application().newLog().apply {
        level = uk.co.caprica.vlcj.log.LogLevel.WARNING
        addLogListener { level, module, _, _, _, _, _, message -> println("libvlc $level [$module] $message") }
    } else null

    var frame by mutableStateOf<ImageBitmap?>(null, neverEqualPolicy())
        private set
    var time by mutableLongStateOf(0L)
        private set
    var length by mutableLongStateOf(0L)
        private set
    var playing by mutableStateOf(false)
        private set
    var buffering by mutableStateOf(true)
        private set
    var ended by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var rate by mutableFloatStateOf(1f)
        private set
    var volume by mutableIntStateOf(100)
        private set

    @Volatile private var pendingSeek = 0L
    @Volatile private var knownLength: Long? = null

    /** A length read from the stream itself (HLS playlist), which beats libVLC's estimate. */
    fun useKnownLength(ms: Long) { knownLength = ms; length = ms }
    @Volatile private var opened = ""
    private var info: ImageInfo? = null
    private var pixels = ByteArray(0)
    // A 1080p frame is 8 MB of native memory; free each one a few frames later instead of waiting for a GC pause
    private val recent = ArrayDeque<Bitmap>()

    init {
        val format = object : BufferFormatCallback {
            override fun getBufferFormat(width: Int, height: Int): BufferFormat {
                info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
                pixels = ByteArray(width * height * 4)
                return RV32BufferFormat(width, height)
            }

            override fun newFormatSize(bufferWidth: Int, bufferHeight: Int, displayWidth: Int, displayHeight: Int) = Unit
            override fun allocatedBuffers(buffers: Array<out ByteBuffer>) = Unit
        }
        val render = object : RenderCallback {
            override fun lock(mediaPlayer: MediaPlayer) = Unit
            override fun unlock(mediaPlayer: MediaPlayer) = Unit
            override fun display(
                mediaPlayer: MediaPlayer, nativeBuffers: Array<out ByteBuffer>, bufferFormat: BufferFormat,
                displayWidth: Int, displayHeight: Int,
            ) {
                val imageInfo = info ?: return
                val buffer = nativeBuffers[0].duplicate().apply { rewind() }
                buffer.get(pixels, 0, minOf(pixels.size, buffer.remaining()))
                val bitmap = Bitmap().apply { installPixels(imageInfo, pixels, imageInfo.width * 4); setImmutable() }
                frame = bitmap.asComposeImageBitmap()
                recent.addLast(bitmap)
                if (recent.size > 4) recent.removeFirst().close()
            }
        }
        player.videoSurface().set(factory.videoSurfaces().newVideoSurface(format, render, true))
        // These run on libVLC's thread: only update state here, never call back into the player
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            // libVLC doesn't always send a final 100% buffering event, so a moving clock also ends buffering
            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
                // Resume by seeking once the clock first moves: libVLC's :start-time stalls some HTTP streams, and
                // seeking HLS on the "playing" event, before any frame, left it buffering forever
                val seek = pendingSeek
                if (seek > 0) { pendingSeek = 0; mediaPlayer.submit { mediaPlayer.controls().setTime(seek) }; return }
                time = newTime; buffering = false
            }
            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) { length = knownLength ?: newLength }
            override fun playing(mediaPlayer: MediaPlayer) {
                playing = true; buffering = pendingSeek > 0
            }
            override fun paused(mediaPlayer: MediaPlayer) { playing = false }
            override fun stopped(mediaPlayer: MediaPlayer) { playing = false }
            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) { buffering = newCache < 100f }
            override fun finished(mediaPlayer: MediaPlayer) { playing = false; ended = true }
            override fun error(mediaPlayer: MediaPlayer) {
                if (nativeLog != null) println("libvlc error event while opening ${opened.take(80)}")
                playing = false; failed = true
            }
        })
    }

    /** libVLC can only send Referer and User-Agent itself; [HeaderProxy] covers links that need more. */
    /** [audioUrl] is a separate audio stream to play alongside, for sites (YouTube) that serve video-only streams. */
    fun open(url: String, referer: String?, userAgent: String?, startAtMs: Long, audioUrl: String? = null, maxHeight: Int? = null) { // null maxHeight: adaptive
        ended = false; failed = false; buffering = true; time = startAtMs; length = 0; knownLength = null; frame = null
        pendingSeek = startAtMs
        opened = url
        val options = listOfNotNull(
            referer?.let { ":http-referrer=$it" }, userAgent?.let { ":http-user-agent=$it" }, audioUrl?.let { ":input-slave=$it" },
            // HLS/DASH adapt to the connection on their own (Auto); a chosen quality fixes the best variant under it
            maxHeight?.let { ":adaptive-maxheight=$it" }, maxHeight?.let { ":adaptive-logic=highest" },
            // The app draws subtitles itself; stop libVLC drawing stream subtitles or sidecar files a second time
            ":no-sub-autodetect-file", if (url.startsWith("http")) ":no-spu" else null,
        )
        player.media().play(url, *options.toTypedArray())
        player.controls().setRate(rate)
        player.audio().setVolume(volume)
    }

    fun togglePause() = if (ended) player.controls().play() else player.controls().pause()
    fun seekTo(ms: Long) { player.controls().setTime(ms.coerceIn(0, maxOf(length - 1000, 0))); time = ms }
    fun skip(ms: Long) = seekTo(time + ms)
    fun setSpeed(value: Float) { rate = value; player.controls().setRate(value) }
    fun changeVolume(value: Int) { volume = value.coerceIn(0, 200); player.audio().setVolume(volume) }

    fun subtitleTracks(): List<TrackDescription> = player.subpictures().trackDescriptions()
    fun currentSubtitle(): Int = player.subpictures().track()
    fun selectSubtitle(id: Int) { player.subpictures().setTrack(id) }
    fun addSubtitle(file: File) { player.subpictures().setSubTitleFile(file) }
    fun audioTracks(): List<TrackDescription> = player.audio().trackDescriptions()
    fun currentAudio(): Int = player.audio().track()
    fun selectAudio(id: Int) { player.audio().setTrack(id) }

    /** Stops playback and clears the picture, keeping the engine for the next video. */
    fun stop() {
        player.controls().stop()
        playing = false; buffering = false; frame = null
    }

    override fun close() {
        nativeLog?.release()
        player.controls().stop()
        player.release()
        factory.release()
    }
}
