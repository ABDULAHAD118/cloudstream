package com.lagradost.cloudstream.mac

import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pausing keeps what's downloaded and resuming carries on from there, for byte-range files and for HLS. */
class DownloadResumeTest {
    private val file = Random(1).nextBytes(20 * 1024 * 1024) // 20 MB: three 8 MB chunks
    private val segments = List(30) { i -> Random(i).nextBytes(64 * 1024) }
    private val served = AtomicLong()

    // Slow enough to pause partway: each response trickles out in 64 KB steps
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/file.mp4") { ex ->
            val range = ex.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.split('-')
            val from = range?.get(0)?.toInt() ?: 0
            val to = range?.get(1)?.takeIf { it.isNotEmpty() }?.toInt() ?: (file.size - 1)
            if (range != null) ex.responseHeaders.add("Content-Range", "bytes $from-$to/${file.size}")
            ex.sendResponseHeaders(if (range != null) 206 else 200, (to - from + 1).toLong())
            runCatching {
                ex.responseBody.use { out ->
                    for (pos in from..to step 65536) {
                        val n = minOf(65536, to - pos + 1)
                        out.write(file, pos, n); served.addAndGet(n.toLong()); Thread.sleep(3)
                    }
                }
            }
        }
        createContext("/hls/") { ex ->
            val name = ex.requestURI.path.substringAfterLast('/')
            val body = if (name == "index.m3u8") {
                (listOf("#EXTM3U", "#EXT-X-VERSION:3", "#EXT-X-TARGETDURATION:2", "#EXT-X-MEDIA-SEQUENCE:0") +
                    segments.indices.flatMap { listOf("#EXTINF:2.0,", "seg$it.ts") } + "#EXT-X-ENDLIST").joinToString("\n").toByteArray()
            } else {
                Thread.sleep(60)
                segments[name.removePrefix("seg").removeSuffix(".ts").toInt()].also { served.addAndGet(it.size.toLong()) }
            }
            ex.sendResponseHeaders(200, body.size.toLong())
            runCatching { ex.responseBody.use { it.write(body) } }
        }
        executor = java.util.concurrent.Executors.newCachedThreadPool() // parallel ranges, as real hosts serve them
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"

    private fun download(id: String) = Downloads.items.first { it.id == id }

    private fun waitFor(timeoutMs: Long = 60_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(50)
        }
    }

    private fun pauseResume(title: String, source: DownloadSource, expected: ByteArray) {
        System.setProperty("cloudstream.downloadDir", File("build/test-downloads/resume").absolutePath.also { File(it).deleteRecursively() })
        served.set(0)
        val d = Downloads.enqueue(title, "Test", source)
        waitFor { download(d.id).progress in 0.4f..0.8f }
        Downloads.pause(d.id)
        assertEquals(DownloadStatus.Paused, download(d.id).status)
        // What the server was still writing into connections the app had closed isn't part of the resume
        Thread.sleep(1000)
        val beforeResume = served.get()
        Downloads.resume(d.id)
        waitFor { download(d.id).status.let { it == DownloadStatus.Done || it == DownloadStatus.Failed } }
        try {
            assertEquals(DownloadStatus.Done, download(d.id).status, "error: ${download(d.id).error}")
            assertContentEquals(expected, File(download(d.id).file).readBytes())
            // Starting over would fetch the whole thing again; resuming fetches what was left, plus at most the last
            // unsaved bit of each piece (1 MB per byte range, the segments in flight for HLS)
            val afterResume = served.get() - beforeResume
            assertTrue(afterResume < expected.size * 0.85, "fetched $afterResume of ${expected.size} bytes after resuming")
            assertTrue(File(download(d.id).file).parentFile.listFiles().orEmpty().none { it.name.endsWith(".part") }, "notes left behind")
        } finally {
            Downloads.delete(d.id)
        }
    }

    @Test
    fun byteRangeFileResumesWhereItPaused() =
        pauseResume("Ranged", DownloadSource("$base/file.mp4", ExtractorLinkType.VIDEO, emptyMap()), file)

    @Test
    fun hlsResumesWhereItPaused() =
        pauseResume("Segments", DownloadSource("$base/hls/index.m3u8", ExtractorLinkType.M3U8, emptyMap()), segments.reduce { a, b -> a + b })
}
