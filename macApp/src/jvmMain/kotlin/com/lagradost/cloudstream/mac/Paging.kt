package com.lagradost.cloudstream.mac

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse

/** A home-page row. Rows from the same [request] page together, as on Android. */
data class HomeRow(
    val request: MainPageRequest,
    val name: String,
    val items: List<SearchResponse>,
    val nextPage: Int,
    val hasNext: Boolean,
)

/** One provider's search results so far. */
data class SearchRow(val api: MainAPI, val items: List<SearchResponse>, val nextPage: Int, val hasNext: Boolean)

/**
 * Merges page [page] of [request] into [rows]: items join the row with the same name (duplicates dropped, since
 * providers often repeat the last items), new names become new rows, and every row of that request takes the
 * response's hasNext.
 */
fun mergeHomePage(rows: List<HomeRow>, request: MainPageRequest, page: Int, response: HomePageResponse?): List<HomeRow> {
    val lists = response?.items.orEmpty().filter { it.list.isNotEmpty() }
    val hasNext = response?.hasNext == true && lists.isNotEmpty()
    val merged = rows.map { row ->
        if (row.request != request) return@map row
        val more = lists.firstOrNull { it.name == row.name }?.list.orEmpty()
        row.copy(items = (row.items + more).distinctBy { it.url }, nextPage = page + 1, hasNext = hasNext)
    }
    val added = lists.filter { list -> rows.none { it.request == request && it.name == list.name } }
        .map { HomeRow(request, it.name, it.list, page + 1, hasNext) }
    return merged + added
}

/** Appends a further search page to [row]. */
fun mergeSearchPage(row: SearchRow, page: Int, items: List<SearchResponse>, hasNext: Boolean): SearchRow {
    val all = (row.items + items).distinctBy { it.url }
    // Some providers keep saying hasNext while repeating the same page; stop once nothing new arrives
    return row.copy(items = all, nextPage = page + 1, hasNext = hasNext && all.size > row.items.size)
}
