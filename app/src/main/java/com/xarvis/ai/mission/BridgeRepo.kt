package com.xarvis.ai.mission

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The few GitHub calls the mission bridge needs, on Rex's private bridge repo, with his GitHub key (the one XARVIS
 * Code uses). Nothing here talks to anyone but api.github.com.
 */
class BridgeRepo(private val token: String, val owner: String, val repo: String) {

    class Http(val code: Int, val text: String, val etag: String?)

    /** File names in [dir]; null when unchanged since [etag] (GitHub answers 304, which doesn't count against its limit). */
    fun list(dir: String, etag: String? = null): Pair<List<String>, String?>? {
        val r = http("GET", "$API/repos/$owner/$repo/contents/${path(dir)}", null, etag)
        if (r.code == 304) return null
        if (r.code == 404) return emptyList<String>() to r.etag
        check(r.code)
        val arr = JSONArray(r.text)
        return (0 until arr.length()).map { arr.getJSONObject(it).optString("name") } to r.etag
    }

    fun exists(path: String): Boolean = http("GET", "$API/repos/$owner/$repo/contents/${path(path)}", null, null).code == 200

    fun text(path: String): String {
        val r = http("GET", "$API/repos/$owner/$repo/contents/${path(path)}", null, null)
        check(r.code)
        val b64 = JSONObject(r.text).optString("content").replace("\n", "")
        return String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
    }

    /** Creates or replaces [path] with [bytes]. */
    fun put(path: String, bytes: ByteArray, message: String) {
        val url = "$API/repos/$owner/$repo/contents/${path(path)}"
        val existing = http("GET", url, null, null)
        val sha = if (existing.code == 200) runCatching { JSONObject(existing.text).optString("sha") }.getOrNull() else null
        val body = JSONObject().put("message", message).put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
        if (!sha.isNullOrBlank()) body.put("sha", sha)
        check(http("PUT", url, body.toString(), null).code)
    }

    /** Whether the repo can be reached with this key, and is private (a public one would show the reports to anyone). */
    fun checkRepo(): String? {
        val r = http("GET", "$API/repos/$owner/$repo", null, null)
        if (r.code == 401) return "GitHub refused the key (it may have expired). Put a new one in ☰ → XARVIS Code."
        if (r.code == 404) return "There's no repo $owner/$repo that this GitHub key can see."
        if (r.code !in 200..299) return "GitHub said ${r.code}."
        if (!JSONObject(r.text).optBoolean("private")) return "$owner/$repo is public. Make it private first: the reports show your screens."
        return null
    }

    private fun check(code: Int) {
        if (code !in 200..299) throw IllegalStateException(
            when (code) {
                401 -> "GitHub refused the key (it may have expired). Put a new one in ☰ → XARVIS Code."
                403 -> "GitHub refused (403): the key may lack the repo permission, or the hourly limit was reached"
                404 -> "not found on GitHub (404)"
                else -> "GitHub error $code"
            },
        )
    }

    private fun path(p: String) = p.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun http(method: String, url: String, body: String?, etag: String?): Http {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.useCaches = false
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "XARVIS-Missions")
            if (etag != null) c.setRequestProperty("If-None-Match", etag)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            Http(code, stream?.bufferedReader()?.use { it.readText() } ?: "", c.getHeaderField("ETag"))
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private const val API = "https://api.github.com"
        const val DEFAULT_NAME = "xarvis-bridge"

    }
}
