package android.net

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** The read side of android.net.Uri that extensions use to pick URLs apart. */
open class Uri private constructor(private val raw: String) {
    // java.net.URI rejects some characters Android tolerates, so retry with them escaped
    private val uri: URI? = runCatching { URI(raw) }.getOrNull()
        ?: runCatching { URI(raw.replace(" ", "%20").replace("|", "%7C")) }.getOrNull()

    override fun toString() = raw
    override fun equals(other: Any?) = other is Uri && other.raw == raw
    override fun hashCode() = raw.hashCode()

    open fun getScheme(): String? = uri?.scheme
    open fun getHost(): String? = uri?.host
    open fun getPort(): Int = uri?.port ?: -1
    open fun getAuthority(): String? = uri?.authority
    open fun getPath(): String? = uri?.path
    open fun getEncodedPath(): String? = uri?.rawPath
    open fun getQuery(): String? = uri?.query
    open fun getEncodedQuery(): String? = uri?.rawQuery
    open fun getFragment(): String? = uri?.fragment
    open fun getPathSegments(): List<String> = getPath().orEmpty().split('/').filter { it.isNotEmpty() }
    open fun getLastPathSegment(): String? = getPathSegments().lastOrNull()
    open fun isAbsolute(): Boolean = getScheme() != null

    private fun queryPairs(): List<Pair<String, String>> = uri?.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map {
        URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
    }

    open fun getQueryParameter(key: String?): String? = queryPairs().firstOrNull { it.first == key }?.second
    open fun getQueryParameters(key: String?): List<String> = queryPairs().filter { it.first == key }.map { it.second }
    open fun getQueryParameterNames(): Set<String> = queryPairs().map { it.first }.toCollection(LinkedHashSet())

    companion object {
        @JvmField val EMPTY = Uri("")
        @JvmStatic fun parse(uriString: String): Uri = Uri(uriString)
        @JvmStatic fun encode(s: String?): String? = s?.let { URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }
        @JvmStatic fun decode(s: String?): String? = s?.let { URLDecoder.decode(it.replace("+", "%2B"), Charsets.UTF_8) }
    }
}
