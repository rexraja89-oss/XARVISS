package com.xarvis.ai.task

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.PowerManager
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.xarvis.ai.R
import com.xarvis.ai.XarvisApp
import com.xarvis.ai.agent.PhoneAgent
import com.xarvis.ai.agent.screenFrom
import com.xarvis.ai.hands.HandsBridge
import com.xarvis.ai.llm.QuotaReached
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume

/**
 * Task mode on this phone (the S22): ☰ → 🧠 Task mode, "task: …", or "task on my S22: …" from the benco. Rex writes a
 * goal → the cloud brain plans → [TaskRunner] does it straight away (his task is his permission for the steps it needs:
 * Rex, Nov 2026, "just one prompt and it should accomplish the task") with XARVIS Hands, seeing the screen as numbered elements plus a screenshot with the
 * same numbers drawn on it (the brain points at them). While it runs: the screen is kept on, Hands' ✱ banner and a
 * notification have STOP, and questions for Rex come as a notification that opens the Task screen.
 */
object TaskManager {
    enum class Phase { IDLE, PLANNING, RUNNING, WAITING, DONE }

    data class State(
        val phase: Phase = Phase.IDLE,
        val goal: String = "",
        val plan: Plan? = null,
        val log: List<String> = emptyList(),
        val question: String? = null,
        val yesNo: Boolean = false,
        val result: String = "",
        val error: String? = null,
        val startedAt: Long = 0,
        /** The linked phone that asked for this task (it gets the progress, questions and result), or null. */
        val from: String? = null,
    )

    /** The linked phone that asked for the task: progress, questions and the result go back to it with [send]. */
    class Reporter(val name: String, val send: (String) -> Unit)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    @Volatile private var stop = false
    @Volatile private var answer: CompletableDeferred<String?>? = null
    private var wake: PowerManager.WakeLock? = null
    @Volatile private var book: LessonBook? = null
    @Volatile private var reporter: Reporter? = null
    @Volatile private var practiceId: String? = null

    fun lessons(context: Context): LessonBook =
        book ?: synchronized(this) { book ?: LessonBook(File(context.filesDir, "tasks/lessons.json")).also { book = it } }

    @Volatile private var skillBook: SkillBook? = null

    /** Rex's taught skills ("show me once"). */
    fun skills(context: Context): SkillBook =
        skillBook ?: synchronized(this) { skillBook ?: SkillBook(File(context.filesDir, "tasks/skills.json")).also { skillBook = it } }

    /** Whether a task or skill is planning, running or waiting for Rex. */
    fun busy(): Boolean = _state.value.phase.let { it == Phase.PLANNING || it == Phase.RUNNING || it == Phase.WAITING } || job?.isActive == true

