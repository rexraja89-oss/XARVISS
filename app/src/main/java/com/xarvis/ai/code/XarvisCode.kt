package com.xarvis.ai.code

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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

    /** Creates a new private repository [name] (with a README), returning its web URL in [Result.data]. */
    fun createRepo(token: String, name: String, description: String): Result = runCatching {
        val body = JSONObject()
            .put("name", name)
            .put("description", description)
            .put("private", true)
            .put("auto_init", true)
            .toString()
        val (code, text) = http("POST", "$API/user/repos", token, body)
        if (code in 200..299) Result(true, "Repository created.", JSONObject(text).optString("html_url"))
        else Result(false, errorMessage(code, text))
    }.getOrElse { Result(false, it.message ?: "Couldn't reach GitHub.") }

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

    private fun errorMessage(code: Int, text: String): String =
        runCatching { JSONObject(text).optString("message").ifBlank { "GitHub error $code" } }
            .getOrDefault("GitHub error $code")
}
