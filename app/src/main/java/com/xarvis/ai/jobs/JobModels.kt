package com.xarvis.ai.jobs

import org.json.JSONArray
import org.json.JSONObject

/** Which CV a job gets: supervisor / superintendent work, or instructor / trainer work. */
enum class JobKind(val label: String) { SUPERVISOR("Supervisor / Superintendent"), TRAINER("Instructor / Trainer") }

/** The agency trust system's verdict, always shown with its reasons. */
enum class Verdict(val label: String) { AUTHENTIC("✅ Authentic"), SUSPICIOUS("⚠️ Suspicious"), SCAM("🚫 Scam") }

/**
 * NEW: a draft waiting for Rex. APPROVED: ticked, waiting to be sent. LINK: no email published —
 * Rex applies on the site himself ("Apply on LinkedIn"). SENT / FAILED / SKIPPED: done.
 */
enum class JobStatus { NEW, APPROVED, LINK, SENT, FAILED, SKIPPED }

/** One job the hunter found, with its checks and (when it has an email) the draft application. */
data class JobItem(
    val id: String,
    val title: String,
    val company: String = "",
    val country: String = "",
    val countryRank: Int = 99,
    val location: String = "",
    val salaryText: String = "",
    /** The salary converted to rupees per month (the top of a range); null when not stated or unreadable. */
    val salaryInr: Long? = null,
    val url: String = "",
    val source: String = "",
    val email: String? = null,
    val kind: JobKind = JobKind.SUPERVISOR,
    val verdict: Verdict = Verdict.SUSPICIOUS,
    val reasons: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val score: Int = 0,
    val status: JobStatus = JobStatus.NEW,
    val subject: String = "",
    val body: String = "",
    val snippet: String = "",
    val found: Long = System.currentTimeMillis(),
    val sentAt: Long? = null,
    val error: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("company", company).put("country", country).put("rank", countryRank)
        .put("location", location).put("salaryText", salaryText).put("salaryInr", salaryInr ?: JSONObject.NULL)
        .put("url", url).put("source", source).put("email", email ?: JSONObject.NULL).put("kind", kind.name)
        .put("verdict", verdict.name).put("reasons", JSONArray(reasons)).put("warnings", JSONArray(warnings))
        .put("score", score).put("status", status.name).put("subject", subject).put("body", body)
        .put("snippet", snippet).put("found", found).put("sentAt", sentAt ?: JSONObject.NULL).put("error", error ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): JobItem = JobItem(
            id = o.getString("id"),
            title = o.optString("title"),
            company = o.optString("company"),
            country = o.optString("country"),
            countryRank = o.optInt("rank", 99),
            location = o.optString("location"),
            salaryText = o.optString("salaryText"),
            salaryInr = if (o.isNull("salaryInr")) null else o.optLong("salaryInr"),
            url = o.optString("url"),
            source = o.optString("source"),
            email = if (o.isNull("email")) null else o.optString("email").ifBlank { null },
            kind = runCatching { JobKind.valueOf(o.optString("kind")) }.getOrDefault(JobKind.SUPERVISOR),
            verdict = runCatching { Verdict.valueOf(o.optString("verdict")) }.getOrDefault(Verdict.SUSPICIOUS),
            reasons = o.optJSONArray("reasons").strings(),
            warnings = o.optJSONArray("warnings").strings(),
            score = o.optInt("score"),
            status = runCatching { JobStatus.valueOf(o.optString("status")) }.getOrDefault(JobStatus.NEW),
            subject = o.optString("subject"),
            body = o.optString("body"),
            snippet = o.optString("snippet"),
            found = o.optLong("found", System.currentTimeMillis()),
            sentAt = if (o.isNull("sentAt")) null else o.optLong("sentAt"),
            error = if (o.isNull("error")) null else o.optString("error"),
        )

        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { getString(it) }

        /** A stable id for a job, so the same ad found twice (or by two sources) is kept once. */
        fun idFor(title: String, company: String, email: String?, url: String): String {
            val key = listOf(norm(title), norm(company), email.orEmpty().lowercase(), if (email == null) url.substringBefore('?') else "")
                .joinToString("|")
            return Integer.toHexString(key.hashCode()) + key.length.toString(36)
        }

        private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
    }
}

/** A job before the checks: what a source (search, Jooble, a LinkedIn alert) found. */
data class RawJob(
    val title: String,
    val company: String = "",
    val location: String = "",
    val salaryText: String = "",
    val url: String = "",
    val source: String,
    /** The ad's text (snippet or the whole page), where emails, requirements and red flags are looked for. */
    val text: String = "",
)
