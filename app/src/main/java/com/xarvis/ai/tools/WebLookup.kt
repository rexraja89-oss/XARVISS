package com.xarvis.ai.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The "lookup" tool: reads Wikipedia for Gemma, so it can answer about people, places, films
 * or things it doesn't know (or isn't sure of) with real facts instead of guessing.
 */
object WebLookup {

    private const val SEARCH = "https://en.wikipedia.org/w/rest.php/v1/search/page?limit=3&q="
    private const val SUMMARY = "https://en.wikipedia.org/api/rest_v1/page/summary/"
    private const val MAX_CHARS = 1800

    /** Short Wikipedia summaries for [query], as text for Gemma; says so if nothing was found. */
    suspend fun lookup(query: String): String = withContext(Dispatchers.IO) {
        try {
            val titles = searchTitles(get(SEARCH + URLEncoder.encode(query, "UTF-8")))
            if (titles.isEmpty()) return@withContext "Wikipedia has no article matching \"$query\"."
            val summaries = titles.take(2).mapNotNull { title ->
                runCatching { summaryText(get(SUMMARY + URLEncoder.encode(title.replace(' ', '_'), "UTF-8"))) }.getOrNull()
            }
            summaries.joinToString("\n\n").take(MAX_CHARS)
                .ifBlank { "Wikipedia has articles titled ${titles.joinToString()}, but no summary could be read." }
        } catch (e: Exception) {
            "The lookup couldn't reach Wikipedia (${e.message ?: "no internet"})."
        }
    }

    /** Article titles from a Wikipedia search response. */
    fun searchTitles(json: String): List<String> {
        val pages = JSONObject(json).optJSONArray("pages") ?: return emptyList()
        return (0 until pages.length()).mapNotNull { pages.optJSONObject(it)?.optString("title")?.ifBlank { null } }
    }

    /** "Title: summary" from a Wikipedia page-summary response. */
    fun summaryText(json: String): String? {
        val o = JSONObject(json)
        val extract = o.optString("extract").trim().ifEmpty { return null }
        return "${o.optString("title")}: $extract"
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 10_000
        // Wikipedia asks API users to say who they are.
        c.setRequestProperty("User-Agent", "XARVIS/1.0 (personal phone assistant; github.com/rexraja89-oss/XARVISS)")
        return try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
