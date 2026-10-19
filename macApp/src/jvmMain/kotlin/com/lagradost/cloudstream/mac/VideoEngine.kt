package com.lagradost.cloudstream.mac

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
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
            else "The video engine (libVLC) wasn't found. Install VLC from videolan.org, or use “Open in external player”."
        }
    }

    private val factory = MediaPlayerFactory("--no-video-title-show", "--quiet")
    val player = factory.mediaPlayers().newEmbeddedMediaPlayer()

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
    private var info: ImageInfo? = null
    private var pixels = ByteArray(0)

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
                frame = Image.makeRaster(imageInfo, pixels, imageInfo.width * 4).toComposeImageBitmap()
            }
        }
        player.videoSurface().set(factory.videoSurfaces().newVideoSurface(format, render, true))
        // These run on libVLC's thread: only update state here, never call back into the player
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            // libVLC doesn't always send a final 100% buffering event, so a moving clock also ends buffering
            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) { time = newTime; buffering = false }
            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) { length = newLength }
            override fun playing(mediaPlayer: MediaPlayer) {
                playing = true; buffering = false
                // Resume by seeking once playback runs; libVLC's :start-time option stalls some HTTP streams
                val seek = pendingSeek.also { pendingSeek = 0 }
                if (seek > 0) mediaPlayer.submit { mediaPlayer.controls().setTime(seek) }
            }
            override fun paused(mediaPlayer: MediaPlayer) { playing = false }
            override fun stopped(mediaPlayer: MediaPlayer) { playing = false }
            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) { buffering = newCache < 100f }
            override fun finished(mediaPlayer: MediaPlayer) { playing = false; ended = true }
            override fun error(mediaPlayer: MediaPlayer) { playing = false; failed = true }
        })
    }

    /** libVLC can only send Referer and User-Agent itself; [HeaderProxy] covers links that need more. */
    fun open(url: String, referer: String?, userAgent: String?, startAtMs: Long) {
        ended = false; failed = false; buffering = true; time = startAtMs; length = 0
        pendingSeek = startAtMs
        val options = listOfNotNull(referer?.let { ":http-referrer=$it" }, userAgent?.let { ":http-user-agent=$it" })
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

    override fun close() {
        player.controls().stop()
        player.release()
        factory.release()
    }
}
