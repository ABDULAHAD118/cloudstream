package com.lagradost.cloudstream.mac

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.*

/** Home's provider: the one picked last time, else Netflix (what this fork opens on), else the first. */
fun homeProvider(providers: List<MainAPI>, remembered: String?): MainAPI? =
    providers.firstOrNull { it.name == remembered }
        ?: providers.firstOrNull { it.name.equals("Netflix", true) }
        ?: providers.firstOrNull()

/** Work that must outlive the screen that started it (leaving Home mid-load shouldn't cancel the load). */
val AppScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** A featured title on Home's carousel; [details] (backdrop, logo, plot) arrive after the row does. */
data class HeroItem(val search: SearchResponse, val details: LoadResponse? = null)

/**
 * Home's state lives here rather than in the screen, as in Android's HomeViewModel: opening a title and coming
 * back keeps the chosen provider and its rows. The provider is also remembered across launches.
 */
object HomeState {
    private val prefs get() = Preferences.named("cloudstream_mac")
    var selectedName by mutableStateOf(prefs.getString("home_provider", null))
        private set
    var rows by mutableStateOf(listOf<HomeRow>())
        private set
    var loadedFor by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var hero by mutableStateOf(listOf<HeroItem>())
        private set
    val loadingMore = mutableStateListOf<MainPageRequest>()
    private val lock = Any() // pages arrive on several threads; each merge reads and replaces rows
    private var job: Job? = null

    fun select(api: MainAPI) {
        selectedName = api.name
        prefs.edit().putString("home_provider", api.name).apply()
        load(api)
    }

    /** Loads [api]'s home page unless it's already shown (or loading). */
    fun show(api: MainAPI) {
        if (loadedFor != api.name) load(api)
    }

    fun load(api: MainAPI) {
        job?.cancel()
        rows = emptyList(); hero = emptyList(); error = null
        // Nothing to fetch without a connection; Home says so, and loads when the connection is back
        if (!Network.online) { loading = false; loadedFor = null; return }
        loading = true; loadedFor = api.name
        job = AppScope.launch {
            api.mainPage.map { page ->
                async {
                    val request = MainPageRequest(page.name, page.data, page.horizontalImages)
                    runCatching { api.getMainPage(1, request) }
                        .onSuccess { res ->
                            val picks = synchronized(lock) {
                                rows = mergeHomePage(rows, request, 1, res)
                                rows.firstOrNull()?.items?.take(6)?.takeIf { hero.isEmpty() }?.also { p -> hero = p.map { HeroItem(it) } }
                            }
                            picks?.let { startHero(api, it) }
                        }
                        .onFailure { if (it !is CancellationException) error = it.message ?: it.toString() }
                }
            }.awaitAll()
            loading = false
        }
    }

    /** Fills the carousel from the first row, then loads each title's page for its backdrop and logo, 3 at a time. */
    private fun CoroutineScope.startHero(api: MainAPI, picks: List<SearchResponse>) {
        val limit = Semaphore(3)
        picks.forEach { item ->
            launch {
                val details = limit.withPermit { runCatching { api.load(item.url) }.getOrNull() } ?: return@launch
                synchronized(lock) { hero = hero.map { if (it.search.url == item.url) it.copy(details = details) else it } }
            }
        }
    }

    fun loadMore(api: MainAPI, row: HomeRow) {
        if (!Network.online || row.request in loadingMore) return
        loadingMore += row.request
        AppScope.launch {
            runCatching { api.getMainPage(row.nextPage, row.request) }
                .onSuccess { res -> synchronized(lock) { rows = mergeHomePage(rows, row.request, row.nextPage, res) } }
                .onFailure { Toasts.show("Couldn't load more: ${it.message ?: it}") }
            loadingMore -= row.request
        }
    }
}

/** Search's query and results survive opening a title and coming back, as on Android. */
object SearchState {
    var query by mutableStateOf("")
    var searched by mutableStateOf("")
        private set
    var results by mutableStateOf(listOf<SearchRow>())
        private set
    var searching by mutableStateOf(false)
        private set
    val loadingMore = mutableStateListOf<MainAPI>()
    private val lock = Any() // providers answer on several threads; each update reads and replaces results
    private var job: Job? = null

    fun search(providers: List<MainAPI>) {
        val q = query.trim().takeIf { it.isNotEmpty() } ?: return
        if (!Network.online) { Toasts.show("You're offline — search needs an internet connection"); return }
        job?.cancel()
        searched = q; results = emptyList(); searching = true
        job = AppScope.launch {
            providers.map { api ->
                async {
                    // Slow or broken providers shouldn't hold up the rest
                    val found = withTimeoutOrNull(30_000) { runCatching { api.search(q, 1) }.getOrNull() }
                    if (found != null && found.items.isNotEmpty()) synchronized(lock) {
                        results = results + SearchRow(api, found.items.distinctBy { it.url }, 2, found.hasNext)
                    }
                }
            }.awaitAll()
            searching = false
        }
    }

    fun loadMore(row: SearchRow) {
        if (!Network.online || row.api in loadingMore) return
        loadingMore += row.api
        val q = searched
        AppScope.launch {
            runCatching { row.api.search(q, row.nextPage) }
                .onSuccess { page ->
                    synchronized(lock) {
                        val current = results.firstOrNull { it.api == row.api } ?: return@synchronized
                        val merged = mergeSearchPage(current, row.nextPage, page?.items.orEmpty(), page?.hasNext == true)
                        results = results.map { if (it.api == row.api) merged else it }
                    }
                }
                .onFailure { Toasts.show("Couldn't load more: ${it.message ?: it}") }
            loadingMore -= row.api
        }
    }
}

/** Titles to suggest while typing, from TheMovieDB like the Android app's SearchSuggestionApi (same key). */
object SearchSuggestions {
    private data class Page(@JsonProperty("results") val results: List<Item>? = null)
    private data class Item(
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
    )

    suspend fun get(query: String): List<String> {
        if (query.length < 2) return emptyList()
        val body = runCatching {
            app.get("https://api.themoviedb.org/3/search/multi", params = mapOf(
                "api_key" to "e6333b32409e02a4a6eba6fb7ff866bb", "query" to query, "language" to "en-US",
            ), cacheTime = 60 * 24).text
        }.getOrNull() ?: return emptyList() // offline: just no suggestions
        return parse(body)
    }

    fun parse(json: String): List<String> = runCatching { parseJson(json, Page::class).results.orEmpty() }.getOrDefault(emptyList())
        .filter { it.mediaType == "movie" || it.mediaType == "tv" }.mapNotNull { it.title ?: it.name }.distinct().take(10)
}
