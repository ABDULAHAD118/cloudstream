package com.lagradost.cloudstream.mac

import androidx.compose.runtime.mutableStateListOf
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.M3u8Helper2
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class DownloadStatus { Queued, Downloading, Done, Failed, Cancelled }

/** What's needed to (re)start a download: the stream, its headers and the separate audio track if any. */
data class DownloadSource(
    val url: String,
    val type: ExtractorLinkType,
    val headers: Map<String, String>,
    val audioUrl: String? = null,
    val quality: Int = 0,
    val label: String = "",
    /** HLS audio tracks kept separate from the video (one per language); all of them go into the file. */
    val hlsAudio: List<String> = emptyList(),
)

data class Download(
    val id: String,
    val title: String,
    val apiName: String,
    val file: String,
    val source: DownloadSource,
    val status: DownloadStatus = DownloadStatus.Queued,
    val progress: Float = 0f,
    val error: String? = null,
    val subtitles: List<String> = emptyList(),
)

/**
 * Downloads to ~/Movies/CloudStream, like Android's VideoDownloadManager: the data is fetched with OkHttp in
 * parallel (byte ranges for files, segments for HLS, using the library's M3u8Helper2 as Android does), which
 * also stops YouTube from throttling a single long request. libVLC is only used locally, to merge a separate
 * audio track into one MKV (seconds), and for DASH, which it fetches itself.
 * shortcut: no pause/resume; a cancelled or failed download restarts from the beginning with Retry.
 */
object Downloads {
    val folder get() = File(System.getProperty("cloudstream.downloadDir") ?: "${System.getProperty("user.home")}/Movies/CloudStream")
    private val listFile = File(Extensions.dataDir, "downloads.json")
    private const val PARALLEL_DOWNLOADS = 2
    private const val CONNECTIONS = 4 // per download
    private const val CHUNK = 8L * 1024 * 1024

    private data class Saved(val items: List<Download> = emptyList())

    val items = mutableStateListOf<Download>().apply {
        // A download that was running when the app quit can't continue, so mark it for Retry
        val saved = runCatching { parseJson(listFile.readText(), Saved::class).items }.getOrDefault(emptyList())
        addAll(saved.map {
            if (it.status == DownloadStatus.Downloading || it.status == DownloadStatus.Queued)
                it.copy(status = DownloadStatus.Failed, error = "Interrupted when the app closed") else it
        })
    }