    /**
     * Replays one of Rex's skills now ("do my VPN"): no planning, no brain unless a step looks different (then the
     * brain does that step alone, if the cloud brain is set up). [values] fill the skill's changing texts. Returns null
     * when it started, else why it can't.
     */
    fun runSkill(context: Context, skill: Skill, values: Map<String, String> = emptyMap(), from: Reporter? = null): String? {
        if (job?.isActive == true) {
            return "I'm already doing a task (${_state.value.plan?.title ?: _state.value.goal.take(60)}). Say \"stop the task\" first."
        }
        if (!HandsBridge.builtIn) return "Skills run on the S22 (they need XARVIS Hands built in)."
        if (HandsBridge.service == null) return "Switch on XARVIS Hands first: ☰ → XARVIS Hands → Open Accessibility settings → XARVIS Hands → On."
        val app = context.applicationContext as XarvisApp
        stop = false
        reporter = from
        practiceId = null
        val plan = Plan("🎓 ${skill.name}", skill.steps.map { PlanStep(it.describe(), it.app(), "", "") })
        _state.value = State(Phase.RUNNING, goal = "Skill: ${skill.name}", plan = plan, startedAt = System.currentTimeMillis(), from = from?.name)
        job = scope.launch {
            keepAwake(app, true)
            HandsBridge.service?.controlBanner("XARVIS (skill)") { stop = true }
            val env = PhoneEnv(app, skill.name)
            val runner = SkillRunner(env, lessons(app), brainHelp = app.core.visionReady())
            report("🎓 Running your skill “${skill.name}” (${skill.steps.size} steps).")
            val result = runCatching { runner.run(skill, values) }.getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) null else SkillRunner.Result(false, "It broke off: ${e.message ?: e.javaClass.simpleName}", 0, 0)
            }
            runCatching { HandsBridge.service?.controlBanner(null) {} }
            keepAwake(app, false)
            val ok = result?.ok == true
            val summary = result?.summary ?: "Stopped."
            runCatching { skills(app).recordRun(skill.name, ok, (result?.helped ?: 1) == 0, summary, System.currentTimeMillis()) }
            remember(app, "Skill: ${skill.name}", plan, ok, summary)
            _state.value = _state.value.copy(phase = Phase.DONE, question = null, result = summary)
            notify(app, if (ok) "🏁 Skill done" else "⏹ Skill stopped", summary.lineSequence().first(), high = true)
            report(if (ok) "🏁 Done — $summary" else "⏹ $summary")
            reporter = null
        }
        return null
    }

    /** What's missing for Task mode on this phone, or null when it's ready. */
    fun notReady(context: Context): String? {
        val core = (context.applicationContext as XarvisApp).core
        return when {
            !HandsBridge.builtIn -> "Task mode runs on the S22 (it needs XARVIS Hands built in)."
            HandsBridge.service == null -> "Switch on XARVIS Hands first: ☰ → XARVIS Hands → Open Accessibility settings → XARVIS Hands → On."
            !core.visionReady() -> "Task mode needs the cloud brain with a Gemini key (☰ → BRAIN → Cloud brain), and the internet."
            else -> null
        }
    }

    // ---- planning, then doing it ----

    /**
     * Rex's task: plan it, then do it straight away — no approval step. [from] is the linked phone that asked (the
     * benco); [practice] a practice task's id. Returns null when it started, else why it can't.
     */
    fun start(context: Context, goal: String, from: Reporter? = null, practice: String? = null, app: String? = null): String? {
        val appHint = app
        val g = goal.trim()
        if (job?.isActive == true) {
            return "I'm already doing a task (${_state.value.plan?.title ?: _state.value.goal.take(60)}). Say \"stop the task\" first."
        }
        notReady(context)?.let { return it }
        val app = context.applicationContext as XarvisApp
        stop = false
        reporter = from
        practiceId = practice
        _state.value = State(Phase.PLANNING, goal = g, startedAt = System.currentTimeMillis(), from = from?.name)
        job = scope.launch {
            // A simple job in one known app ("open Ninja VPN and connect it…") needs no planning call: one step, its app.
            val plan = Plan.quick(g, appHint) ?: runCatching { askPlan(app, g) }.getOrNull()
            if (plan == null) {
                val why = "I couldn't make a plan just now (is the internet on, and the Gemini key working?). Try again."
                _state.value = State(Phase.IDLE, goal = g, error = why)
                report("⚠ $why")
                reporter = null
                practiceId = null
                return@launch
            }
            report("🧠 On it: ${plan.title} — " + plan.steps.mapIndexed { i, st -> "${i + 1}. ${st.title}" }.joinToString(" · "))
            run(app, g, plan)
        }
        return null
    }

    private suspend fun askPlan(context: Context, goal: String): Plan? {
        val core = (context as XarvisApp).core
        val apps = runCatching { com.xarvis.ai.device.DeviceCapabilityManager(context).launchableApps().map { it.label } }.getOrDefault(emptyList())
        val prompt = TaskPrompts.planPrompt(
            goal, apps, lessons(context).promptFor(emptyList(), 15), skills = skills(context).all().map { it.summary() },
        )
        return Plan.parse(core.generateCode(TaskPrompts.PLAN_SYSTEM, prompt))
    }

    private suspend fun run(app: XarvisApp, goal: String, plan: Plan) {
        _state.value = _state.value.copy(phase = Phase.RUNNING, plan = plan, error = null, log = emptyList())
        keepAwake(app, true)
        HandsBridge.service?.controlBanner("XARVIS (task)") { stop = true }
        val env = PhoneEnv(app, plan.title)
        val runner = TaskRunner(env, lessons(app), skills = { name -> skills(app).find(name) })
        progress("🧠 Plan: ${plan.title} — " + plan.steps.mapIndexed { i, st -> "${i + 1}. ${st.title.take(80)}" }.joinToString(" · "))
        val outcome = runCatching { runner.run(goal, plan) }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) null else TaskRunner.Outcome(false, "It broke off: ${e.message ?: e.javaClass.simpleName}", 0, plan)
        }
        runCatching { HandsBridge.service?.controlBanner(null) {} }
        keepAwake(app, false)
        val finished = outcome?.finished == true
        val summary = outcome?.summary ?: "Stopped."
        remember(app, goal, plan, finished, summary)
        practiceId?.let { id -> Practice.record(app, id, outcome) }
        _state.value = _state.value.copy(phase = Phase.DONE, question = null, result = summary)
        notify(app, if (finished) "🏁 Task finished" else "⏹ Task ended", summary.lineSequence().first(), high = true)
        report(if (finished) "🏁 Done — $summary" else "⏹ $summary")
        reporter = null
        practiceId = null
        // Lessons for next time, after Rex has his answer — on its own, so it never holds up the next task.
        if (outcome != null) scope.launch { runCatching { runner.reflect(goal, outcome) } }
    }

    /** Tells the phone that asked for the task (if another one did). */
    private fun report(text: String) {
        reporter?.let { r -> runCatching { r.send(text) } }
    }

    /** A line for "task status" (here or from the other phone). */
    fun statusLine(): String {
        val s = _state.value
        return when (s.phase) {
            Phase.IDLE -> "No task running." + (s.error?.let { " Last time: $it" } ?: "")
            Phase.PLANNING -> "Planning: ${s.goal.take(80)}"
            Phase.RUNNING -> "Working on ${s.plan?.title ?: "the task"} (${(System.currentTimeMillis() - s.startedAt) / 60_000} min): " +
                (s.log.lastOrNull() ?: "starting")
            Phase.WAITING -> "Waiting for your answer: ${s.question}"
            Phase.DONE -> "Finished: ${s.result.lineSequence().first().take(200)}"
        }
    }

    /** Whether a question is waiting for Rex's answer. */
    fun waitingForAnswer(): Boolean = answer != null

    /** A notification from Task mode (also used for news from the other phone's task). */
    fun notice(context: Context, title: String, text: String) = notify(context, title, text, high = true)

    /** STOP (Task screen, notification, or Hands' banner). */
    fun stop() {
        stop = true
        answer?.complete(null)
        // A brain call in flight can take a few seconds; then the runner sees STOP and wraps up.
        scope.launch {
            delay(15_000)
            if (stop && job?.isActive == true) job?.cancel()
        }
    }

    /** Rex's answer to the question on the Task screen. */
    fun answer(text: String) {
        answer?.complete(text)
    }

    fun reset() {
        if (_state.value.phase == Phase.DONE || _state.value.phase == Phase.IDLE) _state.value = State()
    }

    /** A log line with the time since the task started ("1:05 ▶ Step 2/3…"), so slow parts show. */
    private fun progress(line: String) {
        val s = _state.value
        val t = ((System.currentTimeMillis() - s.startedAt) / 1000).coerceAtLeast(0)
        _state.value = s.copy(log = (s.log + "${t / 60}:${"%02d".format(t % 60)} $line").takeLast(MAX_LOG))
    }

    @Suppress("DEPRECATION") // SCREEN_BRIGHT + ACQUIRE_CAUSES_WAKEUP still work on Android 13 and keep the screen on
    private fun keepAwake(context: Context, on: Boolean) {
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        wake = null
        if (!on) return
        wake = runCatching {
            context.getSystemService(PowerManager::class.java).newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "xarvis:task",
            ).apply { acquire(4 * 3_600_000L) }
        }.getOrNull()
    }

    // ---- recent tasks (for the Task screen) ----

    fun recent(context: Context): List<JSONObject> = runCatching {
        val arr = JSONArray(File(context.filesDir, "tasks/history.json").readText())
        (0 until arr.length()).map { arr.getJSONObject(it) }
    }.getOrDefault(emptyList())

    private fun remember(context: Context, goal: String, plan: Plan, finished: Boolean, summary: String) {
        runCatching {
            val f = File(context.filesDir, "tasks/history.json").apply { parentFile?.mkdirs() }
            val list = recent(context).toMutableList()
            list.add(0, JSONObject().put("goal", goal).put("title", plan.title).put("finished", finished).put("summary", summary.take(1500)).put("at", System.currentTimeMillis()))
            f.writeText(JSONArray(list.take(10)).toString())
        }
    }

    // ---- notifications ----

    private fun notify(context: Context, title: String, text: String, high: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = if (high) CHANNEL_ASK else CHANNEL
        manager.createNotificationChannel(
            NotificationChannel(channel, if (high) "Task mode: needs you" else "Task mode", if (high) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            context, 7301, Intent(context, com.xarvis.ai.TaskActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            if (high) ID_ASK else ID,
            NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_stat_xarvis)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(high)
                .setOngoing(!high)
                .setPriority(if (high) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
                .build(),
        )
    }

    private fun clearNotice(context: Context, id: Int) = context.getSystemService(NotificationManager::class.java)?.cancel(id)

    /** The phone and the brain for [TaskRunner], on this phone through XARVIS Hands. */
    private class PhoneEnv(private val app: XarvisApp, private val project: String) : TaskRunner.Env {
        private val core = app.core
        private var width = 0
        private var height = 0

        private fun hands(): HandsBridge.Hands = HandsBridge.service
            ?: throw IllegalStateException("XARVIS Hands was switched off")

        override suspend fun screen(): PhoneAgent.Screen = withContext(Dispatchers.IO) {
            // Locked: nothing can be done until Rex unlocks it.
            var asked = false
            while (app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true && !stopped()) {
                if (!asked) {
                    notify(app, "🔒 Unlock your phone", "XARVIS's task is waiting: unlock the phone so it can carry on.", high = true)
                    progress("🔒 Waiting for you to unlock the phone…")
                    asked = true
                }
                delay(2_000)
            }
            screenFrom(hands().screenElements()).also { width = it.width; height = it.height }
        }

        override suspend fun screenshot(screen: PhoneAgent.Screen): String? {
            val shot = withTimeoutOrNull(4_000) {
                suspendCancellableCoroutine<Bitmap?> { cont -> hands().screenshot { b -> if (cont.isActive) cont.resume(b) } }
            } ?: return null
            return withContext(Dispatchers.Default) { marked(shot, screen) }
        }

        /** The screenshot, smaller, with each element's number drawn where it is (so the brain can point at it). */
        private fun marked(shot: Bitmap, screen: PhoneAgent.Screen): String {
            val scale = minOf(1f, SHOT_SIDE.toFloat() / maxOf(shot.width, shot.height))
            val bmp = Bitmap.createScaledBitmap(shot, (shot.width * scale).toInt().coerceAtLeast(1), (shot.height * scale).toInt().coerceAtLeast(1), true)
                .copy(Bitmap.Config.ARGB_8888, true)
            val sx = bmp.width.toFloat() / (screen.width.takeIf { it > 0 } ?: shot.width)
            val sy = bmp.height.toFloat() / (screen.height.takeIf { it > 0 } ?: shot.height)
            val c = Canvas(bmp)
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 20f; isFakeBoldText = true }
            val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(215, 211, 47, 47) }
            for (e in screen.elements.take(60)) {
                val label = e.n.toString()
                val w = text.measureText(label) + 10
                val x = e.x * sx - w / 2
                val y = e.y * sy - 13
                c.drawRoundRect(RectF(x, y, x + w, y + 26), 6f, 6f, box)
                c.drawText(label, x + 5, y + 20, text)
            }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 65, out)
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }

        override suspend fun tap(x: Int, y: Int, long: Boolean) = withContext(Dispatchers.Main) {
            hands().gesture(if (long) "long" else "tap", x.toFloat(), y.toFloat(), x.toFloat(), y.toFloat(), 60).let { }
        }

        override suspend fun type(text: String): Boolean = withContext(Dispatchers.Main) { hands().typeText(text, false) }

        override suspend fun swipe(direction: String) = withContext(Dispatchers.Main) {
            val w = width.takeIf { it > 0 } ?: app.resources.displayMetrics.widthPixels
            val h = height.takeIf { it > 0 } ?: app.resources.displayMetrics.heightPixels
            val (x1, y1, x2, y2) = when (direction) {
                "up" -> listOf(w / 2f, h * 0.30f, w / 2f, h * 0.72f)
                "left" -> listOf(w * 0.80f, h / 2f, w * 0.20f, h / 2f)
                "right" -> listOf(w * 0.20f, h / 2f, w * 0.80f, h / 2f)
                else -> listOf(w / 2f, h * 0.72f, w / 2f, h * 0.30f)
            }
            hands().gesture("swipe", x1, y1, x2, y2, 350).let { }
        }

        override suspend fun key(name: String) = withContext(Dispatchers.Main) { hands().globalAction(name).let { } }

        override suspend fun open(app: String): String = withContext(Dispatchers.Main) {
            if (app.equals("xarvis", true)) return@withContext "XARVIS writes text itself (SAVE); no app to open"
            val found = com.xarvis.ai.device.DeviceCapabilityManager(this@PhoneEnv.app).findApp(app)
                ?: return@withContext "there's no app called \"$app\" on this phone (try its website with URL)"
            if (hands().openApp(found.packageName)) "opened ${found.label}" else "couldn't open ${found.label}"
        }

        override suspend fun openUrl(url: String): String = withContext(Dispatchers.Main) {
            if (hands().openUrl(url)) "opened in the browser" else "couldn't open it"
        }

        override suspend fun save(name: String, text: String): String {
            val folder = "Projects/" + TaskText.safeName(project).removeSuffix(".txt")
            val saved = com.xarvis.ai.files.FileStore(app).saveBytes(name, "text/plain", text.toByteArray(), subfolder = folder)
            return "Downloads/XARVIS/$folder/${saved.name}"
        }

        override suspend fun think(system: String, prompt: String, imageB64: String?): String {
            try {
                return if (imageB64 != null) core.generateCodeWithImage(system, prompt, imageB64, "image/jpeg") else core.generateCode(system, prompt)
            } catch (e: QuotaReached) {
                // Gemini's free limit: the backup brains can still work from the screen's text alone.
                if (imageB64 != null) {
                    runCatching { return core.generateCode(system, prompt) }
                }
                throw TaskRunner.BrainBusy(e.message ?: "free limit")
            }
        }

        override suspend fun ask(question: String, yesNo: Boolean): String? {
            val d = CompletableDeferred<String?>()
            answer = d
            _state.value = _state.value.copy(phase = Phase.WAITING, question = question, yesNo = yesNo)
            notify(app, "🧠 XARVIS needs you", question, high = true)
            report("❓ $question\nAnswer from here: \"answer: …\" (or \"answer: skip\", or \"stop the task\").")
            val a = d.await()
            answer = null
            clearNotice(app, ID_ASK)
            _state.value = _state.value.copy(phase = Phase.RUNNING, question = null)
            return a
        }

        override fun progress(line: String) {
            TaskManager.progress(line)
            notify(app, "🧠 XARVIS is working: $project", line.take(120), high = false)
            // The phone that asked hears the milestones (not every tap).
            if (MILESTONE.containsMatchIn(line)) report(line.take(300))
        }

        override fun stopped(): Boolean = stop

        override suspend fun pause(ms: Long) = delay(ms)

        override fun now(): Long = System.currentTimeMillis()
    }

    private const val CHANNEL = "xarvis_task"
    private const val CHANNEL_ASK = "xarvis_task_ask"
    private const val ID = 7300
    private const val ID_ASK = 7302
    private const val MAX_LOG = 300
    /** The screenshot sent to the brain: smaller is quicker to send and read (one image tile instead of two). */
    private const val SHOT_SIDE = 768
    /** Progress lines worth sending to the phone that asked: steps starting, being stuck, a new plan, waiting to unlock. */
    private val MILESTONE = Regex("""^(?:▶|⚠|🔁|🔒|📝)""")
}
