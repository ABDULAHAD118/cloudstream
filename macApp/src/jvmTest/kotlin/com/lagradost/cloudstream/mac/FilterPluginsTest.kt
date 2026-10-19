package com.lagradost.cloudstream.mac

import com.lagradost.cloudstream3.TvType
import kotlin.test.Test
import kotlin.test.assertEquals

class FilterPluginsTest {
    private fun p(name: String, types: List<String>?, lang: String? = "en") =
        SitePlugin(url = "", name = name, internalName = name, tvTypes = types, language = lang)

    private val all = listOf(
        p("Movies", listOf("Movie")), p("Anime", listOf("Anime", "OVA"), "ja"),
        p("Untyped", null, null), p("Adult", listOf("NSFW")), p("Both", listOf("Movie", "TvSeries")),
    )

    private fun names(types: Set<TvType> = emptySet(), lang: String? = null, q: String = "") =
        filterPlugins(all, types, lang, q).map { it.name }

    @Test fun hidesNsfwAndSorts() = assertEquals(listOf("Anime", "Both", "Movies", "Untyped"), names())
    @Test fun typeMatchesAny() = assertEquals(listOf("Both", "Movies"), names(setOf(TvType.Movie)))
    @Test fun othersIncludesUntyped() = assertEquals(listOf("Untyped"), names(setOf(TvType.Others)))
    @Test fun languageNoneMeansMissing() = assertEquals(listOf("Untyped"), names(lang = "none"))
    @Test fun searchIsCaseInsensitive() = assertEquals(listOf("Anime"), names(q = "ANI"))
}
