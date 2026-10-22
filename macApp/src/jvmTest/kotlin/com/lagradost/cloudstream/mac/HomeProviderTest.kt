package com.lagradost.cloudstream.mac

import com.lagradost.cloudstream3.MainAPI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Home opens on Netflix on a fresh profile, but a provider the user picked stays picked. */
class HomeProviderTest {
    private fun api(name: String) = object : MainAPI() { override var name = name }

    @Test
    fun rememberedProviderWins() {
        val providers = listOf(api("Disney"), api("Netflix"), api("Prime Video"))
        assertEquals("Prime Video", homeProvider(providers, "Prime Video")?.name)
    }

    @Test
    fun netflixOnAFirstLaunch() {
        val providers = listOf(api("Disney"), api("Netflix"), api("Prime Video"))
        assertEquals("Netflix", homeProvider(providers, null)?.name)
    }

    @Test
    fun firstProviderWithoutNetflix() {
        assertEquals("Disney", homeProvider(listOf(api("Disney"), api("Prime Video")), null)?.name)
        assertNull(homeProvider(emptyList(), "Netflix"))
    }
}
