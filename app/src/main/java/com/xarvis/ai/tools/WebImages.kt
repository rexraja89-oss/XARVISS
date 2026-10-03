package com.xarvis.ai.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The "images" tool (Rex asked to see pictures, e.g. phones with a comparison): a live image search
 * so XARVIS can show real photos in the chat, not just text. Uses DuckDuckGo's image endpoint
 * (a vqd token scraped from its page, then its i.js JSON). No key, no library. Scraping can change,
 * so it fails soft: no images, and the text answer still stands.
 */
object WebImages {

    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"
    private const val MAX_BYTES = 4_000_000

    /** Up to [count] (full image, thumbnail) URL pairs for [query], best-effort. */
    suspend fun search(query: String, count: Int = 5): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        runCatching {
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            val page = get("https://duckduckgo.com/?q=$q&iax=images&ia=images", referer = null)
            val vqd = vqd(page) ?: return@runCatching emptyList()
            val json = get("https://duckduckgo.com/i.js?l=us-en&o=json&q=$q&vqd=$vqd&f=,,,,,&p=1", referer = "https://duckduckgo.com/")
            parse(json).take(count)
        }.getOrDefault(emptyList())
    }

    /** The image bytes at [url] (downscaled by the caller if needed), or null. */
    suspend fun download(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Referer", "https://duckduckgo.com/")
            }
            try {
                if (c.responseCode !in 200..299) return@runCatching null
                val bytes = c.inputStream.use { it.readBytes() }
                if (bytes.size > MAX_BYTES || bytes.isEmpty()) null else bytes
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    /** The (image, thumbnail) pairs in a DuckDuckGo i.js response. */
    fun parse(json: String): List<Pair<String, String>> {
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val o = results.optJSONObject(i) ?: return@mapNotNull null
            val image = o.optString("image").ifBlank { null }
            val thumb = o.optString("thumbnail").ifBlank { image } ?: return@mapNotNull null
            (image ?: thumb) to thumb
        }
    }

    /** The vqd token DuckDuckGo needs for its image API, scraped from the results page. */
    fun vqd(html: String): String? =
        Regex("""vqd=['"]([-0-9a-zA-Z]{6,})['"]""").find(html)?.groupValues?.get(1)
            ?: Regex("""vqd=([-0-9]{6,})""").find(html)?.groupValues?.get(1)

    private fun get(url: String, referer: String?): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", UA)
            referer?.let { setRequestProperty("Referer", it) }
        }
        return try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
