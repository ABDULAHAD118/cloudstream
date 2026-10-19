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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Loopback HTTP proxy that adds the extension's headers (cookies, origin, tokens) to every request, which
 * libVLC can't do itself. HLS playlists are rewritten so their segments and keys go through the proxy too.
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

    /** Points every URI in an HLS playlist at the proxy, resolved against the playlist's own URL. */
    fun rewritePlaylist(body: String, baseUrl: String, wrapUrl: (String) -> String): String {
        val base = baseUrl.toHttpUrl()
        fun absolute(uri: String) = base.resolve(uri)?.toString() ?: uri
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
            val request = Request.Builder().url(target).apply {
                headers.forEach { (k, v) -> header(k, v) }
                exchange.requestHeaders.getFirst("Range")?.let { header("Range", it) }
            }.build()
            app.baseClient.newCall(request).execute().use { response ->
                val type = response.header("Content-Type").orEmpty()
                val body = response.body
                val isPlaylist = type.contains("mpegurl", true) || response.request.url.encodedPath.endsWith(".m3u8")
                if (isPlaylist) {
                    val text = rewritePlaylist(body.string(), response.request.url.toString()) { proxied(id, it) }
                    val bytes = text.toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
                    exchange.sendResponseHeaders(response.code, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } else {
                    listOf("Content-Type", "Content-Range", "Accept-Ranges").forEach { name ->
                        response.header(name)?.let { exchange.responseHeaders.add(name, it) }
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
