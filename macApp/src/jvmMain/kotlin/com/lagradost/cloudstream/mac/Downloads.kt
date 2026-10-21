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
import uk.co.caprica.vlcj.media.Media
import uk.co.caprica.vlcj.media.MediaEventAdapter
import uk.co.caprica.vlcj.media.MediaParsedStatus
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class DownloadStatus { Queued, Downloading, Paused, Done, Failed, Cancelled }

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
    /** Their names from the playlist ("Hindi"), in the same order. The merged file loses them, so the player shows these. */
    val hlsAudioNames: List<String> = emptyList(),
    /** English subtitle playlists declared in the HLS master, saved beside the video as .srt. */
    val hlsSubtitles: List<String> = emptyList(),
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
    /** The episode data for the extension's loadLinks, to get a fresh link when the saved one has expired. */
    val data: String? = null,
)

/**
 * Downloads to ~/Movies/CloudStream, like Android's VideoDownloadManager: the data is fetched with OkHttp in
 * parallel (byte ranges for files, segments for HLS, using the library's M3u8Helper2 as Android does), which
 * also stops YouTube from throttling a single long request. libVLC is only used locally, to merge a separate
 * audio track into one MKV (seconds), and for DASH, which it fetches itself.
 * Downloads pause and resume, and pick up where they stopped after the app restarts: files remember which byte
 * ranges are done and HLS how many segments, in small `.part` notes beside the data. Streams libVLC writes
 * (DASH, and HLS playlists the library can't read) start over.
 */
object Downloads {
    val folder get() = File(System.getProperty("cloudstream.downloadDir") ?: AppSettings.downloadFolder ?: "${System.getProperty("user.home")}/$videosFolderName/CloudStream")
    private val listFile = File(Extensions.dataDir, "downloads.json")
    private const val CHUNK = 8L * 1024 * 1024

    private data class Saved(val items: List<Download> = emptyList())

