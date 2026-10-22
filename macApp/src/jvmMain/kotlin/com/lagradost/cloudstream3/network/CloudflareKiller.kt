package com.lagradost.cloudstream3.network

import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Interceptor
import okhttp3.Response
import java.net.URI

/**
 * Desktop twin of the app module's CloudflareKiller, so extensions that add it as an interceptor (BollyFlix) load.
 * Android solves Cloudflare's challenge in a hidden WebView; there's none here, so requests just go through.
 * shortcut: a site that really serves the challenge still answers 403/503; solving it would need a browser engine.
 */
class CloudflareKiller : Interceptor {
    companion object {
        const val TAG = "CloudflareKiller"
        fun parseCookieMap(cookie: String): Map<String, String> = cookie.split(";").associate {
            val split = it.split("=")
            (split.getOrNull(0)?.trim() ?: "") to (split.getOrNull(1)?.trim() ?: "")
        }.filter { it.key.isNotBlank() && it.value.isNotBlank() }
    }

    val savedCookies: MutableMap<String, Map<String, String>> = mutableMapOf()

    /** The saved cookies for [url]'s host as a Cookie header, like Android's (which also sends the WebView's agent). */
    fun getCookieHeaders(url: String): Headers {
        val cookies = savedCookies[URI(url).host].orEmpty()
        return (if (cookies.isEmpty()) emptyMap() else mapOf("Cookie" to cookies.entries.joinToString(" ") { "${it.key}=${it.value};" })).toHeaders()
    }

    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}
