package com.xarvis.ai.task

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * The Night Shift (Rex, Nov 2026): while he's at the plant at night the S22 sits at home on Wi-Fi and the charger —
 * so it works too. Each job is a taught skill ("do my VPN") or a Task mode goal, at a time of day, every day. At that
 * time XARVIS runs it (if the phone is locked it says so on the benco and waits for Rex to unlock it with XARVIS
 * Remote — it never enters the pattern itself), then sends the benco a night report. Pure scheduling, unit-tested;
 * [NightShiftRunner] does the running.
 */
object NightShift {
    data class Job(
        val id: String,
        /** A skill ("do my VPN") or a task goal. */
        val what: String,
        val hour: Int,
        val minute: Int,
        val enabled: Boolean = true,
        val lastRun: Long = 0,
        val lastResult: String = "",
    ) {
        fun time() = "%02d:%02d".format(hour, minute)

        fun toJson(): JSONObject = JSONObject().put("id", id).put("what", what).put("h", hour).put("m", minute).put("on", enabled)
            .put("last", lastRun).put("result", lastResult)

        companion object {
            fun from(o: JSONObject): Job? {
                val what = o.optString("what").trim().takeIf { it.isNotEmpty() } ?: return null
                val h = o.optInt("h", -1).takeIf { it in 0..23 } ?: return null
                val m = o.optInt("m", 0).coerceIn(0, 59)
                return Job(o.optString("id").ifBlank { "j${what.hashCode()}" }, what, h, m, o.optBoolean("on", true), o.optLong("last"), o.optString("result"))
            }
        }
    }

    /** A job still runs if its time passed less than this long ago (the phone was busy, locked or restarting). */
    const val WINDOW_MS = 3 * 3_600_000L

    fun parse(json: String?): List<Job> = runCatching {
        val arr = JSONArray(json ?: "[]")
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> Job.from(o) } }
    }.getOrDefault(emptyList())

    fun toJson(jobs: List<Job>): String = JSONArray().apply { jobs.forEach { put(it.toJson()) } }.toString()

    /** The job's time today or yesterday — the most recent one at or before [now]. */
    fun lastTime(job: Job, now: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val c = Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, job.hour)
            set(Calendar.MINUTE, job.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis > now) c.add(Calendar.DAY_OF_YEAR, -1)
        return c.timeInMillis
    }

    /** The job's next time after [now]. */
    fun nextTime(job: Job, now: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val c = Calendar.getInstance(zone).apply {
            timeInMillis = lastTime(job, now, zone)
            add(Calendar.DAY_OF_YEAR, 1)
        }
        return c.timeInMillis
    }

    /** Jobs whose time has come (within [WINDOW_MS]) and that haven't run since, earliest first. */
    fun due(jobs: List<Job>, now: Long, zone: TimeZone = TimeZone.getDefault()): List<Job> =
        jobs.filter { it.enabled }
            .map { it to lastTime(it, now, zone) }
            .filter { (job, at) -> now - at <= WINDOW_MS && job.lastRun < at }
            .sortedBy { it.second }
            .map { it.first }

    /** When to wake up next for any job, or null when there are none. */
    fun nextAlarm(jobs: List<Job>, now: Long, zone: TimeZone = TimeZone.getDefault()): Long? =
        jobs.filter { it.enabled }.minOfOrNull { nextTime(it, now, zone) }

    /** The report for the benco: one line a job. */
    fun report(results: List<Pair<Job, String>>): String {
        val ok = results.count { it.second.startsWith("✓") }
        val head = "🌙 Night shift report: $ok of ${results.size} done" + if (ok < results.size) ", ${results.size - ok} need you." else "."
        return head + "\n" + results.joinToString("\n") { (j, r) -> "${j.time()} ${j.what.take(60)} — ${r.take(200)}" }
    }

    fun describe(jobs: List<Job>): String =
        if (jobs.isEmpty()) "The night shift is empty. Add a job like \"night shift: do my VPN at 2 am\"."
        else "🌙 Night shift (every night):\n" + jobs.sortedBy { it.hour * 60 + it.minute }.joinToString("\n") { j ->
            "${if (j.enabled) "•" else "◦ (off)"} ${j.time()} — ${j.what}" + (j.lastResult.takeIf { it.isNotBlank() }?.let { "  · last: ${it.take(80)}" } ?: "")
        }
}
