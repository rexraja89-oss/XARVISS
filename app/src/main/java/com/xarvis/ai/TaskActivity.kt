package com.xarvis.ai

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.xarvis.ai.task.TaskManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ☰ → 🧠 Task mode (Rex, Nov 2026: complex tasks "from planning to execution" — YouTube videos, comics with free AI
 * tools). Write the goal → ▶ Do it: XARVIS plans and does it straight away (the task is the permission), asking only
 * for what only Rex can do (a password, a captcha); STOP any time. Shows the practice ladder and what it learned.
 */
class TaskActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var body: LinearLayout
    private lateinit var goal: EditText
    private lateinit var logView: TextView
    private lateinit var status: TextView
    private var shown: Pair<TaskManager.Phase, String?>? = null

    private val teal = Color.parseColor("#0B6E8C")
    private val grey = Color.parseColor("#5F6B73")
    private val red = Color.parseColor("#C62828")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra(EXTRA_STOP, false) == true) TaskManager.stop()
        val pad = dp(16)
        goal = EditText(this).apply {
            hint = "e.g. Make a 45-second YouTube Short about 5 surprising facts on Mars: AI pictures, an AI voice, edit in CapCut, upload to YT Studio as private."
            minLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        logView = label("", Color.BLACK, 12f).apply { typeface = Typeface.MONOSPACE }
        status = label("", teal, 15f, bold = true)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(TextView(this@TaskActivity).apply { text = "🧠 Task mode"; textSize = 24f; setTextColor(teal) })
            addView(
                label(
                    "Give XARVIS a job, from small (\"open Ninja VPN and connect\") to big (a YouTube Short). It plans it and does " +
                        "it on this phone by itself — your task is the permission, so it doesn't ask along the way. It comes to " +
                        "you only for what only you can do (a password, a captcha). It never pays, types passwords or opens " +
                        "banking apps. STOP any time (here, in the notification, or the ✱ at the top). From the benco: " +
                        "\"task on my S22: …\".",
                ),
            )
            addView(body)
        }
        setContentView(ScrollView(this).apply { addView(col) })
        scope.launch { TaskManager.state.collect { render(it) } }
        scope.launch {
            while (isActive) {
                delay(1_000)
                val s = TaskManager.state.value
                if (s.phase == TaskManager.Phase.RUNNING || s.phase == TaskManager.Phase.WAITING) status.text = runningLine(s)
            }
        }
    }

    private fun render(s: TaskManager.State) {
        logView.text = s.log.takeLast(60).joinToString("\n")
        val key = s.phase to (s.question ?: s.error ?: s.plan?.title)
        if (key == shown) return
        shown = key
        body.removeAllViews()
        (goal.parent as? LinearLayout)?.removeView(goal)
        (logView.parent as? LinearLayout)?.removeView(logView)
        (status.parent as? LinearLayout)?.removeView(status)
        when (s.phase) {
            TaskManager.Phase.IDLE -> idle(s)
            TaskManager.Phase.PLANNING -> {
                body.addView(label("🧠 Planning: ${s.goal.take(200)}" + (s.from?.let { "\n(asked from $it)" } ?: ""), Color.BLACK, 15f))
                body.addView(ProgressBar(this))
                body.addView(button("⏹ Stop") { TaskManager.stop() })
            }
            TaskManager.Phase.RUNNING, TaskManager.Phase.WAITING -> running(s)
            TaskManager.Phase.DONE -> {
                body.addView(label("RESULT", teal, 13f, bold = true))
                body.addView(label(s.result, Color.BLACK, 15f))
                body.addView(button("➕ New task") { TaskManager.reset() })
                body.addView(label("WHAT HAPPENED", teal, 13f, bold = true))
                body.addView(logView)
            }
        }
    }

    private fun idle(s: TaskManager.State) {
        TaskManager.notReady(this)?.let { body.addView(label("⚠ $it", red, 14f)) }
        s.error?.let { body.addView(label("⚠ $it", red, 14f)) }
        body.addView(label("YOUR GOAL", teal, 13f, bold = true))
        if (goal.text.isEmpty() && s.goal.isNotEmpty()) goal.setText(s.goal)
        body.addView(goal)
        body.addView(
            button("▶ Do it") {
                val g = goal.text.toString().trim()
                if (g.length < 8) {
                    goal.error = "Say what you want done (a sentence or two)."
                } else {
                    TaskManager.start(this, g)?.let { why -> goal.error = why }
                }
            },
        )
        practice()
        val book = TaskManager.lessons(this).all()
        body.addView(label("WHAT I'VE LEARNED (used in every plan; tap ✕ to forget one)", teal, 13f, bold = true))
        if (book.isEmpty()) body.addView(label("Nothing yet — I write things down as I work.", grey, 13f))
        book.forEach { (app, list) ->
            list.asReversed().forEach { lesson ->
                body.addView(
                    LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(label("$app: $lesson", Color.BLACK, 13f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                        addView(
                            Button(this@TaskActivity).apply {
                                text = "✕"; setOnClickListener {
                                    TaskManager.lessons(this@TaskActivity).remove(app, lesson)
                                    shown = null
                                    render(TaskManager.state.value)
                                }
                            },
                        )
                    },
                )
            }
        }
        val recent = TaskManager.recent(this)
        if (recent.isNotEmpty()) {
            body.addView(label("RECENT TASKS", teal, 13f, bold = true))
            recent.forEach { r ->
                body.addView(
                    label(
                        (if (r.optBoolean("finished")) "✓ " else "⏹ ") + r.optString("title") + " — " +
                            r.optString("summary").lineSequence().first().take(140),
                        Color.BLACK, 13f,
                    ),
                )
            }
        }
    }

    /** The practice ladder: small jobs first, bigger ones as the small ones pass. */
    private fun practice() {
        val scores = com.xarvis.ai.task.Practice.scores(this)
        val open = com.xarvis.ai.task.Practice.openLevel(scores)
        body.addView(label("🎓 PRACTICE (level $open open — a job passes after ${com.xarvis.ai.task.Practice.PASSES} clean runs)", teal, 13f, bold = true))
        com.xarvis.ai.task.Practice.ladder.forEach { d ->
            val sc = scores[d.id]
            val mark = when {
                sc?.passed == true -> "✅"
                d.level > open -> "🔒"
                else -> "▶"
            }
            val line = "$mark L${d.level}: ${d.goal.take(90)}${if (d.goal.length > 90) "…" else ""}" +
                (sc?.takeIf { it.runs > 0 }?.let { "  (${it.clean}/${com.xarvis.ai.task.Practice.PASSES} clean, ${it.runs} runs)" } ?: "")
            if (d.level > open) {
                body.addView(label(line, grey, 13f))
            } else {
                body.addView(
                    Button(this).apply {
                        text = line; isAllCaps = false; textSize = 13f
                        setOnClickListener { TaskManager.start(this@TaskActivity, d.goal, practice = d.id)?.let { why -> goal.error = why } }
                    },
                )
            }
        }
    }

    private fun running(s: TaskManager.State) {
        status.text = runningLine(s)
        body.addView(status)
        s.from?.let { body.addView(label("Asked from $it — it gets the progress and the result.", grey, 13f)) }
        val q = s.question
        if (s.phase == TaskManager.Phase.WAITING && q != null) {
            body.addView(label("❓ XARVIS asks:", red, 15f, bold = true))
            body.addView(label(q, Color.BLACK, 16f))
            if (s.yesNo) {
                body.addView(
                    LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(button("Yes") { reply("yes") })
                        addView(button("No") { reply("no") })
                    },
                )
            } else {
                val box = EditText(this).apply { hint = "Your answer (e.g. \"I've signed in\", \"use the second style\")" }
                body.addView(box)
                body.addView(button("Send") { box.text.toString().trim().takeIf { it.isNotEmpty() }?.let { reply(it) } })
                body.addView(
                    LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(button("Skip this step") { reply("skip") })
                        addView(button("Stop the task") { reply("stop") })
                    },
                )
            }
        }
        body.addView(
            Button(this).apply {
                text = "⏹ STOP"; textSize = 18f; isAllCaps = false; setTextColor(Color.WHITE); setBackgroundColor(red)
                setOnClickListener { TaskManager.stop() }
            },
        )
        body.addView(label("WHAT XARVIS IS DOING", teal, 13f, bold = true))
        body.addView(logView)
    }

    private fun reply(text: String) {
        TaskManager.answer(text)
        // Back to the app XARVIS was working in.
        if (text != "stop") moveTaskToBack(true)
    }

    private fun runningLine(s: TaskManager.State): String {
        val minutes = (System.currentTimeMillis() - s.startedAt) / 60_000
        val title = s.plan?.title ?: "task"
        return (if (s.phase == TaskManager.Phase.WAITING) "⏸ Waiting for you" else "🧠 Working") + " · $title · $minutes min"
    }

    private fun label(t: String, color: Int = grey, size: Float = 13f, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD); setPadding(0, dp(8), 0, dp(2))
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { onClick() } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_STOP = "stop"
    }
}
