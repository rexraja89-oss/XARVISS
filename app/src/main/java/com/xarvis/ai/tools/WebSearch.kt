package com.xarvis.ai.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The "websearch" tool (Rex asked: "search the web and show me the result here in the app"):
 * does a live web search and gives the brain the top results (title, snippet, link) so it can
 * answer in the chat with current information — instead of just opening Google on the phone.
 *
 * Uses DuckDuckGo's lite page, which is plain static HTML (no key, no JavaScript). No library added.
 */
object WebSearch {

    private const val LITE = "https://lite.duckduckgo.com/lite/?q="
    private const val HTML = "https://html.duckduckgo.com/html/?q="
    private const val MAX_RESULTS = 6
    private const val MAX_CHARS = 4000
    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"

    /** Top web results for [query] as text for the brain, or a plain note if nothing came back. */
    suspend fun search(query: String): String = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val html = runCatching { get(LITE + q) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching { get(HTML + q) }.getOrNull()
            ?: return@withContext "The web search couldn't be reached just now."
        val results = parse(html)
        if (results.isEmpty()) return@withContext "No clear web results came back for \"$query\"."
        results.take(MAX_RESULTS).mapIndexed { i, r ->
            "${i + 1}. ${r.title}\n   ${r.snippet}\n   ${r.url}"
        }.joinToString("\n\n").take(MAX_CHARS)
    }

    data class Result(val title: String, val snippet: String, val url: String)

    /** Pulls results out of DuckDuckGo's lite/html page (title, snippet, real link). */
    fun parse(html: String): List<Result> {
        // Match the whole <a …> result tag (attributes in any order), then read href + text from it.
        val lite = anchors(html, "result-link")
        if (lite.isNotEmpty()) {
            val snippets = Regex("""result-snippet['"]?[^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(html).map { clean(it.groupValues[1]) }.toList()
            return lite.mapIndexed { i, r -> r.copy(snippet = snippets.getOrElse(i) { "" }) }.filter { it.title.isNotBlank() }
        }
        // The non-lite page uses result__a / result__snippet classes.
        val full = anchors(html, "result__a")
        val snips = Regex("""result__snippet[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).map { clean(it.groupValues[1]) }.toList()
        return full.mapIndexed { i, r -> r.copy(snippet = snips.getOrElse(i) { "" }) }.filter { it.title.isNotBlank() }
    }

    /** Result anchors whose class contains [cssClass], with href read wherever it sits in the tag. */
    private fun anchors(html: String, cssClass: String): List<Result> =
        Regex("""<a\b([^>]*\b$cssClass\b[^>]*)>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).mapNotNull { m ->
                val attrs = m.groupValues[1]
                val href = Regex("""\bhref=['"]([^'"]+)['"]""").find(attrs)?.groupValues?.get(1) ?: return@mapNotNull null
                Result(clean(m.groupValues[2]), "", realUrl(href))
            }.toList()

    /** DuckDuckGo wraps links as /l/?uddg=<encoded>; give back the real destination. */
    private fun realUrl(href: String): String {
        val raw = Regex("""[?&]uddg=([^&]+)""").find(href)?.groupValues?.get(1)
            ?: return if (href.startsWith("//")) "https:$href" else href
        return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(href)
    }

    /** Strips tags and decodes the common entities from a snippet/title fragment. */
    private fun clean(s: String): String = s
        .replace(Regex("""<[^>]+>"""), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
        .replace(Regex("""\s+"""), " ").trim()

    private fun get(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "text/html")
        }
        return try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
