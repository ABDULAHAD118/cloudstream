package com.lagradost.cloudstream.mac

import com.lagradost.cloudstream3.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PagingTest {
    private val api = object : MainAPI() { override var name = "Test" }
    private fun items(vararg ids: Int) = runBlocking { ids.map { api.newMovieSearchResponse("m$it", "https://x/$it", TvType.Movie) } }
    private val trending = MainPageRequest("Trending", "t", false)
    private val latest = MainPageRequest("Latest", "l", false)

    @Test
    fun homePagesAppendToTheirOwnRowsAndAddNewOnes() {
        var rows = mergeHomePage(emptyList(), trending, 1, newHomePageResponse(HomePageList("Trending", items(1, 2)), hasNext = true))
        rows = mergeHomePage(rows, latest, 1, newHomePageResponse(HomePageList("Latest", items(9)), hasNext = false))
        rows = mergeHomePage(rows, trending, 2, newHomePageResponse(listOf(HomePageList("Trending", items(2, 3)), HomePageList("Extra", items(4))), hasNext = true))

        val byName = rows.associateBy { it.name }
        assertEquals(listOf("https://x/1", "https://x/2", "https://x/3"), byName.getValue("Trending").items.map { it.url }) // 2 not doubled
        assertEquals(3, byName.getValue("Trending").nextPage)
        assertTrue(byName.getValue("Extra").hasNext)
        assertFalse(byName.getValue("Latest").hasNext)
        assertEquals(1, byName.getValue("Latest").items.size) // other requests untouched
    }

    @Test
    fun anEmptyPageEndsTheRow() {
        val rows = mergeHomePage(emptyList(), trending, 1, newHomePageResponse(HomePageList("Trending", items(1)), hasNext = true))
        assertFalse(mergeHomePage(rows, trending, 2, null).single().hasNext)
    }

    @Test
    fun searchStopsWhenAPageBringsNothingNew() {
        val row = SearchRow(api, items(1, 2), nextPage = 2, hasNext = true)
        val more = mergeSearchPage(row, 2, items(3), hasNext = true)
        assertEquals(3, more.items.size); assertEquals(3, more.nextPage); assertTrue(more.hasNext)
        assertFalse(mergeSearchPage(more, 3, items(1, 3), hasNext = true).hasNext) // provider repeating itself
    }
}
