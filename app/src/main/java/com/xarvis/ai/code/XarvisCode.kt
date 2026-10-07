package com.xarvis.ai.code

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * XARVIS Code — the GitHub side of building SEPARATE projects (never XARVIS's own app).
 *
 * Stage 1: connect a GitHub token and create a new project repository. Later stages add writing the
 * project's code, a build workflow, watching the build and giving back an install link. All calls
 * are plain HTTPS to the GitHub REST API with the user's token; the token is stored encrypted in the
 * Android Keystore (see [com.xarvis.ai.llm.BrainKeys]) and only ever sent to github.com.
 */
object XarvisCode {

    data class Result(val ok: Boolean, val message: String, val data: String? = null)

    private const val API = "https://api.github.com"

    /** Checks the token works and returns the GitHub username in [Result.data]. */
    fun verify(token: String): Result = runCatching {
        val (code, text) = http("GET", "$API/user", token, null)
        if (code in 200..299) Result(true, "Connected.", JSONObject(text).optString("login"))
        else Result(false, errorMessage(code, text))
    }.getOrElse { Result(false, it.message ?: "Couldn't reach GitHub.") }

    /** Creates a new repository [name] (with a README), returning its web URL in [Result.data]. */
    fun createRepo(token: String, name: String, description: String, private: Boolean = true): Result = runCatching {
        val body = JSONObject()
            .put("name", name)
            .put("description", description)
            .put("private", private)
            .put("auto_init", true)
            .toString()
        val (code, text) = http("POST", "$API/user/repos", token, body)
        if (code in 200..299) Result(true, "Repository created.", JSONObject(text).optString("html_url"))
        else Result(false, errorMessage(code, text))
    }.getOrElse { Result(false, it.message ?: "Couldn't reach GitHub.") }

    /** Creates or updates file [path] in [owner]/[repo] with [content]. */
    fun putFile(token: String, owner: String, repo: String, path: String, content: String, message: String): Result = runCatching {
        val p = path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        // An update needs the existing file's sha; a first create doesn't.
        val existing = http("GET", "$API/repos/$owner/$repo/contents/$p", token, null)
        val sha = if (existing.first in 200..299) runCatching { JSONObject(existing.second).optString("sha") }.getOrNull() else null
        val body = JSONObject()
            .put("message", message)
            .put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        if (!sha.isNullOrBlank()) body.put("sha", sha)
        val (code, text) = http("PUT", "$API/repos/$owner/$repo/contents/$p", token, body.toString())
        if (code in 200..299) Result(true, "Pushed $path.") else Result(false, errorMessage(code, text))
    }.getOrElse { Result(false, it.message ?: "Couldn't reach GitHub.") }

    /** Turns on GitHub Pages for [owner]/[repo] (main branch, root). Returns the site URL in [Result.data]. */
    fun enablePages(token: String, owner: String, repo: String): Result = runCatching {
        val body = JSONObject().put("source", JSONObject().put("branch", "main").put("path", "/")).toString()
        val (code, text) = http("POST", "$API/repos/$owner/$repo/pages", token, body)
        val url = "https://$owner.github.io/$repo/"
        when {
            code in 200..299 -> Result(true, "Publishing.", url)
            code == 409 || code == 422 -> Result(true, "Already published.", url) // Pages was already on
            else -> Result(false, errorMessage(code, text))
        }
    }.getOrElse { Result(false, it.message ?: "Couldn't reach GitHub.") }

    /**
     * Deterministic checks on a generated game page, so XARVIS can fix real bugs itself (Stage 3):
     * a cut-off file, or a button that calls a function that was never defined (the "nothing happens
     * when I tap" bug). Returns a plain-language list of problems, empty if it looks sound.
     */
    fun issuesIn(html: String): List<String> {
        val out = ArrayList<String>()
        if (!html.contains("</html>", ignoreCase = true) && !html.contains("</script>", ignoreCase = true)) {
            out.add("the file is cut off — it has no closing </script>/</html>")
        }
        // Functions a button calls via onclick="name(" that are never defined anywhere in the file.
        val called = Regex("""onclick\s*=\s*["']\s*([A-Za-z_$][\w$]*)\s*\(""")
            .findAll(html).map { it.groupValues[1] }.toSet()
        for (fn in called) {
            val defined = html.contains("function $fn") ||
                Regex("""(?:const|let|var)\s+\Q$fn\E\s*=""").containsMatchIn(html) ||
                Regex("""\b\Q$fn\E\s*=\s*(?:function|\()""").containsMatchIn(html) ||
                Regex("""\b\Q$fn\E\s*:\s*function""").containsMatchIn(html)
            if (!defined) out.add("a button calls $fn() but that function is never defined")
        }
        return out
    }

    /** Pulls the HTML out of a brain's reply (strips ``` fences / leading prose), or null if it isn't a page. */
    fun extractHtml(raw: String): String? {
        var s = raw.trim()
        Regex("```(?:html)?\\s*([\\s\\S]*?)```").find(s)?.let { s = it.groupValues[1].trim() }
        val start = s.indexOf("<!doctype", ignoreCase = true).let { if (it >= 0) it else s.indexOf("<html", ignoreCase = true) }
        if (start > 0) s = s.substring(start)
        val looksLikePage = listOf("<html", "<canvas", "<body", "<script").any { s.contains(it, ignoreCase = true) }
        return if (looksLikePage && s.contains("<")) s else null
    }

    // ---- plumbing ----

    /** One request. Returns (HTTP status, response body or error body). */
    private fun http(method: String, urlStr: String, token: String, body: String?): Pair<Int, String> {
        val c = URL(urlStr).openConnection() as HttpURLConnection
        return try {
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "XARVIS-Code")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
            code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            c.disconnect()
        }
    }

    private fun errorMessage(code: Int, text: String): String = runCatching {
        val o = JSONObject(text)
        val base = o.optString("message").ifBlank { "GitHub error $code" }
        // GitHub's detail (e.g. "name already exists on this account") is in the errors array, not
        // the top-level message — include it so callers can tell "repo exists" from a real failure.
        val errs = o.optJSONArray("errors")
        val detail = if (errs != null && errs.length() > 0) errs.getJSONObject(0).optString("message") else ""
        if (detail.isNotBlank()) "$base ($detail)" else base
    }.getOrDefault("GitHub error $code")
}
