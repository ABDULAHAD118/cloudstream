package com.lagradost.cloudstream.mac

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchSuggestionsTest {
    @Test
    fun keepsMoviesAndShowsOnce() {
        val json = """{"results":[
            {"media_type":"movie","title":"Jailer"},
            {"media_type":"person","name":"Rajinikanth"},
            {"media_type":"tv","name":"Jail Break"},
            {"media_type":"movie","title":"Jailer"}
        ]}"""
        assertEquals(listOf("Jailer", "Jail Break"), SearchSuggestions.parse(json))
        assertEquals(emptyList(), SearchSuggestions.parse("not json"))
    }

    @Test
    fun liveFromTmdb() {
        if (System.getenv("CS_SMOKE") != "1") return
        val found = runBlocking { SearchSuggestions.get("stranger") }
        println("suggestions: $found")
        assertTrue(found.any { it.contains("Stranger Things") }, "got $found")
    }
}
