package com.lagradost.cloudstream.mac

import com.lagradost.cloudstream3.app
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Loopback HTTP proxy that adds the extension's headers (cookies, origin, tokens) to every request, which
 * libVLC can't do itself. HLS playlists are rewritten so their segments and keys go through the proxy too,
 * and the next few segments are fetched ahead in parallel: some hosts cap each connection far below the
 * video's bitrate (one measured 30 KB/s alone, 70 KB/s over six), and libVLC fetches one segment at a time.
 */
object HeaderProxy {
    private val token = UUID.randomUUID().toString() // keeps other local apps from using it as an open proxy
    private val headerSets = ConcurrentHashMap<String, Map<String, String>>()

    private val server: HttpServer by lazy {
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/$token/", ::handle)
            executor = Executors.newCachedThreadPool { Thread(it, "header-proxy").apply { isDaemon = true } }
            start()
        }
    }

    /** Returns a local URL that fetches [url] with [headers]. */
    fun wrap(url: String, headers: Map<String, String>): String {
        val id = UUID.randomUUID().toString().take(8)
        headerSets[id] = headers
        return proxied(id, url)
    }

    private fun proxied(id: String, url: String) =
        "http://127.0.0.1:${server.address.port}/$token/$id?u=${URLEncoder.encode(url, Charsets.UTF_8)}"

    private val uriAttribute = Regex("URI=\"([^\"]+)\"")

    /**
     * Large files are fetched in byte-range chunks while libVLC sees one continuous response. Some hosts (YouTube
     * most of all) throttle a single open-ended request to about playback speed or below; bounded ranges aren't,
     * which is the same trick yt-dlp uses.
     */
    private const val CHUNK = 8L * 1024 * 1024
    private val log = System.getenv("CS_PROXY_LOG") == "1" // prints each request libVLC makes, for diagnosing

    private fun streamInChunks(exchange: HttpExchange, url: String, headers: Map<String, String>, first: okhttp3.Response) {
        val range = first.header("Content-Range")?.let { Regex("""bytes (\d+)-(\d+)/(\d+)""").find(it) }
        val start = range?.groupValues?.get(1)?.toLong() ?: 0
        val total = range?.groupValues?.get(3)?.toLongOrNull()
        val askedForRange = exchange.requestHeaders.getFirst("Range") != null
        first.header("Content-Type")?.let { exchange.responseHeaders.add("Content-Type", it) }
        exchange.responseHeaders.add("Accept-Ranges", "bytes")
        if (total == null) { // no total size: just relay this part
            exchange.sendResponseHeaders(206, first.body.contentLength().takeIf { it >= 0 } ?: 0)
            exchange.responseBody.use { out -> first.body.byteStream().copyTo(out) }
            return
        }
        if (askedForRange) exchange.responseHeaders.add("Content-Range", "bytes $start-${total - 1}/$total")
        exchange.sendResponseHeaders(if (askedForRange) 206 else 200, total - start)
        exchange.responseBody.use { out ->
            first.body.byteStream().copyTo(out)
            var next = range.groupValues[2].toLong() + 1
            while (next < total) {
                val end = minOf(next + CHUNK, total) - 1
                val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) }; header("Range", "bytes=$next-$end") }.build()
                client.newCall(request).execute().use { r ->
                    check(r.code == 206) { "HTTP ${r.code} for a byte range" }
                    r.body.byteStream().copyTo(out)
                }
                next = end + 1
            }
        }
    }

    private const val PREFETCH = 4
    // Played segments kept so skipping back (or a reopen at the same spot) needs no download; ~80 s at 10 s each
    private const val KEEP_BEHIND = 8
    /** Segment URL -> its media playlist's segments and its position there, learnt while rewriting playlists. */
    private val segmentOrder = ConcurrentHashMap<String, Pair<List<String>, Int>>()
    private class Segment(val bytes: ByteArray, val type: String?)
    private val segments = ConcurrentHashMap<String, CompletableFuture<Segment>>()
    private val fetchPool = Executors.newFixedThreadPool(PREFETCH + 2) { Thread(it, "segment-fetch").apply { isDaemon = true } }
    private val finished = java.util.concurrent.ConcurrentLinkedDeque<Pair<Long, Int>>() // (time, bytes) of recent fetches

    /** Download speed over the last 10 s, for the buffering message; null before any HLS data. */
    fun recentSpeedKBps(): Int? {
        val now = System.currentTimeMillis()
        while (finished.peekFirst()?.let { now - it.first > 10_000 } == true) finished.pollFirst()
        return finished.sumOf { it.second }.takeIf { it > 0 }?.let { it / 1024 / 10 }
    }

    private fun fetchSegment(url: String, headers: Map<String, String>): CompletableFuture<Segment> =
        segments.computeIfAbsent(url) {
            CompletableFuture.supplyAsync({
                var last: Throwable? = null
                repeat(3) { // a slow host often drops a connection; try again before giving up
                    try {
                        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
                        return@supplyAsync client.newCall(request).execute().use { r ->
                            check(r.isSuccessful) { "HTTP ${r.code}" }
                            Segment(r.body.bytes(), r.header("Content-Type")).also { finished.addLast(System.currentTimeMillis() to it.bytes.size) }
                        }
                    } catch (t: Throwable) { last = t }
                }
                throw last!!
            }, fetchPool)
        }.also { f -> f.whenComplete { _, e -> if (e != null) segments.remove(url, f) } }

    /**
     * Queues the segments after [url] and returns [url] itself if it was already prefetched (or is on its way).
     * Null means "stream it directly": waiting for a whole segment before sending any of it stalls playback
     * on slow hosts, so only segments fetched ahead come from the cache.
     */
    private fun segment(url: String, headers: Map<String, String>): Segment? {
        val (order, index) = segmentOrder[url] ?: return null
        val ahead = segments[url]
        order.subList(index + 1, minOf(order.size, index + 1 + PREFETCH)).forEach { fetchSegment(it, headers) }
        // Keep memory bounded: drop what was played a while ago
        order.subList(0, maxOf(0, index - KEEP_BEHIND)).forEach { segments.remove(it) }
        return ahead?.let { runCatching { it.get(120, TimeUnit.SECONDS) }.getOrNull() }
    }

    // A video response streams for as long as playback lasts, so no overall call deadline like the app client's
    private val client by lazy {
        app.baseClient.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    /** Points every URI in an HLS playlist at the proxy, resolved against the playlist's own URL. */
    fun rewritePlaylist(body: String, baseUrl: String, wrapUrl: (String) -> String): String {
        val base = baseUrl.toHttpUrl()
        fun absolute(uri: String) = base.resolve(uri)?.toString() ?: uri
        // In a media playlist the plain lines are segments; remember their order for prefetching
        if ("#EXTINF" in body) {
            val order = body.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map(::absolute)
            order.forEachIndexed { i, u -> segmentOrder[u] = order to i }
        }
        return body.lines().joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                line.startsWith("#") -> uriAttribute.replace(line) { "URI=\"${wrapUrl(absolute(it.groupValues[1]))}\"" }
                else -> wrapUrl(absolute(line.trim()))
            }
        }
    }

    private fun handle(exchange: HttpExchange) {
        try {
            val id = exchange.requestURI.path.substringAfterLast('/')
            val headers = headerSets[id] ?: return exchange.sendResponseHeaders(404, -1)
            val target = URLDecoder.decode(exchange.requestURI.rawQuery.removePrefix("u="), Charsets.UTF_8)
            val head = exchange.requestMethod.equals("HEAD", true)
            if (!head && exchange.requestHeaders.getFirst("Range") == null) {
                segment(target, headers)?.let { seg ->
                    seg.type?.let { exchange.responseHeaders.add("Content-Type", it) }
                    exchange.sendResponseHeaders(200, seg.bytes.size.toLong())
                    exchange.responseBody.use { it.write(seg.bytes) }
                    return
                }
            }
            val wanted = exchange.requestHeaders.getFirst("Range")
            if (log) println("proxy: ${exchange.requestMethod} range=$wanted ${target.substringBefore('?').takeLast(40)}")
            val from = wanted?.let { Regex("""bytes=(\d+)-(\d*)""").find(it) }
            val request = Request.Builder().url(target).apply {
                if (head) head()
                headers.forEach { (k, v) -> header(k, v) }
                when {
                    // An open-ended request ("bytes=N-" or none) becomes chunks; see streamInChunks
                    !head && (wanted == null || (from != null && from.groupValues[2].isEmpty())) ->
                        header("Range", "bytes=${from?.groupValues?.get(1) ?: 0}-${(from?.groupValues?.get(1)?.toLong() ?: 0) + CHUNK - 1}")
                    wanted != null -> header("Range", wanted)
                }
            }.build()
            client.newCall(request).execute().use { response ->
                val type = response.header("Content-Type").orEmpty()
                val body = response.body
                val isPlaylist = type.contains("mpegurl", true) || response.request.url.encodedPath.endsWith(".m3u8")
                if (isPlaylist) {
                    val text = rewritePlaylist(body.string(), response.request.url.toString()) { proxied(id, it) }
                    val bytes = text.toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
                    // Our bounded Range makes the host answer 206; libVLC asked for the whole playlist
                    exchange.sendResponseHeaders(if (response.code == 206) 200 else response.code, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } else if (!head && response.code == 206) {
                    streamInChunks(exchange, target, headers, response)
                } else {
                    listOf("Content-Type", "Content-Range", "Accept-Ranges").forEach { name ->
                        response.header(name)?.let { exchange.responseHeaders.add(name, it) }
                    }
                    if (head) {
                        // A HEAD reply has no body, so the size has to be passed on by hand
                        response.header("Content-Length")?.let { exchange.responseHeaders.add("Content-Length", it) }
                        return exchange.sendResponseHeaders(response.code, -1)
                    }
                    exchange.sendResponseHeaders(response.code, body.contentLength().takeIf { it >= 0 } ?: 0)
                    exchange.responseBody.use { out -> body.byteStream().copyTo(out) }
                }
            }
        } catch (t: Throwable) {
            runCatching { exchange.sendResponseHeaders(502, -1) }
        } finally {
            exchange.close()
        }
    }
}
