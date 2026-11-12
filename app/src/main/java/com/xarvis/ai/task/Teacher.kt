package com.xarvis.ai.task

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.xarvis.ai.R
import com.xarvis.ai.hands.HandsBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Teach mode ("show me once"): while Rex does a job, XARVIS Hands passes his taps, typing and app changes here, and
 * the screen's labels are noted every few seconds (what he waited for, what appeared). ✅ Done compiles it into a
 * draft [Skill] ([Teach.compile]) for Rex to name and save on the Skills screen. Nothing from banking / payment apps is
 * kept, and a password's text never reaches here (Hands sends an empty text for password boxes).
 */
object Teacher {
    data class Status(val recording: Boolean = false, val taps: Int = 0, val apps: List<String> = emptyList(), val startedAt: Long = 0)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    /** The last recording, compiled, waiting to be named and saved. */
    @Volatile var draft: Teach.Draft? = null

    private val lock = Any()
    private val events = ArrayList<TeachEvent>()
    private val snaps = ArrayList<TeachSnap>()
    private var thread: HandlerThread? = null
    @Volatile private var handler: Handler? = null
    @Volatile private var appContext: Context? = null

    /** Starts watching; null when it started, else why it can't. */
    fun start(context: Context): String? {
        if (!HandsBridge.builtIn) return "Teach mode runs on the S22 (it needs XARVIS Hands built in)."
        HandsBridge.service ?: return "Switch on XARVIS Hands first: ☰ → XARVIS Hands → Open Accessibility settings → XARVIS Hands → On."
        val phase = TaskManager.state.value.phase
        if (phase == TaskManager.Phase.PLANNING || phase == TaskManager.Phase.RUNNING || phase == TaskManager.Phase.WAITING) {
            return "XARVIS is doing a task right now — let it finish (or STOP it) first."
        }
        if (_status.value.recording) return null
        synchronized(lock) {
            events.clear()
            snaps.clear()
        }
        draft = null
        appContext = context.applicationContext
        val t = HandlerThread("xarvis-teach").also { it.start() }
        thread = t
        handler = Handler(t.looper)
        _status.value = Status(recording = true, startedAt = System.currentTimeMillis())
        HandsBridge.teach = { e -> onEvent(e) }
        notice(context.applicationContext)
        handler?.postDelayed(periodic, SNAP_EVERY_MS)
        // Start from the home screen, as the replay will.
        Handler(Looper.getMainLooper()).postDelayed({ HandsBridge.service?.globalAction("home") }, 400)
        return null
    }

    /** ✅ Done: stops watching and returns the draft (null if nothing useful was recorded). */
    fun finish(context: Context): Teach.Draft? {
        if (!_status.value.recording) return draft
        stopWatching(context)
        val d = synchronized(lock) { Teach.compile(events.toList(), snaps.toList()) }
        draft = d.takeIf { it.steps.isNotEmpty() }
        return draft
    }

    fun cancel(context: Context) {
        stopWatching(context)
        draft = null
        synchronized(lock) {
            events.clear()
            snaps.clear()
        }
    }

    private fun stopWatching(context: Context) {
        HandsBridge.teach = null
        _status.value = _status.value.copy(recording = false)
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTICE_ID)
    }

    private fun onEvent(e: TeachEvent) {
        if (!_status.value.recording) return
        // A banking app: only that it was there (no labels, no text).
        val kept = if (TaskRunner.Safety.moneyApp(e.app) || TaskRunner.Safety.moneyApp(e.pkg)) TeachEvent.Window(e.at, e.pkg, e.app) else e
        val taps = synchronized(lock) {
            if (events.size < MAX_EVENTS) events += kept
            events.count { it is TeachEvent.Click }
        }
        if (kept !is TeachEvent.Text) handler?.postDelayed({ snap() }, AFTER_MS)
        val s = _status.value
        val shown = e.pkg != "com.xarvis.ai" && e.pkg != "com.android.systemui" && !Teach.launcher(e.pkg) && e.app.isNotBlank()
        _status.value = s.copy(taps = taps, apps = if (shown && e.app !in s.apps) s.apps + e.app else s.apps)
    }

    private val periodic = object : Runnable {
        override fun run() {
            val s = _status.value
            if (!s.recording) return
            if (System.currentTimeMillis() - s.startedAt > MAX_MINUTES * 60_000L) {
                // Forgotten: stop and keep what there is for the Skills screen.
                appContext?.let { c ->
                    finish(c)
                    TaskManager.notice(c, "🎓 Teach mode stopped", "It ran for $MAX_MINUTES minutes. Open ☰ → 🧠 Task mode → 🎓 Skills to save or drop what it saw.")
                }
                return
            }
            snap()
            handler?.postDelayed(this, SNAP_EVERY_MS)
        }
    }

    /** The labels on screen now (not XARVIS's own screens, not banking apps). */
    private fun snap() {
        val json = runCatching { HandsBridge.service?.screenElements() }.getOrNull() ?: return
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        val pkg = o.optString("pkg")
        val app = o.optString("app")
        if (pkg.isBlank() || pkg == "com.xarvis.ai" || TaskRunner.Safety.moneyApp(app) || TaskRunner.Safety.moneyApp(pkg)) return
        val arr = o.optJSONArray("els") ?: return
        val labels = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("t")?.trim()?.takeIf { s -> s.isNotEmpty() } }
        synchronized(lock) {
            val last = snaps.lastOrNull()
            if (last != null && last.pkg == pkg && last.labels == labels) return
            if (snaps.size < MAX_SNAPS) snaps += TeachSnap(System.currentTimeMillis(), pkg, app, labels)
        }
    }

    private fun notice(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Teach mode", NotificationManager.IMPORTANCE_LOW))
        fun open(code: Int, action: String?) = PendingIntent.getActivity(
            context, code,
            Intent(context, com.xarvis.ai.SkillsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply { if (action != null) putExtra(com.xarvis.ai.SkillsActivity.EXTRA_TEACH, action) },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            NOTICE_ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_xarvis)
                .setContentTitle("🔴 XARVIS is watching and learning")
                .setContentText("Do the job once, your way. Then pull this down and tap ✅ Done.")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "Do the job once, your way — XARVIS notes your taps and what appears. Passwords and banking apps are never recorded. " +
                            "When you're finished, pull this down and tap ✅ Done.",
                    ),
                )
                .setOngoing(true)
                .setContentIntent(open(7311, null))
                .addAction(0, "✅ Done", open(7312, com.xarvis.ai.SkillsActivity.TEACH_DONE))
                .addAction(0, "✖ Cancel", open(7313, com.xarvis.ai.SkillsActivity.TEACH_CANCEL))
                .build(),
        )
    }

    private const val CHANNEL = "xarvis_teach"
    private const val NOTICE_ID = 7310
    private const val SNAP_EVERY_MS = 2_500L
    private const val AFTER_MS = 1_200L
    private const val MAX_EVENTS = 3_000
    private const val MAX_SNAPS = 600
    private const val MAX_MINUTES = 30
}
