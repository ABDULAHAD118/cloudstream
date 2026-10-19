package com.lagradost.cloudstream.mac

import com.lagradost.cloudstream3.app
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

/** NewPipeExtractor's HTTP client (YouTube and similar extensions), ported from the Android app's DownloaderTestImpl. */
object NewPipeDownloader : Downloader() {
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
    private val client by lazy { app.baseClient.newBuilder().readTimeout(30, TimeUnit.SECONDS).build() }

    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.let { it.toRequestBody(null, 0, it.size) }
        val builder = okhttp3.Request.Builder().method(request.httpMethod(), body).url(request.url())
            .addHeader("User-Agent", USER_AGENT)
        request.headers().forEach { (name, values) ->
            if (values.size > 1) {
                builder.removeHeader(name)
                values.forEach { builder.addHeader(name, it) }
            } else if (values.size == 1) {
                builder.header(name, values[0])
            }
        }
        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            return Response(response.code, response.message, response.headers.toMultimap(), response.body.string(), response.request.url.toString())
        }
    }
}
