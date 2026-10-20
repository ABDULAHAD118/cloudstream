package com.lagradost.cloudstream.mac

import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An HLS title whose languages are separate audio groups (as Netflix-style hosts serve them) downloads into one
 * file with Hindi first, and the player names each track of that file correctly.
 */
class DownloadAudioNamesTest {
    private val dir = File("build/test-downloads/audio-names").absoluteFile.apply { deleteRecursively(); mkdirs() }
    private val clip = File("/System/Library/CoreServices/ControlCenter.app/Contents/Resources/BentoGalleryIntroduction.mov")
    private val sounds = mapOf("en" to (File("/System/Library/Sounds/Ping.aiff") to 22050), "hi" to (File("/System/Library/Sounds/Glass.aiff") to 48000))

    /** One MPEG-TS segment, made with libVLC from a macOS system file. */
    private fun segment(input: File, out: File, transcode: String?, noAudio: Boolean = false) = runBlocking {
        val factory = MediaPlayerFactory("--quiet", "--vout=dummy", "--aout=dummy")
        val player = factory.mediaPlayers().newMediaPlayer()
        val done = CompletableDeferred<Unit>()
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun finished(mediaPlayer: MediaPlayer) { done.complete(Unit) }
            override fun error(mediaPlayer: MediaPlayer) { done.completeExceptionally(IllegalStateException("libVLC failed on $input")) }
        })
        val chain = listOfNotNull(transcode, "std{access=file,mux=ts,dst='${out.path}'}").joinToString(":")
        player.media().play(input.path, *listOfNotNull(":sout=#$chain", ":sout-all", if (noAudio) ":no-audio" else null).toTypedArray())
        try { withTimeout(60_000) { done.await() } } finally { player.release(); factory.release() }
    }

    @Test
    fun hindiFirstAndNamedInADownloadedFile() {
        if (!clip.exists() || sounds.values.any { !it.first.exists() }) return println("SKIP: macOS sample media not found")
        segment(clip, File(dir, "video.ts"), null, noAudio = true)
        sounds.forEach { (lang, sound) -> segment(sound.first, File(dir, "$lang.ts"), "transcode{acodec=mp4a,ab=96,samplerate=${sound.second},channels=2}") }
        fun playlist(name: String) = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXTINF:5.0,\n$name.ts\n#EXT-X-ENDLIST\n"
        val pages = mapOf(
            // English listed first, as hosts usually do
            "master.m3u8" to """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",LANGUAGE="eng",NAME="1. English",DEFAULT=YES,URI="en.m3u8"
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",LANGUAGE="hin",NAME="2. Hindi",DEFAULT=NO,URI="hi.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,AUDIO="aac"
                video.m3u8
            """.trimIndent(),
            "video.m3u8" to playlist("video"), "en.m3u8" to playlist("en"), "hi.m3u8" to playlist("hi"),
        )
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val name = ex.requestURI.path.trimStart('/')
                val body = pages[name]?.toByteArray() ?: File(dir, name).takeIf { it.isFile }?.readBytes()
                if (body == null) ex.sendResponseHeaders(404, -1) else { ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) } }
            }
            start()
        }
        try {
            System.setProperty("cloudstream.downloadDir", File(dir, "out").path)
            val link = runBlocking { newExtractorLink("Test", "Test", "http://127.0.0.1:${server.address.port}/master.m3u8", ExtractorLinkType.M3U8) }
            val option = runBlocking { downloadOptions(link) }.single()
            assertEquals(listOf("2. Hindi", "1. English"), option.hlsAudioNames)

            val d = Downloads.enqueue("Languages", "Test", option)
            val end = System.currentTimeMillis() + 120_000
            while (Downloads.items.first { it.id == d.id }.status.let { it != DownloadStatus.Done && it != DownloadStatus.Failed }) {
                check(System.currentTimeMillis() < end) { "download timed out" }; Thread.sleep(200)
            }
            val done = Downloads.items.first { it.id == d.id }
            assertEquals(DownloadStatus.Done, done.status, done.error)

            // Play the file and match each track's sample rate to the sound it came from
            val engine = VideoEngine.shared().getOrThrow()
            engine.open(done.file, null, null, 0)
            val until = System.currentTimeMillis() + 20_000
            while (engine.audioTracks().count { it.id() >= 0 } < 2 && System.currentTimeMillis() < until) Thread.sleep(200)
            val tracks = engine.audioTracks().filter { it.id() >= 0 }
            val rates = engine.player.media().info().audioTracks().associate { it.id() to it.rate() }
            engine.stop()
            val labels = audioLabels(tracks, done.source.hlsAudioNames)
            println("tracks: " + tracks.zip(labels).map { (t, l) -> "${t.id()} ${t.description()} ${rates[t.id()]} Hz -> $l" })
            assertEquals(48000, rates[tracks[labels.indexOf("2. Hindi")].id()], "the track named Hindi is the Hindi sound")
            assertEquals(22050, rates[tracks[labels.indexOf("1. English")].id()], "the track named English is the English sound")
            assertEquals(48000, rates[tracks.first().id()], "Hindi is the file's first audio track")
            Downloads.delete(d.id)
        } finally {
            server.stop(0)
        }
    }
}
