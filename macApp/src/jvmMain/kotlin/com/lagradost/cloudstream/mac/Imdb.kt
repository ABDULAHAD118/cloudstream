package com.lagradost.cloudstream.mac

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.getImdbId
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.mapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** One IMDb user review. */
data class ImdbReview(
    val id: String,
    val author: String,
    /** The reviewer's own score out of 10, when they gave one. */
    val rating: Int?,
    val title: String?,
    val text: String,
    val date: String?,
    val upVotes: Int,
    val downVotes: Int,
    val spoiler: Boolean,
)

data class ImdbReviewPage(val reviews: List<ImdbReview>, val total: Int, val nextCursor: String?)

enum class ReviewSort(val label: String, val by: String, val order: String) {
    Helpful("Most helpful", "HELPFULNESS_SCORE", "DESC"),
    Newest("Newest", "SUBMISSION_DATE", "DESC"),
    Oldest("Oldest", "SUBMISSION_DATE", "ASC"),
    Highest("Highest rated", "USER_RATING", "DESC"),
    Lowest("Lowest rated", "USER_RATING", "ASC"),
    MostVotes("Most votes", "TOTAL_VOTES", "DESC"),
}

data class ImdbPerson(
    @JsonProperty("id") val id: String,
    @JsonProperty("name") val name: String,
    @JsonProperty("image") val image: String?,
)

/** What IMDb knows about a title, as shown on the details page. */
data class ImdbInfo(
    @JsonProperty("id") val id: String,
    @JsonProperty("title") val title: String,
    @JsonProperty("type") val type: String?,
    @JsonProperty("year") val year: Int?,
    @JsonProperty("endYear") val endYear: Int?,
    @JsonProperty("rating") val rating: Double?,
    @JsonProperty("votes") val votes: Long?,
    @JsonProperty("metascore") val metascore: Int?,
    @JsonProperty("popularity") val popularity: Int?,
    @JsonProperty("runtimeMinutes") val runtimeMinutes: Int?,
    @JsonProperty("certificate") val certificate: String?,
    @JsonProperty("genres") val genres: List<String> = emptyList(),
    @JsonProperty("plot") val plot: String?,
    @JsonProperty("directors") val directors: List<ImdbPerson> = emptyList(),
    @JsonProperty("writers") val writers: List<ImdbPerson> = emptyList(),
    @JsonProperty("creators") val creators: List<ImdbPerson> = emptyList(),
    @JsonProperty("stars") val stars: List<ImdbPerson> = emptyList(),
    @JsonProperty("countries") val countries: List<String> = emptyList(),
    @JsonProperty("languages") val languages: List<String> = emptyList(),
    @JsonProperty("released") val released: String?,
    @JsonProperty("awards") val awards: String?,
    @JsonProperty("budget") val budget: String?,
    @JsonProperty("grossWorldwide") val grossWorldwide: String?,
    @JsonProperty("seasons") val seasons: Int?,
    @JsonProperty("episodes") val episodes: Int?,
    /** Votes for each score, index 0 is 1 star up to index 9 for 10 stars. */
    @JsonProperty("ratingHistogram") val ratingHistogram: List<Long> = emptyList(),
    @JsonProperty("reviewCount") val reviewCount: Int? = null,
) {
    val url: String get() = "https://www.imdb.com/title/$id/"
}

private data class CachedImdb(
    @JsonProperty("info") val info: ImdbInfo?,
    @JsonProperty("savedAt") val savedAt: Long,
)

/**
 * IMDb details for a title. Uses the IMDb id when the extension provides one, otherwise finds
 * the title by name, year and type with IMDb's search suggestions.
 *
 * These are the unofficial endpoints imdb.com itself uses; IMDb allows only limited, private,
 * non-commercial use of this data. That fits a personal app. A published build would need a
 * licensed source (or OMDb with the user's own key) instead.
 */
object Imdb {
    private const val TTL_MS = 3L * 24 * 60 * 60 * 1000
    private const val BROWSER_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

    private val file = File(Extensions.dataDir, "imdb-cache.json")
    private val cache = ConcurrentHashMap<String, CachedImdb>(
        runCatching { mapper.readValue<Map<String, CachedImdb>>(file) }.getOrDefault(emptyMap())
    )

    /** Details for [response], or null when IMDb has no matching title. Cached for a few days. */
    suspend fun forTitle(response: LoadResponse): ImdbInfo? = withContext(Dispatchers.IO) {
        val key = "${response.apiName}|${response.url}"
        cache[key]?.takeIf { System.currentTimeMillis() - it.savedAt < TTL_MS }?.let { return@withContext it.info }
        val id = runCatching { response.getImdbId() }.getOrNull()?.takeIf { it.startsWith("tt") }
            ?: findId(response.name, response.year, response.type)
        val info = id?.let { fetch(it) }
        cache[key] = CachedImdb(info, System.currentTimeMillis())
        synchronized(file) { file.writeText(mapper.writeValueAsString(HashMap(cache))) }
        info
    }