    val items = mutableStateListOf<Download>().apply {
        // Downloads that were running when the app quit carry on from where they stopped (see the init below)
        val saved = runCatching { parseJson(listFile.readText(), Saved::class).items }.getOrDefault(emptyList())
        addAll(saved.map { if (it.status == DownloadStatus.Downloading) it.copy(status = DownloadStatus.Queued) else it })
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

    fun enqueue(title: String, apiName: String, source: DownloadSource, subtitles: List<SubtitleFile> = emptyList(), data: String? = null): Download {
        folder.mkdirs()
        val base = title.replace(Regex("""[/\\:*?"<>|]"""), "_").trim().take(150).ifEmpty { "video" }
        val quality = if (source.quality > 0) " ${source.quality}p" else ""
        val ext = extensionFor(source)
        var file = File(folder, "$base$quality.$ext")
        var n = 2
        while (file.exists() || items.any { it.file == file.path }) file = File(folder, "$base$quality ($n).$ext").also { n++ }
        val download = Download(UUID.randomUUID().toString(), title, apiName, file.path, source, data = data)
        synchronized(this) { items.add(0, download); save() }
        AppScope.launch { saveSubtitles(download, subtitles) }
        startNext()
        return download
    }

    /** English subtitles go next to the video with the same name, so any player picks them up; others are left out. */
    private suspend fun saveSubtitles(download: Download, subtitles: List<SubtitleFile>) {
        val video = File(download.file)
        // One English file is enough; a stream's own English subtitles win over the extension's
        val fromExtension = if (download.source.hlsSubtitles.isEmpty()) listOfNotNull(bestEnglish(subtitles.filter { isEnglish(it.lang) }) { it.lang }) else emptyList()
        val saved = fromExtension.mapIndexedNotNull { i, sub ->
            runCatching {
                val ext = sub.url.substringBefore('?').substringAfterLast('.', "srt").take(4)
                val lang = sub.lang.replace(Regex("[^\\w-]"), "_")
                File(video.parentFile, "${video.nameWithoutExtension}.$lang${if (i > 0) ".$i" else ""}.$ext").apply {
                    writeText(app.get(sub.url, headers = sub.headers ?: emptyMap()).text)
                }.path
            }.getOrNull()
        } + download.source.hlsSubtitles.mapIndexedNotNull { i, url ->
            // Subtitles inside the HLS stream are WebVTT in pieces; join them into one .srt
            runCatching {
                val cues = HlsSubtitles.load(HlsSubtitleTrack("English", "en", url)) { u -> runBlocking { app.get(u, headers = download.source.headers).text } }
                check(cues.isNotEmpty()) { "no cues" }
                File(video.parentFile, "${video.nameWithoutExtension}.English${if (i > 0) ".$i" else ""}.srt").apply { writeText(toSrt(cues)) }.path
            }.getOrNull()
        }
        if (saved.isNotEmpty()) update(download.id) { it.copy(subtitles = saved) }
    }

    /**
     * A finished video that already carries English subtitles (MovieBox's MP4s have several) doesn't need the .srt
     * beside it: other players show the file's own track. The in-app player can't (libVLC doesn't draw subtitles
     * into the frames it hands the app), so the .srt moves into the app's data folder for it.
     */
    private suspend fun hideRedundantSubtitles(id: String) = withContext(Dispatchers.IO) {
        val download = items.firstOrNull { it.id == id && it.status == DownloadStatus.Done } ?: return@withContext
        val beside = download.subtitles.filter { File(it).parentFile == File(download.file).parentFile }
        if (beside.isEmpty()) return@withContext
        if (runCatching { embeddedSubtitleLanguages(download.file) }.getOrDefault(emptyList()).none(::isEnglish)) return@withContext
        // Same file name in a folder of its own: the player labels a subtitle by its name after the video's
        val dir = File(Extensions.dataDir, "subtitles/${download.id}").apply { mkdirs() }
        val moved = download.subtitles.associateWith { path ->
            if (path !in beside) path else File(dir, File(path).name).let { if (File(path).renameTo(it)) it.path else path }
        }
        update(id) { it.copy(subtitles = it.subtitles.map { p -> moved[p] ?: p }) }
    }

    /** The languages of the subtitle tracks inside a local video, read by libVLC without playing it. */
    suspend fun embeddedSubtitleLanguages(path: String): List<String> {
        if (VideoEngine.loadError != null || !File(path).exists()) return emptyList() // loadError finds libVLC first
        val media = vlc.media().newMedia(path) ?: return emptyList()
        try {
            val parsed = CompletableDeferred<Unit>()
            media.events().addMediaEventListener(object : MediaEventAdapter() {
                override fun mediaParsedChanged(media: Media, newStatus: MediaParsedStatus) { parsed.complete(Unit) }
            })
            if (!media.parsing().parse()) return emptyList()
            withTimeoutOrNull(30_000) { parsed.await() } ?: return emptyList()
            return media.info().textTracks().map { it.language() ?: it.description() ?: "" }
        } finally {
            media.release()
        }
    }

    /** Starts it again, from where it stopped when its data is still there. */
    fun retry(id: String) {
        update(id) { it.copy(status = DownloadStatus.Queued, error = null) }
        startNext()
    }

    /** Stops it, keeping what's downloaded for [resume]. */
    fun pause(id: String) {
        val job = synchronized(this) { jobs.remove(id) }
        update(id) { it.copy(status = DownloadStatus.Paused) }
        job?.cancel()
        startNext()
    }

    fun resume(id: String) = retry(id)

    fun cancel(id: String) {
        val job = synchronized(this) { jobs.remove(id) }
        update(id) { it.copy(status = DownloadStatus.Cancelled) }
        job?.cancel()
        items.firstOrNull { it.id == id }?.let { partFiles(it).forEach(File::delete) }
        startNext()
    }

    /** Drops finished downloads whose file was deleted outside the app (in Finder, say). */
    fun forgetMissing() {
        val gone = items.filter { it.status == DownloadStatus.Done && !File(it.file).exists() }
        if (gone.isNotEmpty()) synchronized(this) { items.removeAll(gone.toSet()); save() }
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

    /** Starts queued downloads after the limit is raised in Settings; lowering it lets running ones finish. */
    fun limitChanged() = startNext()

    @Synchronized private fun startNext() {
        while (jobs.size < AppSettings.parallelDownloads) {
            val next = items.lastOrNull { it.status == DownloadStatus.Queued } ?: return // oldest first
            update(next.id) { it.copy(status = DownloadStatus.Downloading, error = null) }
            jobs[next.id] = AppScope.launch {
                val error = try { run(next); null } catch (e: CancellationException) { throw e } catch (t: Throwable) {
                    // Stream links expire (resuming after a restart, say): get a fresh one for the same quality and carry on
                    val fresh = try { refreshedSource(next) } catch (e: CancellationException) { throw e } catch (_: Throwable) { null }
                    if (fresh == null) t.message ?: t.toString()
                    else try {
                        update(next.id) { it.copy(source = fresh) }
                        run(next.copy(source = fresh)); null
                    } catch (e: CancellationException) { throw e } catch (t2: Throwable) { t2.message ?: t2.toString() }
                }
                finish(next.id, error)
            }
        }
    }

    private fun finish(id: String, error: String?) {
        synchronized(this) { if (jobs.remove(id) == null) return } // cancelled meanwhile
        val download = items.firstOrNull { it.id == id } ?: return
        // A failed download keeps what it has, so Retry carries on from there
        val ok = error == null && File(download.file).length() > 0
        update(id) {
            if (ok) it.copy(status = DownloadStatus.Done, progress = 1f)
            else it.copy(status = DownloadStatus.Failed, error = error ?: "Nothing was downloaded")
        }
        // shortcut: an .srt still being fetched when the video finishes stays beside it until the next launch
        if (ok) AppScope.launch { hideRedundantSubtitles(id) }
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

    /** The same quality from a fresh set of the extension's links, or null when there's nothing new to try. */
    private suspend fun refreshedSource(d: Download): DownloadSource? {
        val data = d.data ?: return null
        val api = com.lagradost.cloudstream3.APIHolder.allProviders.firstOrNull { it.name == d.apiName } ?: return null
        val links = mutableListOf<ExtractorLink>()
        runCatching { api.loadLinks(data, false, {}, { links += it }) }
        val options = links.flatMap { runCatching { downloadOptions(it) }.getOrDefault(emptyList()) }.filter { it.type == d.source.type && it.url != d.source.url }
        return options.firstOrNull { it.label == d.source.label && it.quality == d.source.quality } ?: options.firstOrNull { it.quality == d.source.quality }
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
        val chunks = (0 until total step CHUNK).map { it to minOf(it + CHUNK, total) - 1 }
        // How far an earlier run (paused, or the app closed) got in each chunk: line 1 is the size, then
        // "chunk position" lines as it went, the furthest one counting. A different size starts over
        val notes = File("${dest.path}.chunks.part")
        val reached = runCatching { notes.readLines() }.getOrNull()
            ?.takeIf { it.firstOrNull()?.toLongOrNull() == total && dest.length() == total }
            ?.drop(1)?.mapNotNull { line -> line.split(' ').takeIf { it.size == 2 }?.let { it[0].toIntOrNull() to it[1].toLongOrNull() } }
            ?.filter { (i, p) -> i != null && p != null }?.groupBy({ it.first!! }, { it.second!! })?.mapValues { it.value.max() }
            ?: emptyMap<Int, Long>().also { notes.writeText("$total\n") }
        RandomAccessFile(dest, "rw").use { if (it.length() != total) it.setLength(total) }
        val gate = Semaphore(AppSettings.downloadConnections)
        var done = chunks.indices.sumOf { i -> (reached[i] ?: chunks[i].first) - chunks[i].first }
        coroutineScope {
            chunks.withIndex().filter { (i, range) -> (reached[i] ?: range.first) <= range.second }.map { (index, range) ->
                val (from, to) = range
                async {
                    gate.withPermit {
                        // Hosts drop connections now and then; resume this chunk where it stopped, a few times
                        var pos = reached[index] ?: from
                        var noted = pos
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
                                                synchronized(chunks) {
                                                    done += n; onProgress(done.toFloat() / total)
                                                    if (pos - noted >= 1024 * 1024) { notes.appendText("$index $pos\n"); noted = pos }
                                                }
                                            }
                                        }
                                    }
                                }
                                check(pos > to) { "The connection closed early" }
                                synchronized(chunks) { notes.appendText("$index $pos\n") }
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
        notes.delete()
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
        // Segments an earlier run already wrote, as "segments-in-playlist written bytes"; another playlist starts over
        val notes = File("${dest.path}.segments.part")
        val (startAt, keep) = runCatching { notes.readText().trim().split(' ').map(String::toLong) }.getOrNull()
            ?.takeIf { it.size == 3 && it[0] == hls.size.toLong() && part.length() >= it[2] }
            ?.let { it[1].toInt() to it[2] } ?: (0 to 0L)
        RandomAccessFile(part, "rw").use { it.setLength(keep) } // drops a segment that was cut off halfway
        java.io.FileOutputStream(part, true).use { out ->
            var written = startAt
            var bytes = keep
            onProgress(written.toFloat() / hls.size)
            coroutineScope {
                // Fetch ahead in windows so memory stays bounded while connections stay busy
                for (window in (startAt until hls.size).chunked(24)) {
                    val parts = window.map { i -> async { gate.withPermit { hls.resolveLinkSafe(i) } } }
                    for (segment in parts) {
                        val data = segment.await() ?: error("A segment failed to download")
                        out.write(data)
                        written++; bytes += data.size
                        notes.writeText("${hls.size} $written $bytes")
                        onProgress(written.toFloat() / hls.size)
                    }
                }
            }
        }
        check(part.renameTo(dest)) { "Couldn't move the finished file into place" }
        notes.delete()
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

    // Placed last, so everything above is set up before the downloads interrupted by quitting start again
    init { AppScope.launch { startNext(); items.toList().forEach { hideRedundantSubtitles(it.id) } } }
}

/**
 * The plain English one among several English subtitles (hosts often add "English [CC]", with sound descriptions,
 * and numbered alternates such as "English (1)"), else the first.
 */
fun <T> bestEnglish(tracks: List<T>, label: (T) -> String): T? = tracks.firstOrNull {
    val l = label(it)
    !l.contains("[CC]", true) && !l.contains("SDH", true) && !Regex("""\(\d+\)""").containsMatchIn(l)
} ?: tracks.firstOrNull()

/** Cues as a SubRip file, which every player reads. */
fun toSrt(cues: List<Cue>): String {
    fun time(ms: Long) = "%02d:%02d:%02d,%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
    return cues.withIndex().joinToString("\n") { (i, c) -> "${i + 1}\n${time(c.startMs)} --> ${time(c.endMs)}\n${c.text}\n" }
}

// "English", "eng", "en", "en-US", as track names or language tags; audio-description tracks don't count
private val englishLabel = Regex("""(?i)\benglish\b|\[(en|eng)([-_][a-z]+)?]|^(en|eng)([-_. (\[].*)?$""")

fun isEnglish(label: String) = englishLabel.containsMatchIn(label.trim()) && !label.contains("description", ignoreCase = true)

/**
 * The audio tracks a download keeps: Hindi and English, Hindi first, since players start with the first track.
 * When neither is there, all of them, so the file still has its original language.
 */
fun <T> keptLanguages(tracks: List<T>, label: (T) -> String): List<T> {
    val hindi = tracks.filter { hindiTrack(listOf(label(it))) != null }
    val english = tracks.filter { it !in hindi && isEnglish(label(it)) }
    return (hindi + english).ifEmpty { tracks }
}

/**
 * Every downloadable quality of [link]. A plain file is one option; an HLS playlist offers each of its variants,
 * read with the library's HlsPlaylistParser (trick-play and audio-less variants left out, as Android does).
 */
suspend fun downloadOptions(link: ExtractorLink): List<DownloadSource> {
    if (link.type != ExtractorLinkType.M3U8) return listOf(Downloads.sourceFor(link))
    val headers = Player.headersFor(link)
    val master = runCatching { app.get(link.url, headers = headers).text }.getOrNull() ?: return listOf(Downloads.sourceFor(link))
    val playlist = runCatching { com.lagradost.cloudstream3.utils.HlsPlaylistParser.parse(link.url, master) }.getOrNull()
        ?: return listOf(Downloads.sourceFor(link))
    val englishSubtitles = listOfNotNull(
        bestEnglish(HlsSubtitles.tracks(link.url, master).filter { isEnglish(it.name) || isEnglish("[${it.language}]") }) { it.name }?.playlistUrl
    )
    val variants = playlist.variants.filter { it.isPlayableStandalone(playlist) }.ifEmpty { playlist.variants.filter { !it.isTrickPlay() } }
    return variants.map { v ->
        // Variants whose sound is a separate audio group (one track per language) take all of that group along
        // Hindi goes first: players, this one and others, start with the file's first audio track
        val audio = keptLanguages(playlist.audios.filter { it.groupId == v.audioGroupId && it.url != null }.distinctBy { it.url.toString() }) { r ->
            listOfNotNull(r.name, r.format.language?.let { "[$it]" }).joinToString(" ")
        }
        Downloads.sourceFor(link, v.url.toString(), v.format.height.coerceAtLeast(0), link.name).copy(
            hlsAudio = audio.map { it.url.toString() },
            hlsAudioNames = audio.map { r -> r.name.ifBlank { r.format.language ?: "Audio" } },
            hlsSubtitles = englishSubtitles,
        )
    }
        .distinctBy { it.url }.sortedByDescending { it.quality }.ifEmpty { listOf(Downloads.sourceFor(link)) }
}
