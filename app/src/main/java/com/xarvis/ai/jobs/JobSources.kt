package com.xarvis.ai.jobs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Where the hunter looks: Tavily (web search that also returns each page's text, so the application email
 * and requirements are read from the ad itself), Jooble (job listings), and the parsers for both and for
 * LinkedIn's job-alert emails. Network calls throw on failure; the hunter catches and carries on.
 */
object JobSources {

    data class Hit(val title: String, val url: String, val snippet: String, val text: String)

    // ---- Tavily ----

    /** One basic Tavily search (1 credit), with each result's page text. [domains]: only these sites. */
    suspend fun tavily(key: String, query: String, max: Int = 8, domains: List<String> = emptyList(), timeRange: String? = "month"): List<Hit> =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("query", query)
                .put("search_depth", "basic")
                .put("max_results", max)
                .put("include_raw_content", true)
                .put("topic", "general")
            if (timeRange != null) body.put("time_range", timeRange)
            if (domains.isNotEmpty()) body.put("include_domains", JSONArray(domains))
            parseTavily(post("https://api.tavily.com/search", body.toString(), mapOf("Authorization" to "Bearer $key")))
        }

    fun parseTavily(json: String): List<Hit> {
        val arr = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Hit(
                title = o.optString("title").trim(),
                url = o.optString("url"),
                snippet = o.optString("content"),
                text = if (o.isNull("raw_content")) "" else o.optString("raw_content").take(MAX_TEXT),
            )
        }
    }

    // ---- Jooble ----

    data class JoobleJob(val title: String, val company: String, val location: String, val salary: String, val snippet: String, val link: String, val source: String)

    /** One Jooble request (Rex's key allows a limited number). */
    suspend fun jooble(key: String, keywords: String, location: String): List<JoobleJob> = withContext(Dispatchers.IO) {
        val body = JSONObject().put("keywords", keywords).put("location", location).put("page", "1")
        parseJooble(post("https://jooble.org/api/" + URLEncoder.encode(key, "UTF-8"), body.toString(), emptyMap()))
    }

    fun parseJooble(json: String): List<JoobleJob> {
        val arr = JSONObject(json).optJSONArray("jobs") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            JoobleJob(
                title = clean(o.optString("title")),
                company = clean(o.optString("company")),
                location = clean(o.optString("location")),
                salary = clean(o.optString("salary")),
                snippet = clean(o.optString("snippet")),
                link = o.optString("link"),
                source = clean(o.optString("source")),
            )
        }
    }

    // ---- LinkedIn job-alert emails (Loop 1) ----

    private val LI_JOB = Regex("""https?://(?:[a-z]+\.)?linkedin\.com/(?:comm/)?jobs/view/(\d+)""")

    /**
     * The jobs in a LinkedIn job-alert email (its text, already decoded): for each "jobs/view/<id>" link, the
     * lines just before it are the title, company and location.
     */
    fun parseLinkedInAlert(text: String): List<RawJob> {
        val lines = text.lines().map { it.trim() }
        val out = LinkedHashMap<String, RawJob>()
        for ((i, line) in lines.withIndex()) {
            val m = LI_JOB.find(line) ?: continue
            val id = m.groupValues[1]
            if (id in out) continue
            val before = ArrayList<String>()
            var j = i - 1
            while (j >= 0 && before.size < 4) {
                val l = lines[j].removePrefix("View job:").trim()
                if (l.isNotEmpty() && !l.startsWith("http") && !LI_JOB.containsMatchIn(l) && l.length < 120 && !l.startsWith("-")) before.add(0, l)
                if (l.isEmpty() && before.size >= 2) break
                j--
            }
            // Title, company, location (some alerts add "· Easy Apply", "Actively recruiting"…).
            val useful = before.filterNot { Regex("""(?i)easy apply|actively recruiting|promoted|alumni|applicants|be an early|\d+ (day|hour|week)s? ago|new$""").containsMatchIn(it) }
            if (useful.isEmpty()) continue
            val title = useful.getOrNull(useful.size - 3) ?: useful.first()
            val company = useful.getOrNull(useful.size - 2).takeIf { useful.size >= 2 && it != title }.orEmpty()
            val location = useful.last().takeIf { useful.size >= 3 }.orEmpty()
            out[id] = RawJob(title, company, location, url = "https://www.linkedin.com/jobs/view/$id/", source = "LinkedIn alert")
        }
        return out.values.toList()
    }

    // ---- does an email's domain receive mail? (DNS over HTTPS, no key) ----

    /** True: the domain has a mail server. False: it doesn't exist. Null: couldn't tell. */
    suspend fun mailDomainWorks(domain: String): Boolean? = withContext(Dispatchers.IO) {
        runCatching {
            val j = JSONObject(get("https://dns.google/resolve?name=" + URLEncoder.encode(domain, "UTF-8") + "&type=MX"))
            when (j.optInt("Status", -1)) {
                3 -> false
                0 -> {
                    val answers = j.optJSONArray("Answer")
                    val hasMx = answers != null && (0 until answers.length()).any { answers.getJSONObject(it).optInt("type") == 15 }
                    if (hasMx) true else {
                        val a = JSONObject(get("https://dns.google/resolve?name=" + URLEncoder.encode(domain, "UTF-8") + "&type=A"))
                        a.optJSONArray("Answer")?.length()?.let { it > 0 } ?: false
                    }
                }
                else -> null
            }
        }.getOrNull()
    }

    // ---- reading an ad page (free: plain HTTP) ----

    /** The text of a job page (Jooble's links lead to the original ad), or "" when it can't be read. */
    suspend fun pageText(url: String): String = runCatching {
        com.xarvis.ai.tools.WebRead.read(url).takeUnless { it.startsWith("Couldn't") || it.contains("has no readable text") }.orEmpty().take(MAX_TEXT)
    }.getOrDefault("")

    // ---- plumbing ----

    private const val MAX_TEXT = 12_000

    fun clean(s: String): String = s.replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace("&amp;", "&")
        .replace(Regex("&#?\\w+;"), " ").replace(Regex("\\s+"), " ").trim()

    private fun post(url: String, json: String, headers: Map<String, String>): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15_000
        c.readTimeout = 40_000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "application/json")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw java.io.IOException("HTTP $code: ${text.take(160)}")
        return text
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.setRequestProperty("Accept", "application/dns-json")
        return c.inputStream.bufferedReader().use { it.readText() }
    }
}
