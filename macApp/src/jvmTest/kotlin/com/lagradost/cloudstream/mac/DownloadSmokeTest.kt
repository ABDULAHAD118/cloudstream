package com.lagradost.cloudstream.mac

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Network test: downloads a YouTube video (separate video and audio, merged into one MKV) and Apple's public
 * HLS sample (segments joined into TS), then plays both files. Run with CS_SMOKE=1.
 */
class DownloadSmokeTest {
    private fun waitFor(d: Download): Download {
        val deadline = System.currentTimeMillis() + 10 * 60_000
        while (System.currentTimeMillis() < deadline) {
            val cur = Downloads.items.first { it.id == d.id }
            if (cur.status != DownloadStatus.Downloading && cur.status != DownloadStatus.Queued) return cur
            Thread.sleep(1000)
        }
        error("download timed out")
    }

    private fun plays(path: String): VideoEngine {
        val e = VideoEngine()
        e.open(path, null, null, 0)
        val deadline = System.currentTimeMillis() + 20_000
        while (e.time < 1500 && System.currentTimeMillis() < deadline) Thread.sleep(500)
        return e
    }

    @Test
    fun youtubeAndHls() = runBlocking {
        if (System.getenv("CS_SMOKE") != "1") return@runBlocking
        System.setProperty("cloudstream.downloadDir", File("build/test-downloads").absolutePath.also { File(it).deleteRecursively() })
        if (Extensions.state.plugins.none { it.internalName == "YoutubeProvider" }) {
            val repo = Extensions.addRepo(Extensions.OFFICIAL_REPO)
            Extensions.install(Extensions.listPlugins(repo.url).first { it.internalName == "YoutubeProvider" }, repo.url)
        } else Extensions.loadAll()
        val yt = APIHolder.apis.first { it.name == "YouTube" }
        val movie = yt.search("big buck bunny", 1)!!.items.take(6).firstNotNullOf { yt.load(it.url) as? MovieLoadResponse }
        val links = mutableListOf<ExtractorLink>()
        yt.loadLinks(movie.dataUrl, false, {}, { links.add(it) })
        val video = Downloads.enqueue(movie.name, yt.name, Downloads.sourceFor(sortLinks(links, 360).first()))
        val hls = Downloads.enqueue("Apple HLS sample", "Test", DownloadSource(
            "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_4x3/gear1/prog_index.m3u8",
            ExtractorLinkType.M3U8, mapOf("User-Agent" to USER_AGENT),
        ))

        for ((d, ext) in listOf(video to "mkv", hls to "ts")) {
            val done = waitFor(d)
            assertEquals(DownloadStatus.Done, done.status, "${done.title}: ${done.error}")
            assertTrue(done.file.endsWith(".$ext"))
            val e = plays(done.file)
            println("DOWNLOADED ${done.title}: ${File(done.file).length() / 1024} KB, plays to ${e.time} ms, audio ${e.audioTracks().map { it.description() }}")
            assertTrue(e.time >= 1500, "${done.title} didn't play")
            if (d == video) assertTrue(e.audioTracks().any { it.id() >= 0 }, "the separate audio track wasn't merged in")
            e.close()
        }
    }
}
