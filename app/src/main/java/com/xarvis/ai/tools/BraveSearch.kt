package com.xarvis.ai.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Brave Search API (Rex added a free key): the primary, reliable source for live web results and
 * product images — unlike scraping DuckDuckGo, it's a real keyed API. Free tier, 2,000/month.
 * The key is entered in ☰ → BRAIN → Web search and stored encrypted; only sent to Brave.
 * On any error (quota, no key) the caller falls back to Wikipedia / DuckDuckGo.
 */
object BraveSearch {

    private const val WEB = "https://api.search.brave.com/res/v1/web/search?count=8&q="
    private const val IMG = "https://api.search.brave.com/res/v1/images/search?count=6&q="
    private const val MAX_CHARS = 4000

    /** Top web results for [query] as text for the brain, or empty on any problem (then fall back). */
    suspend fun web(query: String, key: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val json = get(WEB + URLEncoder.encode(query.trim(), "UTF-8"), key)
            val results = parseWeb(json)
            if (results.isEmpty()) "" else results.mapIndexed { i, r ->
                "${i + 1}. ${r.title}\n   ${r.snippet}\n   ${r.url}"
            }.joinToString("\n\n").take(MAX_CHARS)
        }.getOrDefault("")
    }

    /** Top image URLs for [query], or empty on any problem (then fall back to Wikipedia/DuckDuckGo). */
    suspend fun images(query: String, key: String): List<String> = withContext(Dispatchers.IO) {
        runCatching { parseImages(get(IMG + URLEncoder.encode(query.trim(), "UTF-8"), key)) }.getOrDefault(emptyList())
    }

    data class Result(val title: String, val snippet: String, val url: String)

    fun parseWeb(json: String): List<Result> {
        val results = JSONObject(json).optJSONObject("web")?.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val o = results.optJSONObject(i) ?: return@mapNotNull null
            val title = o.optString("title").ifBlank { return@mapNotNull null }
            Result(strip(title), strip(o.optString("description")), o.optString("url"))
        }
    }

    fun parseImages(json: String): List<String> {
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val o = results.optJSONObject(i) ?: return@mapNotNull null
            o.optJSONObject("properties")?.optString("url")?.ifBlank { null }
                ?: o.optJSONObject("thumbnail")?.optString("src")?.ifBlank { null }
        }
    }

    private fun strip(s: String): String = s.replace(Regex("""<[^>]+>"""), "").replace(Regex("""\s+"""), " ").trim()

    private fun get(url: String, key: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Subscription-Token", key)
        }
        return try {
            if (c.responseCode !in 200..299) throw java.io.IOException("Brave HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
