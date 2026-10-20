package com.lagradost.cloudstream.mac

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.nio.ByteBuffer

/**
 * Thumbnails for the seek bar. A second, silent VLC player opens the same video. For each
 * hovered time it jumps there, plays just long enough to decode fresh frames (a paused player
 * repeats the old picture first), keeps one small frame and pauses again. Frames are cached per [BUCKET_MS], so
 * moving back and forth over the bar is instant after the first pass.
 *
 * The second player only opens the stream on the first hover, so slow hosts aren't asked for it twice otherwise.
 */
class SeekPreview internal constructor(factory: MediaPlayerFactory) {
    private val player: EmbeddedMediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val requests = Channel<Long>(Channel.CONFLATED)

    private val cache = object : LinkedHashMap<Long, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ImageBitmap>) = size > 400
    }

    /** The frame for the most recently requested time, or null while it loads. */
    var thumbnail by mutableStateOf<ImageBitmap?>(null)
        private set

    @Volatile private var mrl: String? = null
    @Volatile private var wanted: Long? = null
    @Volatile private var awaiting: Long? = null
    @Volatile private var target: Long = 0
    @Volatile private var framesSinceSeek = 0
    @Volatile private var ready = CompletableDeferred<Unit>()
    @Volatile private var arrived: CompletableDeferred<Unit>? = null
    private var info: ImageInfo? = null
    private var pixels = ByteArray(0)

    init {
        player.videoSurface().set(
            factory.videoSurfaces().newVideoSurface(
                object : BufferFormatCallback {
                    override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                        // Small frames are enough for a preview, VLC scales them for us
                        val width = WIDTH
                        val height = (WIDTH * sourceHeight / sourceWidth.coerceAtLeast(1)).coerceIn(2, WIDTH * 2) and 1.inv()
                        info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
                        pixels = ByteArray(width * height * 4)
                        return RV32BufferFormat(width, height)
                    }

                    override fun newFormatSize(bufferWidth: Int, bufferHeight: Int, displayWidth: Int, displayHeight: Int) = Unit
                    override fun allocatedBuffers(buffers: Array<out ByteBuffer>) = Unit
                },
                object : RenderCallback {
                    override fun lock(mediaPlayer: MediaPlayer) = Unit
                    override fun unlock(mediaPlayer: MediaPlayer) = Unit
                    override fun display(
                        mediaPlayer: MediaPlayer,
                        nativeBuffers: Array<out ByteBuffer>,
                        bufferFormat: BufferFormat,
                        displayWidth: Int,
                        displayHeight: Int,
                    ) {
                        val bucket = awaiting ?: return
                        val imageInfo = info ?: return
                        // The first frames after a jump can be the old picture, skip them
                        if (++framesSinceSeek < SKIP_FRAMES) return
                        val buffer = nativeBuffers[0]
                        if (buffer.remaining() < pixels.size) return
                        buffer.get(pixels)
                        buffer.rewind()
                        val image = Bitmap().apply {
                            allocPixels(imageInfo)
                            installPixels(imageInfo, pixels, imageInfo.minRowBytes)
                        }.asComposeImageBitmap()
                        synchronized(cache) { cache[bucket] = image }
                        if (wanted == bucket) thumbnail = image
                        awaiting = null
                        mediaPlayer.submit { mediaPlayer.controls().setPause(true) }
                        arrived?.complete(Unit)
                    }
                },
                true,
            )
        )
        player.events().addMediaPlayerEventListener(object : uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter() {
            // Any of these means the video is open and jumps will be honoured
            override fun mediaPlayerReady(mediaPlayer: MediaPlayer) { ready.complete(Unit) }
            override fun videoOutput(mediaPlayer: MediaPlayer, newCount: Int) { if (newCount > 0) ready.complete(Unit) }
            override fun paused(mediaPlayer: MediaPlayer) { ready.complete(Unit) }
            override fun playing(mediaPlayer: MediaPlayer) { ready.complete(Unit) }
        })
        scope.launch {
            for (bucket in requests) grab(bucket)
        }
    }

    @Volatile private var source: String? = null

    /** The video to preview, or null for none. It's opened on the first [request]. Same url is a no-op. */
    fun open(url: String?) {
        if (url == source) return
        source = url
        mrl = null
        synchronized(cache) { cache.clear() }
        thumbnail = null
        wanted = null
        ready = CompletableDeferred()
        player.submit { player.controls().stop() }
    }

    private fun start(url: String) {
        mrl = url
        player.submit { player.media().startPaused(url, ":no-audio", ":no-spu", ":network-caching=1500") }
    }

    /** Shows the frame near [timeMs], from the cache or by fetching it. */
    fun request(timeMs: Long) {
        val url = source ?: return
        if (mrl == null) start(url)
        val bucket = (timeMs / BUCKET_MS).coerceAtLeast(0)
        wanted = bucket
        val cached = synchronized(cache) { cache[bucket] }
        if (cached != null) thumbnail = cached else requests.trySend(bucket)
    }

    private suspend fun grab(bucket: Long) {
        if (bucket != wanted || synchronized(cache) { bucket in cache }) return
        // A jump before the video has opened is ignored by VLC
        if (withTimeoutOrNull(10_000) { ready.await() } == null) return
        val signal = CompletableDeferred<Unit>()
        arrived = signal
        // Middle of the bucket, so a frame stands for the seconds around it
        target = bucket * BUCKET_MS + BUCKET_MS / 2
        framesSinceSeek = 0
        awaiting = bucket
        player.submit {
            player.controls().setTime(target)
            player.controls().play()
        }
        val got = withTimeoutOrNull(4_000) { signal.await() } != null
        awaiting = null
        if (!got) player.submit { player.controls().setPause(true) }
    }

    fun release() {
        scope.cancel()
        runCatching { player.controls().stop() }
        runCatching { player.release() }
    }

    companion object {
        const val BUCKET_MS = 5_000L
        const val WIDTH = 320
        private const val SKIP_FRAMES = 3
    }
}