    private fun normalize(title: String) = title.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    /** Picks the best IMDb suggestion: same title, close year, and the right kind (movie or series). */
    internal suspend fun findId(name: String, year: Int?, type: TvType): String? {
        val query = URLEncoder.encode(name.trim().lowercase(), "UTF-8").replace("+", "%20")
        val body = app.get("https://v3.sg.media-imdb.com/suggestion/x/$query.json", headers = mapOf("User-Agent" to BROWSER_UA)).text
        val results = mapper.readTree(body).path("d")
        val wantsSeries = type in setOf(TvType.TvSeries, TvType.Anime, TvType.AsianDrama, TvType.Cartoon, TvType.Documentary) &&
            type != TvType.Movie
        val target = normalize(name)
        return results.filter { it.path("id").asText().startsWith("tt") }
            .map { r ->
                val title = normalize(r.path("l").asText())
                val y = r.path("y").takeIf { it.isNumber }?.asInt()
                val kind = r.path("qid").asText()
                var score = 0
                if (title == target) score += 10 else if (title.contains(target) || target.contains(title)) score += 4 else score -= 20
                if (year != null && y != null) score += when (kotlin.math.abs(year - y)) { 0 -> 6; 1 -> 3; else -> -6 }
                val isSeries = kind.startsWith("tv") && kind != "tvMovie"
                if (wantsSeries == isSeries) score += 3
                r.path("id").asText() to score
            }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }

    private val query = """
        query(${'$'}id: ID!) { title(id: ${'$'}id) {
          id titleText { text } titleType { text } releaseYear { year endYear }
          ratingsSummary { aggregateRating voteCount } meterRanking { currentRank }
          runtime { seconds } certificate { rating } genres { genres { text } }
          plot { plotText { plainText } } metacritic { metascore { score } }
          principalCredits { category { text } credits(limit: 8) { name { id nameText { text } primaryImage { url } } } }
          countriesOfOrigin { countries { text } } spokenLanguages { spokenLanguages { text } }
          prestigiousAwardSummary { nominations wins award { text } }
          releaseDate { day month year country { text } }
          productionBudget { budget { amount currency } }
          lifetimeGross(boxOfficeArea: WORLDWIDE) { total { amount currency } }
          episodes { seasons { number } episodes(first: 0) { total } }
          aggregateRatingsBreakdown { histogram { histogramValues { rating voteCount } } }
          reviews(first: 0) { total }
        } }
    """.trimIndent()

    private fun graphql(query: String, variables: Map<String, Any?>): JsonNode? {
        val payload = mapper.writeValueAsString(mapOf("query" to query, "variables" to variables))
        val request = Request.Builder()
            .url("https://api.graphql.imdb.com/")
            .header("User-Agent", BROWSER_UA)
            .header("Origin", "https://www.imdb.com")
            .header("Referer", "https://www.imdb.com/")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        val text = app.baseClient.newCall(request).execute().use { if (it.isSuccessful) it.body.string() else return null }
        return mapper.readTree(text).path("data").path("title").takeIf { it.isObject }
    }

    internal fun fetch(id: String): ImdbInfo? = graphql(query, mapOf("id" to id))?.let(::parse)

    private val reviewsQuery = """
        query(${'$'}id: ID!, ${'$'}first: Int!, ${'$'}after: ID, ${'$'}by: ReviewsSortBy!, ${'$'}order: SortOrder!) { title(id: ${'$'}id) {
          reviews(first: ${'$'}first, after: ${'$'}after, sort: { by: ${'$'}by, order: ${'$'}order }) {
            total pageInfo { endCursor hasNextPage }
            edges { node {
              id author { nickName } authorRating summary { originalText }
              text { originalText { plainText } } submissionDate helpfulness { upVotes downVotes } spoiler
            } }
          }
        } }
    """.trimIndent()

    /** A page of user reviews. Pass the previous page's [ImdbReviewPage.nextCursor] to continue. */
    suspend fun reviews(id: String, sort: ReviewSort = ReviewSort.Helpful, after: String? = null, count: Int = 20): ImdbReviewPage? =
        withContext(Dispatchers.IO) {
            val r = graphql(
                reviewsQuery,
                mapOf("id" to id, "first" to count, "after" to after, "by" to sort.by, "order" to sort.order),
            )?.path("reviews") ?: return@withContext null
            val reviews = r.path("edges").mapNotNull { edge ->
                val n = edge.path("node")
                val text = n.textOrNull("text", "originalText", "plainText") ?: return@mapNotNull null
                ImdbReview(
                    id = n.path("id").asText(),
                    author = n.textOrNull("author", "nickName") ?: "IMDb user",
                    rating = n.path("authorRating").takeIf { it.isNumber }?.asInt(),
                    title = n.textOrNull("summary", "originalText"),
                    text = text,
                    date = n.textOrNull("submissionDate"),
                    upVotes = n.path("helpfulness").path("upVotes").asInt(0),
                    downVotes = n.path("helpfulness").path("downVotes").asInt(0),
                    spoiler = n.path("spoiler").asBoolean(false),
                )
            }
            val next = r.path("pageInfo").takeIf { it.path("hasNextPage").asBoolean(false) }?.textOrNull("endCursor")
            ImdbReviewPage(reviews, r.path("total").asInt(reviews.size), next)
        }

