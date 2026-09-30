package com.xarvis.ai.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * The "webread" tool (Rex asked): fetches a web page and pulls out its readable text, so the brain
 * (cloud or Gemma) can answer about it or summarise it with live information. Dependency-free — a
 * small HTML-to-text reader, no library added (the phones' installers judge the whole package).
 * Only plain GET over http/https; JavaScript-only pages return little, which the reply makes clear.
 */
object WebRead {

    private const val MAX_CHARS = 12_000
    private const val MAX_HOPS = 4
    // A normal browser UA: many sites serve a bare/blocked page to unknown agents.
    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"

    /** The page at [rawUrl] as readable text for the brain, or a plain note if it couldn't be read. */
    suspend fun read(rawUrl: String): String = withContext(Dispatchers.IO) {
        val url = normalize(rawUrl) ?: return@withContext "That doesn't look like a web address: \"$rawUrl\"."
        try {
            val (finalUrl, html) = fetch(url)
            val text = extractText(html)
            if (text.isBlank()) {
                "The page at $finalUrl has no readable text (it may need JavaScript or a sign-in)."
            } else {
                "Web page: $finalUrl\n\n" + text.take(MAX_CHARS)
            }
        } catch (e: Exception) {
            "Couldn't read $url (${e.message ?: "no connection"})."
        }
    }

    /** Follows a few redirects and returns the final URL and its HTML. */
    private fun fetch(start: String): Pair<String, String> {
        var current = start
        var hops = 0
        while (true) {
            val c = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 15_000
                instanceFollowRedirects = false // handle hops ourselves so http↔https also follows
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
            }
            try {
                val code = c.responseCode
                if (code in 300..399 && hops < MAX_HOPS) {
                    val loc = c.getHeaderField("Location") ?: throw java.io.IOException("redirect with no target")
                    current = URL(URL(current), loc).toString()
                    hops++
                    continue
                }
                if (code !in 200..299) throw java.io.IOException("HTTP $code")
                val body = c.inputStream.bufferedReader().use { it.readText() }
                return current to body
            } finally {
                c.disconnect()
            }
        }
    }

    /** Adds https:// when missing and rejects anything that isn't a real web address. */
    fun normalize(raw: String): String? {
        var u = raw.trim().trim('"', '\'', '<', '>', ' ', '.', ')', '(')
        if (u.isEmpty()) return null
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "https://$u"
        // Must have a host with a dot (e.g. example.com), no spaces.
        val host = Regex("""^https?://([^/\s]+)""", RegexOption.IGNORE_CASE).find(u)?.groupValues?.get(1) ?: return null
        if (!host.contains('.') || host.contains(' ')) return null
        return u
    }

    /** Turns HTML into plain readable text: drops scripts/styles/tags, decodes entities, keeps paragraphs. */
    fun extractText(html: String): String {
        var s = html
        // The page title first, if any, so the brain knows what it is.
        val title = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(s)?.groupValues?.get(1)?.let { decodeEntities(it).trim() }.orEmpty()
        // Remove the parts that aren't page text.
        s = s.replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), " ")
        s = s.replace(Regex("""<(script|style|noscript|head|nav|footer|svg)\b[^>]*>.*?</\1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
        // Block-level tags become line breaks so paragraphs survive.
        s = s.replace(Regex("""(?i)<br\s*/?>"""), "\n")
        s = s.replace(Regex("""(?i)</(p|div|li|tr|h[1-6]|section|article|ul|ol|table|blockquote)>"""), "\n")
        // Drop every remaining tag.
        s = s.replace(Regex("""<[^>]+>"""), " ")
        s = decodeEntities(s)
        // Tidy whitespace: no runs of spaces, no more than one blank line.
        s = s.replace(Regex("""[ \t]+"""), " ")
            .replace(Regex(""" *\n *"""), "\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
        return if (title.isNotEmpty() && !s.startsWith(title)) "$title\n\n$s" else s
    }

    private fun decodeEntities(text: String): String {
        var s = text
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
            .replace("&mdash;", "—").replace("&ndash;", "–").replace("&hellip;", "…")
            .replace("&rsquo;", "'").replace("&lsquo;", "'").replace("&ldquo;", "\"").replace("&rdquo;", "\"")
        // Numeric entities: &#123; and &#x1F;
        s = Regex("""&#(\d{1,7});""").replace(s) { runCatching { it.groupValues[1].toInt().toChar().toString() }.getOrDefault(" ") }
        s = Regex("""&#x([0-9a-fA-F]{1,6});""").replace(s) { runCatching { it.groupValues[1].toInt(16).toChar().toString() }.getOrDefault(" ") }
        return s
    }
}