    private val client by lazy { app.baseClient.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).readTimeout(60, TimeUnit.SECONDS).build() }
    private val vlc by lazy { MediaPlayerFactory("--quiet", "--vout=dummy", "--aout=dummy") }
    private val jobs = mutableMapOf<String, Job>()

    @Synchronized private fun save() = listFile.writeText(Saved(items.toList()).toJson())

    @Synchronized private fun update(id: String, persist: Boolean = true, change: (Download) -> Download) {
        val i = items.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        items[i] = change(items[i])
        if (persist) save()
    }

    /** Builds the download source for [link], mirroring what the player sends for it. */
    fun sourceFor(link: ExtractorLink, url: String = link.url, quality: Int = link.quality, label: String = link.name) = DownloadSource(
        url, link.type, Player.headersFor(link),
        link.audioTracks.getOrNull(defaultAudio(link.audioTracks.map { it.url }))?.url, quality, label,
    )

    /** The file type a source is saved as. */
    fun extensionFor(source: DownloadSource): String = when {
        source.type == ExtractorLinkType.M3U8 && source.hlsAudio.isNotEmpty() -> "mkv" // video + separate audio tracks, merged
        source.type == ExtractorLinkType.M3U8 -> "ts" // HLS segments are MPEG-TS, joined end to end
        source.audioUrl != null || source.type == ExtractorLinkType.DASH -> "mkv" // merged tracks; MKV holds any codec
        else -> source.url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
            .lowercase().takeIf { it in setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "ts") } ?: "mp4"
    }

    fun enqueue(title: String, apiName: String, source: DownloadSource, subtitles: List<SubtitleFile> = emptyList()): Download {
        folder.mkdirs()
        val base = title.replace(Regex("""[/\\:*?"<>|]"""), "_").trim().take(150).ifEmpty { "video" }
        val quality = if (source.quality > 0) " ${source.quality}p" else ""
        val ext = extensionFor(source)
        var file = File(folder, "$base$quality.$ext")
        var n = 2
        while (file.exists() || items.any { it.file == file.path }) file = File(folder, "$base$quality ($n).$ext").also { n++ }
        val download = Download(UUID.randomUUID().toString(), title, apiName, file.path, source)
        synchronized(this) { items.add(0, download); save() }
        AppScope.launch { saveSubtitles(download, subtitles) }
        startNext()
        return download
    }

    /** Subtitles go next to the video with the same name, so any player picks them up. */
    private suspend fun saveSubtitles(download: Download, subtitles: List<SubtitleFile>) {
        val video = File(download.file)
        val saved = subtitles.mapIndexedNotNull { i, sub ->
            runCatching {
                val ext = sub.url.substringBefore('?').substringAfterLast('.', "srt").take(4)
                val lang = sub.lang.replace(Regex("[^\\w-]"), "_")
                File(video.parentFile, "${video.nameWithoutExtension}.$lang${if (i > 0) ".$i" else ""}.$ext").apply {
                    writeText(app.get(sub.url, headers = sub.headers ?: emptyMap()).text)
                }.path
            }.getOrNull()
        }
        if (saved.isNotEmpty()) update(download.id) { it.copy(subtitles = saved) }
    }

    fun retry(id: String) {
        update(id) { it.copy(status = DownloadStatus.Queued, progress = 0f, error = null) }
        startNext()
    }

    fun cancel(id: String) {
        val job = synchronized(this) { jobs.remove(id) }
        update(id) { it.copy(status = DownloadStatus.Cancelled) }
        job?.cancel()
        items.firstOrNull { it.id == id }?.let { partFiles(it).forEach(File::delete) }
        startNext()
    }

    /** Removes the entry, and its files unless [keepFile]. */
    fun delete(id: String, keepFile: Boolean = false) {
        val download = items.firstOrNull { it.id == id } ?: return
        if (download.status == DownloadStatus.Downloading) cancel(id)
        if (!keepFile) { File(download.file).delete(); download.subtitles.forEach { File(it).delete() } }
        synchronized(this) { items.removeAll { it.id == id }; save() }
    }

    private fun partFiles(d: Download) = listOf(File(d.file)) +
        (File(d.file).parentFile?.listFiles { f -> f.name.startsWith(File(d.file).name + ".") && f.name.endsWith(".part") }.orEmpty())

    @Synchronized private fun startNext() {
        while (jobs.size < PARALLEL_DOWNLOADS) {
            val next = items.lastOrNull { it.status == DownloadStatus.Queued } ?: return // oldest first
            update(next.id) { it.copy(status = DownloadStatus.Downloading, progress = 0f, error = null) }
            jobs[next.id] = AppScope.launch {
                val error = try { run(next); null } catch (e: CancellationException) { throw e } catch (t: Throwable) { t.message ?: t.toString() }
                finish(next.id, error)
            }
        }
    }

    private fun finish(id: String, error: String?) {
        synchronized(this) { if (jobs.remove(id) == null) return } // cancelled meanwhile
        val download = items.firstOrNull { it.id == id } ?: return
        val ok = error == null && File(download.file).length() > 0
        if (!ok) partFiles(download).forEach(File::delete)
        update(id) {
            if (ok) it.copy(status = DownloadStatus.Done, progress = 1f)
            else it.copy(status = DownloadStatus.Failed, error = error ?: "Nothing was downloaded")
        }
        startNext()
    }

    private var lastSaved = 0L
    private fun progress(id: String, value: Float) {
        val now = System.currentTimeMillis()
        // Progress ticks often; only write the list to disk now and then
        val persist = now - lastSaved > 5000
        if (persist) lastSaved = now
        update(id, persist) { it.copy(progress = value.coerceIn(0f, 0.999f)) }
    }

    private suspend fun run(d: Download) {
        val s = d.source
        val target = File(d.file)
        when {
            s.type == ExtractorLinkType.M3U8 && s.hlsAudio.isNotEmpty() -> coroutineScope {
                // The video segments carry no sound: fetch every language track beside it, then merge them all
                val video = File("${d.file}.video.part")
                val audios = s.hlsAudio.indices.map { File("${d.file}.audio$it.part") }
                val done = FloatArray(1 + audios.size)
                fun report() = progress(d.id, 0.95f * (0.8f * done[0] + 0.2f * done.drop(1).average().toFloat()))
                val jobs = listOf(async { fetchHls(d, s.url, video) { done[0] = it; report() } }) +
                    s.hlsAudio.mapIndexed { i, url -> async { fetchHls(d, url, audios[i]) { done[i + 1] = it; report() } } }
                jobs.awaitAll()
                // libVLC only attaches an extra input that reports its position, which raw MPEG-TS doesn't:
                // give each track MKV timestamps first (local, seconds), then merge them into one file
                val videoMkv = File("${d.file}.video.mkv.part")
                val audioMkas = audios.indices.map { File("${d.file}.audio$it.mka.part") }
                withLibVlc(d, video.path, null, videoMkv)
                audios.forEachIndexed { i, f -> withLibVlc(d, f.path, null, audioMkas[i]) }
                withLibVlc(d, videoMkv.path, audioMkas.joinToString("#") { it.path }, target)
                (audios + video + audioMkas + videoMkv).forEach(File::delete)
            }
            s.type == ExtractorLinkType.M3U8 -> fetchHls(d, s.url, target) { progress(d.id, it) }
            s.type == ExtractorLinkType.DASH -> withLibVlc(d, s.url, s.audioUrl, target)
            s.audioUrl != null -> coroutineScope {
                // Video and audio download side by side, then merge locally into one file
                val video = File("${d.file}.video.part")
                val audio = File("${d.file}.audio.part")
                var videoDone = 0f
                var audioDone = 0f
                val v = async { fetchFile(s.url, s.headers, video) { videoDone = it; progress(d.id, 0.95f * (0.8f * videoDone + 0.2f * audioDone)) } }
                val a = async { fetchFile(s.audioUrl, s.headers, audio) { audioDone = it; progress(d.id, 0.95f * (0.8f * videoDone + 0.2f * audioDone)) } }
                v.await(); a.await()
                withLibVlc(d, video.path, audio.path, target)
                video.delete(); audio.delete()
            }
            else -> fetchFile(s.url, s.headers, target) { progress(d.id, it) }
        }
    }

    /** Fetches [url] into [dest], in parallel byte ranges when the server allows them. */
    suspend fun fetchFile(url: String, headers: Map<String, String>, dest: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        fun request(range: String?) = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
            range?.let { header("Range", it) }
        }.build()

        // A 1-byte range tells us both the size and whether ranges work (retried: connections do get reset)
        val total = retrying {
            client.newCall(request("bytes=0-0")).execute().use { r ->
                if (r.code == 206) r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() else null
            }
        }
        dest.parentFile?.mkdirs()
        // Without range support there's no resuming, so it's one request (retried from the start if it drops)
        if (total == null) {
            retrying {
                client.newCall(request(null)).execute().use { r ->
                    check(r.isSuccessful) { "HTTP ${r.code}" }
                    val length = r.body.contentLength()
                    var done = 0L
                    dest.outputStream().use { out ->
                        r.body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                ensureActive()
                                val n = input.read(buffer).takeIf { it >= 0 } ?: break
                                out.write(buffer, 0, n); done += n
                                if (length > 0) onProgress(done.toFloat() / length)
                            }
                        }
                    }
                }
            }
            return@withContext
        }
        RandomAccessFile(dest, "rw").use { it.setLength(total) }
        val chunks = (0 until total step CHUNK).map { it to minOf(it + CHUNK, total) - 1 }
        val gate = Semaphore(CONNECTIONS)
        var done = 0L
        coroutineScope {
            chunks.map { (from, to) ->
                async {
                    gate.withPermit {
                        // Hosts drop connections now and then; resume this chunk where it stopped, a few times
                        var pos = from
                        var attempt = 0
                        while (pos <= to) {
                            try {
                                client.newCall(request("bytes=$pos-$to")).execute().use { r ->
                                    check(r.code == 206) { "HTTP ${r.code} for a byte range" }
                                    RandomAccessFile(dest, "rw").use { file ->
                                        file.seek(pos)
                                        r.body.byteStream().use { input ->
                                            val buffer = ByteArray(64 * 1024)
                                            while (true) {
                                                ensureActive()
                                                val n = input.read(buffer).takeIf { it >= 0 } ?: break
                                                file.write(buffer, 0, n); pos += n
                                                synchronized(chunks) { done += n; onProgress(done.toFloat() / total) }
                                            }
                                        }
                                    }
                                }
                                check(pos > to) { "The connection closed early" }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                if (++attempt > 4) throw e
                                delay(1000L * attempt)
                            }
                        }
                    }
                }
            }.awaitAll()
        }
    }

    /** Runs [block] again (up to 4 more times, with a growing pause) when the connection fails. */
    private suspend fun <T> retrying(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try { return block() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (++attempt > 4) throw e
                delay(1000L * attempt)
            }
        }
    }

    /** HLS: the library resolves the variant and decrypts segments; we fetch them in parallel and write them in order. */
    private suspend fun fetchHls(d: Download, url: String, dest: File, onProgress: (Float) -> Unit): Unit = withContext(Dispatchers.IO) {
        val s = d.source
        val hls = M3u8Helper2.hslLazy(M3u8Helper.M3u8Stream(url, s.quality.takeIf { it > 0 }, s.headers), selectBest = true, requireAudio = url == s.url && s.hlsAudio.isEmpty())
        // The library's parser misses segments in unusual playlists (e.g. a tag between #EXTINF and the URI). Never
        // save a silently truncated file: if segments are missing, let libVLC read the whole playlist instead.
        val expected = runCatching { hlsDurationMs(url) { u -> runBlocking { app.get(u, headers = s.headers).text } } }.getOrNull()
        val found = (hls.allTsLinks.sumOf { it.time ?: 0.0 } * 1000).toLong()
        if (hls.size == 0 || (expected != null && found < expected * 0.97)) {
            return@withContext withLibVlc(d, url, null, dest, mux = "ts")
        }
        val gate = Semaphore(6)
        val part = File("${dest.path}.part") // per output: video and audio tracks download side by side
        part.outputStream().use { out ->
            var written = 0
            coroutineScope {
                // Fetch ahead in windows so memory stays bounded while connections stay busy
                for (window in (0 until hls.size).chunked(24)) {
                    val parts = window.map { i -> async { gate.withPermit { hls.resolveLinkSafe(i) } } }
                    for (bytes in parts) {
                        out.write(bytes.await() ?: error("A segment failed to download"))
                        written++
                        onProgress(written.toFloat() / hls.size)
                    }
                }
            }
        }
        check(part.renameTo(dest)) { "Couldn't move the finished file into place" }
    }

    /** Runs libVLC's stream output from [input] (+ [audio]) into [dest]; local inputs finish in seconds. */
    private suspend fun withLibVlc(d: Download, input: String, audio: String?, dest: File, mux: String = "mkv") {
        val player = vlc.mediaPlayers().newMediaPlayer()
        val done = CompletableDeferred<String?>()
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun finished(mediaPlayer: MediaPlayer) { done.complete(null) }
            override fun error(mediaPlayer: MediaPlayer) { done.complete("libVLC couldn't read the stream") }
            override fun positionChanged(mediaPlayer: MediaPlayer, newPosition: Float) {
                if (input.startsWith("http")) progress(d.id, newPosition) // remote: DASH or the HLS fallback
            }
        })
        val s = d.source
        val remote = input.startsWith("http")
        val options = listOfNotNull(
            ":sout=#std{access=file,mux=$mux,dst='${dest.path.replace("'", "\\'")}'}", ":sout-all", ":no-sout-display",
            audio?.let { ":input-slave=$it" },
            if (remote) s.headers.entries.firstOrNull { it.key.equals("Referer", true) }?.let { ":http-referrer=${it.value}" } else null,
            if (remote) ":http-user-agent=${s.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: USER_AGENT}" else null,
            if (remote && s.quality > 0) ":adaptive-maxheight=${s.quality}" else null,
        )
        // Like playback: libVLC's own TLS breaks inside the JVM, so remote non-DASH input comes through the proxy
        val source = if (remote && s.type != ExtractorLinkType.DASH) HeaderProxy.wrap(input, s.headers) else input
        try {
            check(player.media().play(source, *options.toTypedArray())) { "libVLC couldn't open the stream" }
            done.await()?.let { error(it) }
        } finally {
            withContext(NonCancellable) { player.controls().stop(); player.release() }
        }
    }
}

