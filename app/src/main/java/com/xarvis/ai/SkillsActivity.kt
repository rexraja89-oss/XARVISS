package com.xarvis.ai

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.xarvis.ai.task.NightShift
import com.xarvis.ai.task.NightShiftRunner
import com.xarvis.ai.task.Skill
import com.xarvis.ai.task.SkillStep
import com.xarvis.ai.task.Skills
import com.xarvis.ai.task.TaskManager
import com.xarvis.ai.task.Teacher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 🎓 Skills ("show me once") and 🌙 the Night Shift (Task mode → 🎓 Skills & 🌙 Night shift). Teach: XARVIS goes Home and
 * watches while Rex does a job once; ✅ Done (here or in the notification) shows what it understood, Rex names it and
 * saves it. Skills run with ▶ (or "do my <name>", also from the benco) and can be night-shift jobs.
 */
class SkillsActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var body: LinearLayout
    private var lastStatus: Teacher.Status? = null

    private val teal = Color.parseColor("#0B6E8C")
    private val grey = Color.parseColor("#5F6B73")
    private val red = Color.parseColor("#C62828")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(TextView(this@SkillsActivity).apply { text = "🎓 Skills & 🌙 Night shift"; textSize = 24f; setTextColor(teal) })
            addView(body)
        }
        setContentView(ScrollView(this).apply { addView(col) })
        handle(intent)
        scope.launch {
            Teacher.status.collect { s ->
                val prev = lastStatus
                lastStatus = s
                if (prev == null || prev.recording != s.recording || (s.recording && prev.taps != s.taps)) render()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun handle(intent: Intent?) {
        val action = intent?.getStringExtra(EXTRA_TEACH) ?: return render()
        intent.removeExtra(EXTRA_TEACH)
        when (action) {
            TEACH_DONE -> if (Teacher.finish(this) == null) toast("I didn't see any steps to keep — try again, doing the job from the home screen.")
            TEACH_CANCEL -> Teacher.cancel(this)
        }
        render()
    }

    private fun render() {
        if (!::body.isInitialized) return
        body.removeAllViews()
        teach()
        skills()
        night()
    }

    // ---- Teach ----

    private fun teach() {
        val s = Teacher.status.value
        body.addView(heading("🔴 TEACH ME A JOB"))
        if (s.recording) {
            body.addView(
                label(
                    "🔴 Watching… ${s.taps} tap${if (s.taps == 1) "" else "s"}" + (s.apps.takeIf { it.isNotEmpty() }?.joinToString(", ", " in ") ?: "") +
                        ".\nDo the job in the other apps, then come back here (or pull down the notification) and tap ✅ Done.",
                    Color.BLACK, 15f,
                ),
            )
            body.addView(button("✅ Done — show me what you learned") {
                if (Teacher.finish(this) == null) toast("I didn't see any steps to keep — try again, doing the job from the home screen.")
                render()
            })
            body.addView(button("✖ Cancel") { Teacher.cancel(this); render() })
            return
        }
        val draft = Teacher.draft
        if (draft != null) {
            review(draft)
            return
        }
        body.addView(
            label(
                "Tap Teach, and XARVIS goes to the home screen and watches. Do the job once, your way — open the app, tap, " +
                    "type, wait for the ad's ✕. Then tap ✅ Done in the notification. Next time \"do my <name>\" replays it in " +
                    "seconds, finding the buttons by their names and waiting for what you waited for. Passwords and banking " +
                    "apps are never recorded.",
            ),
        )
        body.addView(button("🔴 Teach a new skill") {
            Teacher.start(this)?.let { why -> toast(why) }
        })
    }

    private fun review(draft: com.xarvis.ai.task.Teach.Draft) {
        body.addView(label("Here's what I saw. Name it and save it.", Color.BLACK, 15f, bold = true))
        val name = EditText(this).apply {
            hint = "Name (e.g. VPN) — then say \"do my VPN\""
            setText(draft.steps.filterIsInstance<SkillStep.Open>().firstOrNull()?.app.orEmpty())
        }
        body.addView(name)
        val changing = HashMap<Int, CheckBox>()
        draft.steps.forEachIndexed { i, step ->
            body.addView(label("${i + 1}. ${step.describe()}", Color.BLACK, 14f))
            if (step is SkillStep.Type) {
                val box = CheckBox(this).apply { text = "This text changes each time (say it after a colon: \"do my ${name.text.ifEmpty { "X" }}: …\")" }
                changing[i] = box
                body.addView(box)
            }
        }
        draft.notes.forEach { body.addView(label(it, grey, 13f)) }
        body.addView(button("💾 Save this skill") {
            val n = name.text.toString().trim().removePrefix("my ").trim()
            if (n.length < 2) {
                name.error = "Give it a short name"
                return@button
            }
            var count = 0
            val steps = draft.steps.mapIndexed { i, st ->
                if (st is SkillStep.Type && changing[i]?.isChecked == true) st.copy(variable = if (count++ == 0) "text" else "text$count") else st
            }
            val existing = TaskManager.skills(this).all().any { com.xarvis.ai.task.SkillBook.key(it.name) == com.xarvis.ai.task.SkillBook.key(n) }
            val save = {
                TaskManager.skills(this).save(Skill(n, steps, System.currentTimeMillis()))
                Teacher.draft = null
                toast("Saved. Say \"do my $n\" — here or from the benco.")
                render()
            }
            if (existing) {
                AlertDialog.Builder(this).setMessage("You already have a skill called \"$n\". Replace it?")
                    .setPositiveButton("Replace") { _, _ -> save() }.setNegativeButton("Cancel", null).show()
            } else {
                save()
            }
        })
        body.addView(button("🗑 Throw it away") { Teacher.draft = null; render() })
    }

    // ---- Skills ----

    private fun skills() {
        val all = TaskManager.skills(this).all()
        body.addView(heading("🎓 YOUR SKILLS"))
        if (all.isEmpty()) {
            body.addView(label("None yet. Your first one could be Ninja VPN: open it, Connect, close the ad."))
            return
        }
        all.forEach { s ->
            body.addView(
                label(
                    "${s.name} — ${s.steps.size} steps" + (if (s.runs > 0) " · ${s.clean}/${s.runs} clean runs" else "") +
                        (s.lastResult.takeIf { it.isNotBlank() }?.let { "\nLast: ${it.take(120)}" } ?: ""),
                    Color.BLACK, 15f, bold = true,
                ),
            )
            body.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(button("▶ Run") { runSkill(s) })
                    addView(button("👁 Steps") {
                        AlertDialog.Builder(this@SkillsActivity).setTitle(s.name)
                            .setMessage(s.steps.mapIndexed { i, st -> "${i + 1}. ${st.describe()}" }.joinToString("\n"))
                            .setPositiveButton("OK", null).show()
                    })
                    addView(button("🗑") {
                        AlertDialog.Builder(this@SkillsActivity).setMessage("Forget \"${s.name}\"?")
                            .setPositiveButton("Forget") { _, _ -> TaskManager.skills(this@SkillsActivity).delete(s.name); render() }
                            .setNegativeButton("Keep", null).show()
                    })
                },
            )
        }
    }

    private fun runSkill(s: Skill) {
        val start = { values: Map<String, String> ->
            val why = TaskManager.runSkill(this, s, values)
            if (why != null) toast(why) else startActivity(Intent(this, TaskActivity::class.java))
        }
        if (s.variables.isEmpty()) {
            start(emptyMap())
            return
        }
        // Ask for the changing texts (the ones typed when Rex showed it are the defaults).
        val defaults = s.steps.filterIsInstance<SkillStep.Type>().filter { it.variable != null }.associate { it.variable!! to it.text }
        val boxes = s.variables.associateWith { v -> EditText(this).apply { setText(defaults[v].orEmpty()); hint = v } }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            boxes.values.forEach { addView(it) }
        }
        AlertDialog.Builder(this).setTitle("${s.name}: what this time?").setView(col)
            .setPositiveButton("▶ Run") { _, _ -> start(boxes.mapValues { it.value.text.toString().trim() }) }
            .setNegativeButton("Cancel", null).show()
    }

    // ---- Night shift ----

    private fun night() {
        body.addView(heading("🌙 NIGHT SHIFT (every night, on this phone)"))
        body.addView(
            label(
                "Jobs XARVIS does at night while you're at the plant: a skill (\"do my VPN\") or any task. The report goes to your " +
                    "other phone. Leave this phone on the charger and Wi-Fi; if it's locked at that time, XARVIS tells your benco " +
                    "and waits for you to unlock it (XARVIS Remote) — it never enters your pattern.",
            ),
        )
        val jobs = NightShiftRunner.jobs(this).sortedBy { it.hour * 60 + it.minute }
        jobs.forEach { j ->
            body.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(
                        label("${j.time()} — ${j.what}" + (j.lastResult.takeIf { it.isNotBlank() }?.let { "\nLast: ${it.take(100)}" } ?: ""), Color.BLACK, 14f),
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(Switch(this@SkillsActivity).apply {
                        isChecked = j.enabled
                        setOnCheckedChangeListener { _, on -> NightShiftRunner.setEnabled(this@SkillsActivity, j.id, on) }
                    })
                    addView(button("🗑") { NightShiftRunner.remove(this@SkillsActivity, j.id); render() })
                },
            )
        }
        body.addView(button("➕ Add a night job") { addNightJob() })
        NightShiftRunner.lastReport(this)?.let { body.addView(label("LAST NIGHT\n$it", Color.BLACK, 13f)) }
    }

    private fun addNightJob() {
        val skills = TaskManager.skills(this).all()
        val what = EditText(this).apply {
            hint = "do my VPN — or any task, e.g. \"find 3 Mars facts and save them in a note\""
            setText(skills.firstOrNull()?.let { "do my ${it.name}" }.orEmpty())
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(what)
            if (skills.isNotEmpty()) addView(label("Your skills: " + skills.joinToString(", ") { it.name }, grey, 12f))
        }
        AlertDialog.Builder(this).setTitle("What should XARVIS do at night?").setView(col)
            .setPositiveButton("Pick the time") { _, _ ->
                val w = what.text.toString().trim()
                if (w.length < 3) return@setPositiveButton
                TimePickerDialog(this, { _, h, m ->
                    val job = NightShiftRunner.add(this, w, h, m)
                    toast("🌙 ${job.time()} every night: ${job.what}")
                    render()
                }, 2, 0, true).show()
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ---- views ----

    private fun heading(t: String) = label(t, teal, 13f, bold = true).apply { setPadding(0, dp(18), 0, dp(4)) }

    private fun label(t: String, color: Int = grey, size: Float = 13f, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD); setPadding(0, dp(6), 0, dp(2))
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { onClick() } }

    private fun toast(t: String) = android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_LONG).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TEACH = "teach"
        const val TEACH_DONE = "done"
        const val TEACH_CANCEL = "cancel"
    }
}
