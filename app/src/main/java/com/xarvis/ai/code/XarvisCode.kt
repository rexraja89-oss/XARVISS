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

    /** Creates or updates file [path] in [owner]/[repo] with [content], on [branch] when given. */
    fun putFile(token: String, owner: String, repo: String, path: String, content: String, message: String, branch: String? = null): Result = runCatching {
        val p = path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val refQ = if (branch != null) "?ref=$branch" else ""
        // An update needs the existing file's sha (on that branch); a first create doesn't.
        val existing = http("GET", "$API/repos/$owner/$repo/contents/$p$refQ", token, null)
        val sha = if (existing.first in 200..299) runCatching { JSONObject(existing.second).optString("sha") }.getOrNull() else null
        val body = JSONObject()
            .put("message", message)
            .put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        if (!sha.isNullOrBlank()) body.put("sha", sha)
        if (branch != null) body.put("branch", branch)
        val (code, text) = http("PUT", "$API/repos/$owner/$repo/contents/$p", token, body.toString())
        if (code in 200..299) Result(true, "Pushed $path.") else Result(false, errorMessage(code, text))
    }.getOrElse { Result(false, it.message ?: "Couldn't reach GitHub.") }

    /** Creates [newBranch] from the tip of [fromBranch]. Succeeds (no-op) if it already exists. */
    fun createBranch(token: String, owner: String, repo: String, fromBranch: String, newBranch: String): Result = runCatching {
        val ref = http("GET", "$API/repos/$owner/$repo/git/ref/heads/$fromBranch", token, null)
        if (ref.first !in 200..299) return@runCatching Result(false, errorMessage(ref.first, ref.second))
        val sha = JSONObject(ref.second).optJSONObject("object")?.optString("sha").orEmpty()
        if (sha.isBlank()) return@runCatching Result(false, "couldn't read the base branch")
        val body = JSONObject().put("ref", "refs/heads/$newBranch").put("sha", sha).toString()
        val (code, text) = http("POST", "$API/repos/$owner/$repo/git/refs", token, body)
        when {
            code in 200..299 -> Result(true, "branch created", newBranch)
            code == 422 -> Result(true, "branch exists", newBranch) // already there
            else -> Result(false, errorMessage(code, text))
        }
    }.getOrElse { Result(false, it.message ?: "Couldn't reach GitHub.") }

    /** The newest workflow run on [branch] as "status|conclusion|id|headSha" in [Result.data]. */
    fun latestRunOnBranch(token: String, owner: String, repo: String, branch: String): Result = runCatching {
        val (code, text) = http("GET", "$API/repos/$owner/$repo/actions/runs?branch=$branch&per_page=1", token, null)
        if (code !in 200..299) return@runCatching Result(false, errorMessage(code, text))
        val runs = JSONObject(text).optJSONArray("workflow_runs")
        if (runs == null || runs.length() == 0) return@runCatching Result(false, "no runs")
        val r = runs.getJSONObject(0)
        Result(true, "ok", "${r.optString("status")}|${r.optString("conclusion")}|${r.optLong("id")}|${r.optString("head_sha")}")
    }.getOrElse { Result(false, it.message ?: "Couldn't reach GitHub.") }

    /** Failure messages (compiler/test errors) for commit [sha], from its check-run annotations. */
    fun buildErrors(token: String, owner: String, repo: String, sha: String): String = runCatching {
        val runs = http("GET", "$API/repos/$owner/$repo/commits/$sha/check-runs", token, null)
        if (runs.first !in 200..299) return@runCatching ""
        val arr = JSONObject(runs.second).optJSONArray("check_runs") ?: return@runCatching ""
        val out = StringBuilder()
        for (i in 0 until arr.length()) {
            val cr = arr.getJSONObject(i)
            if (cr.optString("conclusion") != "failure") continue
            val id = cr.optLong("id")
            val ann = http("GET", "$API/repos/$owner/$repo/check-runs/$id/annotations", token, null)
            if (ann.first in 200..299) {
                val a = org.json.JSONArray(ann.second)
                for (j in 0 until a.length()) {
                    val o = a.getJSONObject(j)
                    out.append(o.optString("path")).append(":").append(o.optInt("start_line"))
                        .append(" ").append(o.optString("message")).append("\n")
                }
            }
        }
        out.toString().trim()
    }.getOrDefault("")

    /** Deletes branch [branch] (to discard an agent's work). */
    fun deleteBranch(token: String, owner: String, repo: String, branch: String): Result = runCatching {
        val (code, text) = http("DELETE", "$API/repos/$owner/$repo/git/refs/heads/$branch", token, null)
        if (code in 200..299) Result(true, "deleted") else Result(false, errorMessage(code, text))
    }.getOrElse { Result(false, it.message ?: "Couldn't reach GitHub.") }

    /** The XARVIS app's own repository — edits here need Rex's explicit ask + approval (never on its own). */
    const val SELF_REPO = "XARVISS"

    /** True if [repo] is XARVIS's own app repo (case-insensitive), which is approval-gated. */
    fun isSelfRepo(repo: String): Boolean = repo.equals(SELF_REPO, ignoreCase = true) || repo.equals("xarviss", ignoreCase = true)

    data class RepoInfo(val name: String, val owner: String, val branch: String, val private: Boolean)

    /** Lists the user's repositories (most recently updated first). Raw JSON array in [Result.data]. */
    fun listRepos(token: String): Result = runCatching {
        val (code, text) = http("GET", "$API/user/repos?per_page=100&sort=updated&affiliation=owner", token, null)
        if (code in 200..299) Result(true, "ok", text) else Result(false, errorMessage(code, text))
    }.getOrElse { Result(false, it.message ?: "Couldn't reach GitHub.") }

    /** Parses [listRepos]'s JSON into repo info. Pure, so it is unit-tested. */
    fun parseRepos(json: String): List<RepoInfo> = runCatching {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val full = o.optString("full_name")
            RepoInfo(
                name = o.optString("name"),
                owner = full.substringBefore("/", o.optJSONObject("owner")?.optString("login") ?: ""),
                branch = o.optString("default_branch").ifBlank { "main" },
                private = o.optBoolean("private"),
            )
        }
    }.getOrDefault(emptyList())

    /** Lists every file path in [owner]/[repo] on [branch]. Raw JSON in [Result.data]. */
    fun listTree(token: String, owner: String, repo: String, branch: String): Result = runCatching {
        val (code, text) = http("GET", "$API/repos/$owner/$repo/git/trees/$branch?recursive=1", token, null)
        if (code in 200..299) Result(true, "ok", text) else Result(false, errorMessage(code, text))
    }.getOrElse { Result(false, it.message ?: "Couldn't reach GitHub.") }

    /** Parses [listTree]'s JSON into file paths (blobs only, no folders). Pure, so it is unit-tested. */
    fun parseTreePaths(json: String): List<String> = runCatching {
        val o = JSONObject(json)
        val tree = o.optJSONArray("tree") ?: return@runCatching emptyList<String>()
        (0 until tree.length()).mapNotNull {
            val e = tree.getJSONObject(it)
            if (e.optString("type") == "blob") e.optString("path") else null
        }
    }.getOrDefault(emptyList())

    /** Reads file [path] from [owner]/[repo] (on [branch] when given) and returns its decoded text in [Result.data]. */
    fun getFile(token: String, owner: String, repo: String, path: String, branch: String? = null): Result = runCatching {
        val p = path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val refQ = if (branch != null) "?ref=$branch" else ""
        val (code, text) = http("GET", "$API/repos/$owner/$repo/contents/$p$refQ", token, null)
        if (code == 404) return@runCatching Result(false, "not found", null)
        if (code !in 200..299) return@runCatching Result(false, errorMessage(code, text))
        val b64 = JSONObject(text).optString("content").replace("\n", "")
        val decoded = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
        Result(true, "ok", decoded)
    }.getOrElse { Result(false, it.message ?: "Couldn't reach GitHub.") }

    /**
     * Creates a release tagged [tag] and streams [file] up as an asset (handles large OBBs without loading
     * the whole file into memory). Returns the asset's download URL in [Result.data].
     */
    fun uploadReleaseAsset(token: String, owner: String, repo: String, tag: String, file: java.io.File): Result = runCatching {
        // Reuse the release for this tag if it exists, else make it.
        var uploadBase: String
        val existing = http("GET", "$API/repos/$owner/$repo/releases/tags/$tag", token, null)
        if (existing.first in 200..299) {
            val rel = JSONObject(existing.second)
            uploadBase = rel.optString("upload_url")
            // GitHub rejects a second asset with the same name — delete the old one first.
            val assets = rel.optJSONArray("assets")
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name") == file.name) {
                    http("DELETE", "$API/repos/$owner/$repo/releases/assets/${a.optLong("id")}", token, null)
                }
            }
        } else {
            val body = JSONObject().put("tag_name", tag).put("name", tag).put("body", "Uploaded by XARVIS").toString()
            val (code, text) = http("POST", "$API/repos/$owner/$repo/releases", token, body)
            if (code !in 200..299) return@runCatching Result(false, errorMessage(code, text))
            uploadBase = JSONObject(text).optString("upload_url")
        }
        // upload_url looks like ".../assets{?name,label}" — strip the template.
        val base = uploadBase.substringBefore("{")
        val assetName = file.name
        val url = "$base?name=" + URLEncoder.encode(assetName, "UTF-8")
        val dl = streamUpload(url, token, file)
        if (dl != null) Result(true, "uploaded", dl) else Result(false, "Upload failed.")
    }.getOrElse { Result(false, it.message ?: "Couldn't reach GitHub.") }

    /** Fires the workflow file [workflowFile] on branch main with [inputs]. */
    fun dispatchWorkflow(token: String, owner: String, repo: String, workflowFile: String, inputs: Map<String, String>): Result = runCatching {
        val inp = JSONObject(); for ((k, v) in inputs) inp.put(k, v)
        val body = JSONObject().put("ref", "main").put("inputs", inp).toString()
        val (code, text) = http("POST", "$API/repos/$owner/$repo/actions/workflows/$workflowFile/dispatches", token, body)
        if (code in 200..299) Result(true, "started") else Result(false, errorMessage(code, text))
    }.getOrElse { Result(false, it.message ?: "Couldn't reach GitHub.") }

    /** Streams the release asset named [name] under [tag] into [out]. Handles the redirect to storage without leaking the token. */
    fun downloadAsset(token: String, owner: String, repo: String, tag: String, name: String, out: java.io.OutputStream): Result = runCatching {
        val rel = http("GET", "$API/repos/$owner/$repo/releases/tags/$tag", token, null)
        if (rel.first !in 200..299) return@runCatching Result(false, errorMessage(rel.first, rel.second))
        val assets = JSONObject(rel.second).optJSONArray("assets")
        var assetUrl: String? = null
        if (assets != null) for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name") == name) { assetUrl = a.optString("url"); break }
        }
        if (assetUrl == null) return@runCatching Result(false, "the modified file wasn't found in the release yet")
        // First hop: authenticated, no redirect-follow, so the token is never sent to storage.
        val c = URL(assetUrl).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", "application/octet-stream")
        c.setRequestProperty("User-Agent", "XARVIS-Code")
        val code = c.responseCode
        val src = if (code in 300..399) {
            val loc = c.getHeaderField("Location"); c.disconnect()
            (URL(loc).openConnection() as HttpURLConnection).apply { setRequestProperty("User-Agent", "XARVIS-Code") }.inputStream
        } else if (code in 200..299) c.inputStream else { c.disconnect(); return@runCatching Result(false, "download failed ($code)") }
        src.use { input -> input.copyTo(out, 64 * 1024) }
        Result(true, "downloaded")
    }.getOrElse { Result(false, it.message ?: "Couldn't reach GitHub.") }

    /** The newest workflow run's status+conclusion for [owner]/[repo], as "status|conclusion" in [Result.data]. */
    fun latestRun(token: String, owner: String, repo: String): Result = runCatching {
        val (code, text) = http("GET", "$API/repos/$owner/$repo/actions/runs?per_page=1", token, null)
        if (code !in 200..299) return@runCatching Result(false, errorMessage(code, text))
        val runs = JSONObject(text).optJSONArray("workflow_runs")
        if (runs == null || runs.length() == 0) return@runCatching Result(false, "no runs yet")
        val r = runs.getJSONObject(0)
        Result(true, "ok", r.optString("status") + "|" + r.optString("conclusion") + "|" + r.optLong("id"))
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
        // External files/CDNs break an offline single-file app.
        if (Regex("""<(?:script|link|img)[^>]*\b(?:src|href)\s*=\s*["']https?://""", RegexOption.IGNORE_CASE).containsMatchIn(html)) {
            out.add("it loads an external file/CDN (a src/href to http) — everything must be inline, with no external files")
        }
        // Keyboard-only controls are unplayable on a phone (no keyboard): needs touch too.
        val hasKeyboard = Regex("""(keydown|keyup|keypress|onkey)""", RegexOption.IGNORE_CASE).containsMatchIn(html)
        val hasTouch = Regex("""(touchstart|touchend|touchmove|pointerdown|onclick|addEventListener\s*\(\s*["']click)""", RegexOption.IGNORE_CASE).containsMatchIn(html)
        if (hasKeyboard && !hasTouch) {
            out.add("it only responds to the keyboard — add large on-screen touch buttons or swipe, since the phone has no keyboard")
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

    /** Streams a file's bytes up to GitHub's asset endpoint without loading it all into memory. Returns the asset download URL. */
    private fun streamUpload(urlStr: String, token: String, file: java.io.File): String? {
        val c = URL(urlStr).openConnection() as HttpURLConnection
        return try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 120_000
            c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "XARVIS-Code")
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.setFixedLengthStreamingMode(file.length())
            file.inputStream().use { input -> c.outputStream.use { out -> input.copyTo(out, 64 * 1024) } }
            val code = c.responseCode
            if (code in 200..299) {
                val text = c.inputStream.bufferedReader().use { it.readText() }
                JSONObject(text).optString("browser_download_url")
            } else null
        } catch (e: Exception) { null } finally { c.disconnect() }
    }

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