/**
 * Every downloadable quality of [link]. A plain file is one option; an HLS playlist offers each of its variants,
 * read with the library's HlsPlaylistParser (trick-play and audio-less variants left out, as Android does).
 */
suspend fun downloadOptions(link: ExtractorLink): List<DownloadSource> {
    if (link.type != ExtractorLinkType.M3U8) return listOf(Downloads.sourceFor(link))
    val headers = Player.headersFor(link)
    val playlist = runCatching {
        com.lagradost.cloudstream3.utils.HlsPlaylistParser.parse(link.url, app.get(link.url, headers = headers).text)
    }.getOrNull() ?: return listOf(Downloads.sourceFor(link))
    val variants = playlist.variants.filter { it.isPlayableStandalone(playlist) }.ifEmpty { playlist.variants.filter { !it.isTrickPlay() } }
    return variants.map { v ->
        // Variants whose sound is a separate audio group (one track per language) take all of that group along
        val audio = playlist.audios.filter { it.groupId == v.audioGroupId }.mapNotNull { it.url?.toString() }.distinct()
        Downloads.sourceFor(link, v.url.toString(), v.format.height.coerceAtLeast(0), link.name).copy(hlsAudio = audio)
    }
        .distinctBy { it.url }.sortedByDescending { it.quality }.ifEmpty { listOf(Downloads.sourceFor(link)) }
}
