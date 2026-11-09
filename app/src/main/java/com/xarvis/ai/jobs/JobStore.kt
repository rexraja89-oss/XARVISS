package com.xarvis.ai.jobs

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * What the Job Hunter keeps on the phone (app storage only, never in the cloud or GitHub): the jobs and drafts,
 * the log of every address ever written to (so nobody is emailed twice), Rex's two CVs, his trusted / blocked
 * lists, and how much of the free Tavily / Jooble allowance has been used.
 */
class JobStore(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "jobs").apply { mkdirs() }
    private val jobsFile = File(dir, "jobs.json")
    private val sentFile = File(dir, "sent.json")
    private val prefs = app.getSharedPreferences("jobhunter", Context.MODE_PRIVATE)

    // ---- jobs ----

    fun jobs(): List<JobItem> = synchronized(LOCK) {
        runCatching {
            val a = JSONArray(jobsFile.readText())
            (0 until a.length()).map { JobItem.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun saveJobs(list: List<JobItem>) = synchronized(LOCK) {
        // Keep it small: done jobs older than 60 days go (the sent log still remembers the addresses).
        val cutoff = System.currentTimeMillis() - 60L * 86_400_000L
        val kept = list.filter { it.status in setOf(JobStatus.NEW, JobStatus.APPROVED, JobStatus.LINK) || it.found > cutoff }
            .sortedByDescending { it.found }.take(600)
        val a = JSONArray()
        kept.forEach { a.put(it.toJson()) }
        jobsFile.writeText(a.toString())
    }

    fun update(id: String, change: (JobItem) -> JobItem) = synchronized(LOCK) {
        saveJobs(jobs().map { if (it.id == id) change(it) else it })
    }

    // ---- the sent log ----

    data class Sent(val email: String, val at: Long, val title: String, val company: String, val replied: Boolean)

    fun sent(): List<Sent> = synchronized(LOCK) {
        runCatching {
            val a = JSONArray(sentFile.readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Sent(o.getString("email"), o.getLong("at"), o.optString("title"), o.optString("company"), o.optBoolean("replied"))
            }
        }.getOrDefault(emptyList())
    }

    private fun saveSent(list: List<Sent>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("email", it.email).put("at", it.at).put("title", it.title).put("company", it.company).put("replied", it.replied)) }
        sentFile.writeText(a.toString())
    }

    fun logSent(email: String, title: String, company: String) = synchronized(LOCK) {
        saveSent(sent() + Sent(email.lowercase(), System.currentTimeMillis(), title, company, false))
    }

    fun markReplied(email: String) = synchronized(LOCK) {
        saveSent(sent().map { if (it.email.equals(email, true)) it.copy(replied = true) else it })
    }

    fun alreadyEmailed(email: String): Boolean = sent().any { it.email.equals(email, true) }

    fun sentToday(): Int {
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return sent().count { it.at >= start }
    }

    // ---- CVs ----

    fun cvFile(kind: JobKind): File = File(dir, "cv_${kind.name}.pdf")
    fun cvName(kind: JobKind): String? = prefs.getString("cvName_${kind.name}", null)?.takeIf { cvFile(kind).exists() }

    fun saveCv(kind: JobKind, name: String, bytes: ByteArray, text: String) {
        cvFile(kind).writeBytes(bytes)
        File(dir, "cv_${kind.name}.txt").writeText(text)
        prefs.edit().putString("cvName_${kind.name}", name).apply()
    }

    fun cvText(kind: JobKind): String = runCatching { File(dir, "cv_${kind.name}.txt").readText() }.getOrDefault("")

    /** The CV for [kind], or the other one when only one was given. */
    fun cvFor(kind: JobKind): JobKind? = when {
        cvName(kind) != null -> kind
        cvName(JobKind.SUPERVISOR) != null -> JobKind.SUPERVISOR
        cvName(JobKind.TRAINER) != null -> JobKind.TRAINER
        else -> null
    }

    // ---- Rex's details for the letters (read from his CV; he can correct them) ----

    fun profile(kind: JobKind): JobLetter.Profile {
        val from = JobLetter.profileFrom(cvText(kind))
        return from.copy(
            name = prefs.getString("name", null)?.ifBlank { null } ?: from.name,
            email = prefs.getString("email", null)?.ifBlank { null } ?: from.email,
            phones = prefs.getString("phones", null)?.split('\n', '|')?.map { it.trim() }?.filter { it.isNotEmpty() }?.ifEmpty { null } ?: from.phones,
            summary = prefs.getString("summary_${kind.name}", null)?.ifBlank { null } ?: from.summary,
        )
    }

    fun saveProfile(name: String, email: String, phones: String, kind: JobKind, summary: String) {
        prefs.edit().putString("name", name.trim()).putString("email", email.trim()).putString("phones", phones.trim())
            .putString("summary_${kind.name}", summary.trim()).apply()
    }

    // ---- trusted / blocked ----

    fun trusted(): Set<String> = prefs.getStringSet("trusted", emptySet()).orEmpty()
    fun blocked(): Set<String> = prefs.getStringSet("blocked", emptySet()).orEmpty()

    /** [trust]: true = trusted, false = blocked, null = off both lists. */
    fun setTrust(name: String, trust: Boolean?) {
        val n = name.trim().lowercase()
        val t = trusted() - n
        val b = blocked() - n
        prefs.edit()
            .putStringSet("trusted", if (trust == true) t + n else t)
            .putStringSet("blocked", if (trust == false) b + n else b)
            .apply()
    }

    // ---- the free allowances ----

    private fun month() = SimpleDateFormat("yyyyMM", Locale.US).format(Date())

    fun tavilyUsed(): Int = if (prefs.getString("tavilyMonth", "") == month()) prefs.getInt("tavilyUsed", 0) else 0
    fun addTavily(n: Int = 1) {
        prefs.edit().putString("tavilyMonth", month()).putInt("tavilyUsed", tavilyUsed() + n).apply()
    }

    fun joobleUsed(): Int = prefs.getInt("joobleUsed", 0)
    fun addJooble(n: Int = 1) = prefs.edit().putInt("joobleUsed", joobleUsed() + n).apply()

    var lastHunt: Long
        get() = prefs.getLong("lastHunt", 0)
        set(v) = prefs.edit().putLong("lastHunt", v).apply()

    var lastReplyCheck: Long
        get() = prefs.getLong("lastReplyCheck", 0)
        set(v) = prefs.edit().putLong("lastReplyCheck", v).apply()

    fun seenReply(uid: String): Boolean = uid in prefs.getStringSet("replies", emptySet()).orEmpty()
    fun markReplySeen(uid: String) = prefs.edit().putStringSet("replies", prefs.getStringSet("replies", emptySet()).orEmpty() + uid).apply()

    companion object {
        private val LOCK = Any()
        /** Tavily's free allowance a month, and what the hunter leaves untouched for Rex's other searches. */
        const val TAVILY_MONTH = 1_000
        const val TAVILY_RESERVE = 50
        /** Jooble gave Rex 500 requests (it didn't say per month); the hunter stops a little before. */
        const val JOOBLE_LIMIT = 480
    }
}
