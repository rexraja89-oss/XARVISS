package com.xarvis.ai.task

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xarvis.ai.XarvisApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Runs the [NightShift] on the S22: one exact alarm at the next job's time ([schedule]); when it rings, every due job
 * runs in turn (each waits for any task already running), the benco hears each result and then a night report, and the
 * report is kept for the morning ([lastReport]). Jobs live in prefs "tasks" / "nightshift".
 */
object NightShiftRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = Mutex()

    fun jobs(context: Context): List<NightShift.Job> = NightShift.parse(prefs(context).getString(KEY, "[]"))

    private fun save(context: Context, jobs: List<NightShift.Job>) {
        prefs(context).edit().putString(KEY, NightShift.toJson(jobs)).apply()
        schedule(context)
    }

    fun add(context: Context, what: String, hour: Int, minute: Int): NightShift.Job {
        // lastRun = now: a time already past today waits for tomorrow instead of running at once.
        val now = System.currentTimeMillis()
        val job = NightShift.Job("j" + now.toString(36), what.trim(), hour.coerceIn(0, 23), minute.coerceIn(0, 59), lastRun = now)
        save(context, jobs(context) + job)
        return job
    }

    fun remove(context: Context, id: String) = save(context, jobs(context).filterNot { it.id == id })

    fun setEnabled(context: Context, id: String, on: Boolean) =
        save(context, jobs(context).map { if (it.id == id) it.copy(enabled = on, lastRun = if (on) System.currentTimeMillis() else it.lastRun) else it })

    fun clear(context: Context) = save(context, emptyList())

    /** The last night's report, if it's from the last 18 hours. */
    fun lastReport(context: Context): String? {
        val p = prefs(context)
        val at = p.getLong(KEY_REPORT_AT, 0)
        if (System.currentTimeMillis() - at > 18 * 3_600_000L) return null
        return p.getString(KEY_REPORT, null)
    }

    /** (Re)arms the alarm for the next job (after boot, an update, or a change). */
    fun schedule(context: Context) {
        val pi = alarmIntent(context)
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val next = NightShift.nextAlarm(jobs(context), System.currentTimeMillis())
        if (next == null) {
            am.cancel(pi)
            return
        }
        com.xarvis.ai.remind.ProactiveReceiver.armExact(context, next, pi)
    }

    /** The alarm rang (or XARVIS started late): run what's due, one after another. */
    fun runDue(context: Context) {
        val app = context.applicationContext
        scope.launch {
            if (!running.tryLock()) return@launch // already working through the list
            try {
                work(app)
            } finally {
                running.unlock()
                schedule(app)
            }
        }
    }

    private suspend fun work(context: Context) {
        val due = NightShift.due(jobs(context), System.currentTimeMillis())
        if (due.isEmpty()) return
        val core = (context as XarvisApp).core
        core.tellOtherPhone("🌙 Night shift starting: " + due.joinToString(" · ") { "${it.time()} ${it.what.take(40)}" })
        val results = ArrayList<Pair<NightShift.Job, String>>()
        for (job in due) {
            val result = runOne(context, job)
            results += job to result
            save(context, jobs(context).map { if (it.id == job.id) it.copy(lastRun = System.currentTimeMillis(), lastResult = result.take(300)) else it })
        }
        val report = NightShift.report(results)
        prefs(context).edit().putString(KEY_REPORT, report).putLong(KEY_REPORT_AT, System.currentTimeMillis()).apply()
        core.tellOtherPhone(report)
        TaskManager.notice(context, "🌙 Night shift report", report)
    }

    /** Runs one job and waits for it; "✓ …" when it finished, else what stopped it. */
    private suspend fun runOne(context: Context, job: NightShift.Job): String {
        // Something already running (a task Rex started, or the last job): wait for it, up to 2 hours.
        var waited = 0L
        while (TaskManager.busy()) {
            if (waited > 2 * 3_600_000L) return "⚠ skipped: XARVIS was busy with another task for 2 hours"
            delay(30_000)
            waited += 30_000
        }
        val core = (context as XarvisApp).core
        val reporter = core.nightReporter()
        val (skill, values) = skillFor(context, job.what)
        val why = if (skill != null) {
            TaskManager.runSkill(context, skill, values, from = reporter)
        } else {
            TaskManager.start(context, job.what, from = reporter)
        }
        if (why != null) return "⚠ couldn't start: $why"
        delay(2_000)
        var left = 4 * 3_600_000L
        while (TaskManager.busy() && left > 0) {
            delay(5_000)
            left -= 5_000
        }
        if (TaskManager.busy()) {
            TaskManager.stop()
            return "⚠ stopped after 4 hours"
        }
        val s = TaskManager.state.value
        return when {
            s.phase == TaskManager.Phase.DONE && TaskManager.recent(context).firstOrNull()?.optBoolean("finished") == true -> "✓ " + s.result.lineSequence().first()
            s.phase == TaskManager.Phase.DONE -> "⏹ " + s.result.lineSequence().first()
            else -> "⚠ " + (s.error ?: "it didn't finish")
        }
    }

    /** A job that names a skill ("do my VPN", or just "VPN"): that skill and its values; else (null, empty) = a task goal. */
    fun skillFor(context: Context, what: String): Pair<Skill?, Map<String, String>> {
        val all = TaskManager.skills(context).all()
        val cmd = TaskCommands.parse(what) as? TaskCommands.Cmd.Skill
        if (cmd != null) Skills.resolve(all, cmd)?.let { return it }
        all.firstOrNull { SkillBook.key(it.name) == SkillBook.key(what) }?.let { return it to emptyMap() }
        return null to emptyMap()
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 7320, Intent(context, NightShiftReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun prefs(context: Context) = context.getSharedPreferences("tasks", Context.MODE_PRIVATE)
    private const val KEY = "nightshift"
    private const val KEY_REPORT = "nightshift_report"
    private const val KEY_REPORT_AT = "nightshift_report_at"
}

/** The Night Shift's alarm. */
class NightShiftReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        NightShiftRunner.runDue(context)
    }
}