    private fun JsonNode.textOrNull(vararg path: String): String? =
        path.fold(this) { node, key -> node.path(key) }.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }

    private fun people(t: JsonNode, vararg categories: String): List<ImdbPerson> =
        t.path("principalCredits").filter { c -> c.path("category").path("text").asText() in categories }
            .flatMap { c -> c.path("credits") }
            .mapNotNull { c ->
                val n = c.path("name")
                val name = n.textOrNull("nameText", "text") ?: return@mapNotNull null
                ImdbPerson(n.path("id").asText(), name, n.textOrNull("primaryImage", "url"))
            }
            .distinctBy { it.id }

    private fun money(node: JsonNode): String? {
        val amount = node.path("amount").takeIf { it.isNumber }?.asLong() ?: return null
        val currency = node.path("currency").asText("USD")
        val short = when {
            amount >= 1_000_000_000 -> "%.1fB".format(amount / 1e9)
            amount >= 1_000_000 -> "%.1fM".format(amount / 1e6)
            else -> "%,d".format(amount)
        }.replace(".0", "")
        return if (currency == "USD") "$$short" else "$short $currency"
    }

    private fun parse(t: JsonNode): ImdbInfo {
        val date = t.path("releaseDate")
        val released = date.path("year").takeIf { it.isNumber }?.asInt()?.let { y ->
            val months = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
            val m = date.path("month").takeIf { it.isNumber }?.asInt()?.let { months.getOrNull(it - 1) }
            val d = date.path("day").takeIf { it.isNumber }?.asInt()
            listOfNotNull(listOfNotNull(m, d?.toString()).joinToString(" ").ifBlank { null }, y.toString()).joinToString(", ") +
                (date.textOrNull("country", "text")?.let { " ($it)" } ?: "")
        }
        val awards = t.path("prestigiousAwardSummary").takeIf { it.isObject }?.let { a ->
            val award = a.textOrNull("award", "text") ?: return@let null
            val wins = a.path("wins").asInt(0)
            val noms = a.path("nominations").asInt(0)
            when {
                wins > 0 -> "Won $wins $award${if (wins > 1) "s" else ""}"
                noms > 0 -> "Nominated for $noms $award${if (noms > 1) "s" else ""}"
                else -> null
            }
        }
        return ImdbInfo(
            id = t.path("id").asText(),
            title = t.textOrNull("titleText", "text") ?: "",
            type = t.textOrNull("titleType", "text"),
            year = t.path("releaseYear").path("year").takeIf { it.isNumber }?.asInt(),
            endYear = t.path("releaseYear").path("endYear").takeIf { it.isNumber }?.asInt(),
            rating = t.path("ratingsSummary").path("aggregateRating").takeIf { it.isNumber }?.asDouble(),
            votes = t.path("ratingsSummary").path("voteCount").takeIf { it.isNumber }?.asLong(),
            metascore = t.path("metacritic").path("metascore").path("score").takeIf { it.isNumber }?.asInt(),
            popularity = t.path("meterRanking").path("currentRank").takeIf { it.isNumber }?.asInt(),
            runtimeMinutes = t.path("runtime").path("seconds").takeIf { it.isNumber }?.asInt()?.div(60),
            certificate = t.textOrNull("certificate", "rating"),
            genres = t.path("genres").path("genres").mapNotNull { it.textOrNull("text") },
            plot = t.textOrNull("plot", "plotText", "plainText"),
            directors = people(t, "Director", "Directors"),
            writers = people(t, "Writer", "Writers"),
            creators = people(t, "Creator", "Creators"),
            stars = people(t, "Star", "Stars"),
            countries = t.path("countriesOfOrigin").path("countries").mapNotNull { it.textOrNull("text") },
            languages = t.path("spokenLanguages").path("spokenLanguages").mapNotNull { it.textOrNull("text") },
            released = released,
            awards = awards,
            budget = money(t.path("productionBudget").path("budget")),
            grossWorldwide = money(t.path("lifetimeGross").path("total")),
            seasons = t.path("episodes").path("seasons").size().takeIf { it > 0 },
            episodes = t.path("episodes").path("episodes").path("total").takeIf { it.isNumber }?.asInt()?.takeIf { it > 0 },
            ratingHistogram = t.path("aggregateRatingsBreakdown").path("histogram").path("histogramValues").let { values ->
                val byRating = values.associate { it.path("rating").asInt() to it.path("voteCount").asLong() }
                if (byRating.isEmpty()) emptyList() else (1..10).map { byRating[it] ?: 0L }
            },
            reviewCount = t.path("reviews").path("total").takeIf { it.isNumber }?.asInt(),
        )
    }
}

/** "10K", "3.2M" for vote counts. */
fun compactCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1e6).replace(".0", "")
    n >= 1_000 -> "%.1fK".format(n / 1e3).replace(".0", "")
    else -> n.toString()
}
