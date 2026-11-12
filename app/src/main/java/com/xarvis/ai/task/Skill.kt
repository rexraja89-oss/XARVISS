package com.xarvis.ai.task

import com.xarvis.ai.agent.PhoneAgent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/*
 * "Show me once" (Rex, 12 Nov 2026: "blast my mind" → teach XARVIS like an apprentice): Rex does a job once while
 * XARVIS Hands watches (Teach mode); XARVIS turns what it saw into a [Skill] — open this app, tap the button called X,
 * type Y, check that Z shows — and later replays it at machine speed ([SkillRunner]): it finds buttons by their names,
 * waits for what Rex waited for instead of guessing times, and calls the brain only for a step that looks different.
 * Skills are building blocks for Task mode's plans and for the Night Shift. Never recorded or replayed: passwords
 * (Rex types those himself), banking / payment apps, and money buttons.
 */

/** What XARVIS Hands saw while Rex showed a job. Positions are 0–1 of the screen. */
sealed interface TeachEvent {
    val at: Long
    val pkg: String
    val app: String

    /** A new window came to the front (an app opened, a dialog showed). */
    data class Window(override val at: Long, override val pkg: String, override val app: String) : TeachEvent

    data class Click(
        override val at: Long, override val pkg: String, override val app: String,
        val label: String, val id: String, val x: Float, val y: Float, val long: Boolean = false,
    ) : TeachEvent

    /** Text in a box changed ([text] is empty for a password box: it's never recorded). */
    data class Text(
        override val at: Long, override val pkg: String, override val app: String,
        val field: String, val id: String, val text: String, val password: Boolean, val x: Float = 0.5f, val y: Float = 0.5f,
    ) : TeachEvent
}

/** The labels on screen at one moment while Rex was showing the job. */
data class TeachSnap(val at: Long, val pkg: String, val app: String, val labels: List<String>)

/** A button / box as Rex used it: its label and id (how it's found again), where it was, and in which app. */
data class SkillTarget(val label: String, val id: String, val x: Float, val y: Float, val app: String) {
    fun toJson(): JSONObject = JSONObject().put("label", label).put("id", id).put("x", x.toDouble()).put("y", y.toDouble()).put("app", app)

    companion object {
        fun from(o: JSONObject) = SkillTarget(o.optString("label"), o.optString("id"), o.optDouble("x", 0.5).toFloat(), o.optDouble("y", 0.5).toFloat(), o.optString("app"))
    }
}

sealed interface SkillStep {
    data class Open(val app: String) : SkillStep

    /** [before]: the screen's labels when Rex tapped (to recognise it); [expect]: what appeared after; [waitMs]: how long to wait for it. */
    data class Tap(val target: SkillTarget, val long: Boolean, val before: List<String>, val expect: List<String>, val waitMs: Long) : SkillStep

    /** [variable]: the name of the value that changes each time ("topic"), or null for the same text every time. */
    data class Type(val target: SkillTarget, val text: String, val variable: String? = null) : SkillStep

    /** Something only Rex does (a password): the replay waits for him. */
    data class YourTurn(val what: String) : SkillStep

    /** The job is done when these show (at least half of them). */
    data class Check(val expect: List<String>, val waitMs: Long) : SkillStep

    fun describe(): String = when (this) {
        is Open -> "Open $app"
        is Tap -> (if (long) "Long-press " else "Tap ") + (target.label.takeIf { it.isNotBlank() }?.let { "“${it.take(40)}”" } ?: "a spot without a name") +
            (if (target.app.isNotBlank()) " in ${target.app}" else "")
        is Type -> "Type “${text.take(40)}”" + (variable?.let { " (changes each time: {$it})" } ?: "")
        is YourTurn -> "Your turn: $what"
        is Check -> "Check it shows " + expect.take(3).joinToString(" / ") { "“${it.take(30)}”" }
    }

    fun app(): String = when (this) {
        is Open -> app
        is Tap -> target.app
        is Type -> target.app
        else -> ""
    }

    fun toJson(): JSONObject = when (this) {
        is Open -> JSONObject().put("k", "open").put("app", app)
        is Tap -> JSONObject().put("k", "tap").put("t", target.toJson()).put("long", long).put("before", JSONArray(before))
            .put("expect", JSONArray(expect)).put("wait", waitMs)
        is Type -> JSONObject().put("k", "type").put("t", target.toJson()).put("text", text).put("var", variable ?: "")
        is YourTurn -> JSONObject().put("k", "you").put("what", what)
        is Check -> JSONObject().put("k", "check").put("expect", JSONArray(expect)).put("wait", waitMs)
    }

    companion object {
        private fun strings(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.optString(it) }.filter { it.isNotBlank() }

        fun from(o: JSONObject): SkillStep? = when (o.optString("k")) {
            "open" -> Open(o.optString("app"))
            "tap" -> Tap(SkillTarget.from(o.optJSONObject("t") ?: JSONObject()), o.optBoolean("long"), strings(o.optJSONArray("before")), strings(o.optJSONArray("expect")), o.optLong("wait", 15_000))
            "type" -> Type(SkillTarget.from(o.optJSONObject("t") ?: JSONObject()), o.optString("text"), o.optString("var").takeIf { it.isNotBlank() })
            "you" -> YourTurn(o.optString("what"))
            "check" -> Check(strings(o.optJSONArray("expect")), o.optLong("wait", 60_000))
            else -> null
        }
    }
}

data class Skill(
    val name: String,
    val steps: List<SkillStep>,
    val createdAt: Long,
    val runs: Int = 0,
    val clean: Int = 0,
    val lastRun: Long = 0,
    val lastResult: String = "",
) {
    /** The values that change each time, in the order they're typed. */
    val variables: List<String> get() = steps.filterIsInstance<SkillStep.Type>().mapNotNull { it.variable }.distinct()

    val apps: List<String> get() = steps.filterIsInstance<SkillStep.Open>().map { it.app }.distinct()

    /** One line for the planner and for Rex: "my VPN: Open Ninja VPN → Tap “Connect” → …". */
    fun summary(): String = "$name" + (variables.takeIf { it.isNotEmpty() }?.joinToString(", ", " (needs: ", ")") { "{$it}" } ?: "") + ": " +
        steps.joinToString(" → ") { it.describe() }.take(300)

    fun toJson(): JSONObject = JSONObject().put("name", name).put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })
        .put("created", createdAt).put("runs", runs).put("clean", clean).put("lastRun", lastRun).put("lastResult", lastResult)

    companion object {
        fun from(o: JSONObject): Skill? {
            val name = o.optString("name").trim().takeIf { it.isNotEmpty() } ?: return null
            val arr = o.optJSONArray("steps") ?: return null
            val steps = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { s -> SkillStep.from(s) } }
            if (steps.isEmpty()) return null
            return Skill(name, steps, o.optLong("created"), o.optInt("runs"), o.optInt("clean"), o.optLong("lastRun"), o.optString("lastResult"))
        }
    }
}

/** Turns what Rex showed (events + screen snapshots) into skill steps. Pure, unit-tested. */
object Teach {
    data class Draft(val steps: List<SkillStep>, val notes: List<String>)

    private val IGNORED = setOf("com.xarvis.ai", "com.android.systemui")
    /** System windows an app brings up (a VPN's connection request, a permission box): part of the job, never "opened". */
    private val DIALOGS = Regex(
        """^(android|com\.android\.vpndialogs|com\.(google\.)?android\.permissioncontroller|com\.(google\.)?android\.packageinstaller|com\.samsung\.android\.permissioncontroller|com\.android\.settings\.intelligence)$""",
    )
    const val MIN_WAIT_MS = 8_000L
    const val MAX_WAIT_MS = 180_000L
    const val CHECK_WAIT_MS = 60_000L

    fun launcher(pkg: String) = pkg.contains("launcher", ignoreCase = true) || pkg == "com.sec.android.app.desktoplauncher"

    private fun dialog(pkg: String) = DIALOGS.matches(pkg)

    /** How long the replay waits for a step's button: about twice what Rex took, within limits. */
    fun waitFor(gapMs: Long): Long = (gapMs * 2 + 5_000).coerceIn(MIN_WAIT_MS, MAX_WAIT_MS)

    fun compile(events: List<TeachEvent>, snaps: List<TeachSnap>): Draft {
        val steps = ArrayList<SkillStep>()
        val notes = LinkedHashSet<String>()
        val shots = snaps.filter { it.pkg !in IGNORED && !launcher(it.pkg) && !money(it) }.sortedBy { it.at }
        var current = ""
        var fromHome = true
        var lastAt = events.minOfOrNull { it.at } ?: 0L
        // Where each app's session started (for the final check: what wasn't there when Rex began in that app).
        val sessionStart = HashMap<String, Long>()
        for (e in events.sortedBy { it.at }) {
            if (e.pkg in IGNORED || e.pkg.isBlank()) continue
            if (money(e.app, e.pkg)) {
                notes += "🔒 ${e.app.ifBlank { e.pkg }}: banking and payment apps are never recorded or used."
                continue
            }
            if (launcher(e.pkg)) {
                fromHome = true
                current = e.pkg
                lastAt = e.at
                continue
            }
            if (e.pkg != current) {
                if (fromHome && !dialog(e.pkg)) {
                    steps += SkillStep.Open(e.app.ifBlank { e.pkg })
                    sessionStart[e.pkg] = e.at
                }
                if (!dialog(e.pkg)) fromHome = false
                current = e.pkg
                sessionStart.putIfAbsent(e.pkg, e.at)
            }
            when (e) {
                is TeachEvent.Window -> Unit
                is TeachEvent.Click -> {
                    val before = shots.lastOrNull { it.at <= e.at && it.pkg == e.pkg }?.labels.orEmpty()
                    val after = shots.firstOrNull { it.at > e.at }?.labels.orEmpty()
                    steps += SkillStep.Tap(
                        SkillTarget(e.label.trim(), e.id, e.x, e.y, e.app), e.long, before.take(40), fresh(before, after), waitFor(e.at - lastAt),
                    )
                }
                is TeachEvent.Text -> if (e.password) {
                    if (steps.lastOrNull() !is SkillStep.YourTurn) steps += SkillStep.YourTurn("type your password in ${e.app} (XARVIS never does)")
                    notes += "🔑 You typed a password: replays stop there and wait for you to type it."
                } else {
                    val last = steps.lastOrNull()
                    val same = last is SkillStep.Type && ((e.id.isNotEmpty() && last.target.id == e.id) || (e.id.isEmpty() && last.target.label == e.field))
                    if (same) {
                        steps[steps.lastIndex] = (last as SkillStep.Type).copy(text = e.text)
                    } else {
                        steps += SkillStep.Type(SkillTarget(e.field.trim(), e.id, e.x, e.y, e.app), e.text)
                    }
                }
            }
            lastAt = e.at
        }
        // A box cleared again isn't a step.
        steps.removeAll { it is SkillStep.Type && it.text.isBlank() }
        // The finish line: what the last screen shows that wasn't there when Rex started in that app.
        shots.lastOrNull()?.let { last ->
            val start = sessionStart[last.pkg]
            val first = shots.firstOrNull { it.pkg == last.pkg && (start == null || it.at >= start) }
            val lastTap = steps.lastOrNull { it is SkillStep.Tap } as SkillStep.Tap?
            val expect = fresh(first?.labels.orEmpty(), last.labels).ifEmpty { fresh(lastTap?.before.orEmpty(), last.labels) }
            if (expect.isNotEmpty() && steps.isNotEmpty()) steps += SkillStep.Check(expect, CHECK_WAIT_MS)
        }
        return Draft(steps, notes.toList())
    }

    /** Labels in [after] that weren't in [before], keeping ones that say something (not clocks, counters or ✕). */
    fun fresh(before: List<String>, after: List<String>): List<String> {
        val had = before.map { SkillMatch.norm(it) }.toSet()
        return after.map { it.trim() }.filter { l ->
            val n = SkillMatch.norm(l)
            n.isNotEmpty() && n !in had && l.length in 2..30 && !NUMBERISH.matches(l) && !TaskRunner.CLOSE.matches(l)
        }.distinct().take(4)
    }

    private val NUMBERISH = Regex("""^[\d\s:.,%/+\-]+[a-zA-Z]{0,3}$""")

    private fun money(s: TeachSnap) = TaskRunner.Safety.moneyApp(s.app) || TaskRunner.Safety.moneyApp(s.pkg)
    private fun money(app: String, pkg: String) = TaskRunner.Safety.moneyApp(app) || TaskRunner.Safety.moneyApp(pkg)
}

/** Finding a recorded button on today's screen. Pure, unit-tested. */
object SkillMatch {
    fun norm(label: String) = label.trim().lowercase().replace(Regex("""\s+"""), " ")

    fun find(screen: PhoneAgent.Screen, t: SkillTarget, editable: Boolean = false): PhoneAgent.Element? {
        val els = if (editable) screen.elements.filter { it.editable } else screen.elements
        if (els.isEmpty()) return null
        val label = norm(t.label)
        if (label.isNotEmpty()) {
            els.filter { norm(it.label) == label }.nearest(screen, t)?.let { return it }
            // An ad's close button has many names: any of them will do.
            if (TaskRunner.CLOSE.matches(t.label.trim())) els.filter { TaskRunner.CLOSE.matches(it.label.trim()) }.nearest(screen, t)?.let { return it }
            // The same words with a little more or less ("Connect" / "Connect now").
            if (label.length >= 4) {
                els.filter {
                    val n = norm(it.label)
                    n.length >= 4 && (n.contains(label) || label.contains(n)) && kotlin.math.abs(n.length - label.length) <= 12
                }.nearest(screen, t)?.let { return it }
            }
        }
        if (t.id.isNotBlank()) els.filter { it.id == t.id }.nearest(screen, t)?.let { return it }
        if (editable && label.isEmpty() && t.id.isBlank()) return els.nearest(screen, t)
        return null
    }

    /** How alike two screens are by their labels (0–1). */
    fun similarity(recorded: Collection<String>, screen: PhoneAgent.Screen): Double {
        val a = recorded.map { norm(it) }.filter { it.isNotEmpty() }.toSet()
        val b = screen.elements.map { norm(it.label) }.filter { it.isNotEmpty() }.toSet()
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / a.union(b).size
    }

    /** How many of [expect] are on [screen]. */
    fun seen(expect: List<String>, screen: PhoneAgent.Screen): Int {
        val on = screen.elements.map { norm(it.label) }
        return expect.count { e -> val n = norm(e); on.any { it == n || (n.length >= 4 && it.contains(n)) } }
    }

    private fun List<PhoneAgent.Element>.nearest(screen: PhoneAgent.Screen, t: SkillTarget): PhoneAgent.Element? {
        if (isEmpty()) return null
        if (size == 1) return first()
        val w = screen.width.coerceAtLeast(1).toFloat()
        val h = screen.height.coerceAtLeast(1).toFloat()
        return minBy { e ->
            val dx = e.x / w - t.x
            val dy = e.y / h - t.y
            (if (t.id.isNotBlank() && e.id == t.id) 0f else 1f) + dx * dx + dy * dy
        }
    }
}

/**
 * Replays a [Skill] on the phone through Task mode's [TaskRunner.Env]: no brain calls for steps that look like when
 * Rex showed them; a step that doesn't is handed to the brain alone (when [brainHelp]), then the replay carries on.
 */
class SkillRunner(
    private val env: TaskRunner.Env,
    private val lessons: LessonBook,
    private val brainHelp: Boolean = true,
    private val settleMs: Long = 700,
    private val pollMs: Long = 600,
) {
    data class Result(val ok: Boolean, val summary: String, val stepsDone: Int, val helped: Int)

    private sealed interface Went {
        data object Ok : Went
        data class Problem(val why: String) : Went
        data class Stop(val why: String) : Went
    }

    val log = ArrayList<String>()

    suspend fun run(skill: Skill, values: Map<String, String> = emptyMap()): Result {
        val started = env.now()
        var helped = 0
        say("🎓 Skill “${skill.name}”: ${skill.steps.size} steps")
        for ((i, step) in skill.steps.withIndex()) {
            if (env.stopped()) return Result(false, "Stopped — you pressed STOP.", i, helped)
            say("▶ ${i + 1}/${skill.steps.size} ${step.describe()}")
            when (val went = runStep(step, values)) {
                Went.Ok -> Unit
                is Went.Stop -> return Result(false, "Stopped at step ${i + 1} (${step.describe()}): ${went.why}", i, helped)
                is Went.Problem -> {
                    if (!brainHelp) return Result(false, "Step ${i + 1} (${step.describe()}): ${went.why}", i, helped)
                    say("🧠 Step ${i + 1} looks different from when you showed me (${went.why}) — the brain does this one step")
                    val how = "Rex showed this step himself: ${step.describe()}." +
                        ((step as? SkillStep.Tap)?.expect?.takeIf { it.isNotEmpty() }?.let { " Afterwards the screen showed ${it.joinToString(", ") { s -> "“$s”" }}." } ?: "")
                    val done = when (step) {
                        is SkillStep.Check -> "the screen shows " + step.expect.joinToString(" or ") { "“$it”" }
                        is SkillStep.Tap -> step.expect.takeIf { it.isNotEmpty() }?.joinToString(" or ", "the screen shows ") { "“$it”" } ?: "the step is done"
                        else -> "the step is done"
                    }
                    val helper = TaskRunner(env, lessons, TaskRunner.Limits(maxMinutes = 10, actionsPerStep = 15, replans = 0, askWhenStuck = false))
                    val out = helper.run(
                        "Skill “${skill.name}”, step ${i + 1}: ${step.describe()}",
                        Plan(skill.name, listOf(PlanStep(step.describe(), step.app(), how, done))),
                    )
                    if (!out.finished) return Result(false, "Step ${i + 1} (${step.describe()}): ${went.why}; the brain couldn't do it either (${out.summary.take(160)})", i, helped + 1)
                    helped++
                }
            }
        }
        val secs = ((env.now() - started) / 1000).coerceAtLeast(0)
        val time = "${secs / 60}:${"%02d".format(secs % 60)}"
        return Result(true, "“${skill.name}” done in $time" + if (helped > 0) " (the brain helped with $helped step${if (helped == 1) "" else "s"})." else ".", skill.steps.size, helped)
    }

    private suspend fun runStep(step: SkillStep, values: Map<String, String>): Went = when (step) {
        is SkillStep.Open -> open(step)
        is SkillStep.Tap -> tap(step)
        is SkillStep.Type -> type(step, values)
        is SkillStep.YourTurn -> {
            val a = env.ask("Your turn: ${step.what}. Reply \"done\" when it's done (or \"stop\").", yesNo = false)
            when {
                a == null || a.trim().equals("stop", true) -> Went.Stop("you stopped it")
                else -> Went.Ok
            }
        }
        is SkillStep.Check -> check(step)
    }

    private suspend fun open(step: SkillStep.Open): Went {
        if (TaskRunner.Safety.moneyApp(step.app)) return Went.Stop("XARVIS never opens banking or payment apps")
        val now = screen() ?: return Went.Stop("stopped")
        if (now.app.isNotBlank() && (now.app.equals(step.app, true) || now.app.contains(step.app, true) || step.app.contains(now.app, true))) {
            return Went.Ok
        }
        val r = env.open(step.app)
        if (!r.startsWith("opened")) return Went.Stop(r)
        say("📱 $r")
        env.pause(settleMs)
        return Went.Ok
    }

    private suspend fun tap(step: SkillStep.Tap): Went {
        val t = step.target
        val deadline = env.now() + step.waitMs
        var scrolled = false
        var waiting = false
        while (true) {
            val s = screen() ?: return Went.Stop("stopped")
            if (TaskRunner.Safety.moneyApp(s.app)) {
                env.key("home")
                return Went.Stop("${s.app} (a banking or payment app) came up — XARVIS went Home and stopped")
            }
            val el = SkillMatch.find(s, t)
            if (el != null) {
                if (TaskRunner.Safety.check(el.label, "") == PhoneAgent.Safety.Verdict.NEVER) {
                    return Went.Stop("“${el.label.take(40)}” is about money, a password or a subscription — never tapped")
                }
                say("👆 ${if (step.long) "Long-pressing" else "Tapping"} “${el.label.take(50)}”")
                env.tap(el.x, el.y, step.long)
                env.pause(settleMs)
                return Went.Ok
            }
            // A button without a name: tapped where Rex tapped it, only on the same-looking screen of the same app, and
            // never next to a money button.
            if (t.label.isBlank() && t.id.isBlank() && s.width > 0 && sameApp(s.app, t.app) && SkillMatch.similarity(step.before, s) >= SAME_SCREEN) {
                val x = (t.x * (s.width - 1)).toInt()
                val y = (t.y * (s.height - 1)).toInt()
                val near = s.elements.filter { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) < NEAR_PX * NEAR_PX }
                if (near.any { TaskRunner.Safety.check(it.label, "") == PhoneAgent.Safety.Verdict.NEVER }) return Went.Problem("a money button is next to where you tapped")
                say("👆 Tapping where you tapped (no name on it)")
                env.tap(x, y, step.long)
                env.pause(settleMs)
                return Went.Ok
            }
            val left = deadline - env.now()
            if (left <= 0) return Went.Problem(if (t.label.isNotBlank()) "“${t.label.take(40)}” didn't show up" else "that screen didn't come up")
            if (!waiting) {
                say("⏳ Waiting for " + (t.label.takeIf { it.isNotBlank() }?.let { "“${it.take(40)}”" } ?: "the screen you tapped on"))
                waiting = true
            }
            // Half the time gone and still not there: it may be further down the list.
            if (!scrolled && left < step.waitMs / 2 && sameApp(s.app, t.app) && t.label.isNotBlank()) {
                scrolled = true
                env.swipe("down")
                env.pause(settleMs)
                continue
            }
            env.pause(pollMs)
            if (env.stopped()) return Went.Stop("you pressed STOP")
        }
    }

    private suspend fun type(step: SkillStep.Type, values: Map<String, String>): Went {
        val text = step.variable?.let { v -> values[v] ?: values.values.firstOrNull()?.takeIf { values.size == 1 } } ?: step.text
        if (TaskRunner.Safety.secret(text)) return Went.Stop("that looks like a code or PIN — XARVIS never types those")
        val deadline = env.now() + 10_000
        while (true) {
            val s = screen() ?: return Went.Stop("stopped")
            val box = SkillMatch.find(s, step.target, editable = true) ?: s.elements.firstOrNull { it.editable && !it.password }
            if (box != null) {
                if (box.password) return Went.Stop("that's a password box — you type those yourself")
                env.tap(box.x, box.y, false)
                env.pause(400)
                say("⌨ Typing “${text.take(50)}”")
                return if (env.type(text)) {
                    env.pause(settleMs)
                    Went.Ok
                } else {
                    Went.Problem("the text box didn't take the text")
                }
            }
            if (env.now() > deadline) return Went.Problem("there was no text box")
            env.pause(pollMs)
        }
    }

    private suspend fun check(step: SkillStep.Check): Went {
        val need = (step.expect.size + 1) / 2
        val deadline = env.now() + step.waitMs
        while (true) {
            val s = screen() ?: return Went.Stop("stopped")
            if (SkillMatch.seen(step.expect, s) >= need.coerceAtLeast(1)) {
                say("✓ It shows " + step.expect.filter { SkillMatch.seen(listOf(it), s) > 0 }.joinToString(", ") { "“$it”" })
                return Went.Ok
            }
            if (env.now() > deadline) return Went.Problem("the finish screen (" + step.expect.joinToString(", ") { "“$it”" } + ") didn't show")
            env.pause(pollMs)
        }
    }

    private suspend fun screen(): PhoneAgent.Screen? = if (env.stopped()) null else env.screen()

    private fun sameApp(a: String, b: String) = a.isNotBlank() && b.isNotBlank() && (a.equals(b, true) || a.contains(b, true) || b.contains(a, true))

    private fun say(line: String) {
        log += line
        env.progress(line)
    }

    companion object {
        /** How alike the screen must be (by labels) to tap a nameless button where Rex tapped it. */
        const val SAME_SCREEN = 0.5
        private const val NEAR_PX = 90
    }
}

/** Which skill a command means, and the texts it fills in. Pure, unit-tested. */
object Skills {
    /** "do my search: dew point" → (search, {text: dew point}); also a skill whose name has "with" in it. */
    fun resolve(skills: List<Skill>, cmd: TaskCommands.Cmd.Skill): Pair<Skill, Map<String, String>>? {
        SkillBook.match(skills, cmd.name)?.let { return it to valuesFor(it, cmd.value) }
        if (cmd.raw != cmd.name) SkillBook.match(skills, cmd.raw)?.takeIf { SkillBook.key(it.name) == SkillBook.key(cmd.raw) }?.let { return it to emptyMap() }
        return null
    }

    /** "a | b" → the skill's changing texts, in order. */
    fun valuesFor(skill: Skill, value: String?): Map<String, String> {
        val parts = value?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        return skill.variables.zip(parts).toMap()
    }

    fun list(skills: List<Skill>): String =
        if (skills.isEmpty()) "No skills yet. Teach me one: ☰ → 🧠 Task mode → 🎓 Skills → 🔴 Teach, then do the job once."
        else "🎓 Your skills (say \"do my <name>\"):\n" + skills.joinToString("\n") { s ->
            "• ${s.name}" + (s.variables.takeIf { it.isNotEmpty() }?.joinToString(" | ", " — needs: ") { "{$it}" } ?: "") +
                " (${s.steps.size} steps" + (if (s.runs > 0) ", ${s.clean}/${s.runs} clean runs" else "") + ")"
        }
}

/** Rex's skills, in filesDir/tasks/skills.json. */
class SkillBook(private val file: File) {
    @Synchronized
    fun all(): List<Skill> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> Skill.from(o) } }
    }.getOrDefault(emptyList())

    @Synchronized
    fun save(skill: Skill) {
        val list = all().filterNot { key(it.name) == key(skill.name) } + skill
        write(list)
    }

    @Synchronized
    fun delete(name: String) = write(all().filterNot { key(it.name) == key(name) })

    @Synchronized
    fun recordRun(name: String, ok: Boolean, clean: Boolean, result: String, at: Long) {
        write(all().map { if (key(it.name) == key(name)) it.copy(runs = it.runs + 1, clean = it.clean + if (ok && clean) 1 else 0, lastRun = at, lastResult = result.take(300)) else it })
    }

    /** The skill Rex means: its name, or a sentence containing it ("do my VPN" → "VPN"). */
    fun find(query: String): Skill? = match(all(), query)

    private fun write(list: List<Skill>) {
        file.parentFile?.mkdirs()
        file.writeText(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())
    }

    companion object {
        /** "My VPN!" → "vpn". */
        fun key(name: String) = name.lowercase().replace(Regex("""[^\p{L}\p{N} ]"""), " ").trim().replace(Regex("""^(?:my|the|a)\s+"""), "")
            .replace(Regex("""\s+"""), " ").trim()

        fun match(skills: List<Skill>, query: String): Skill? {
            val q = key(query)
            if (q.isEmpty()) return null
            skills.firstOrNull { key(it.name) == q }?.let { return it }
            val words = q.split(' ').toSet()
            // The sentence contains the skill's whole name ("connect my ninja vpn now" → "Ninja VPN").
            skills.filter { s ->
                val k = key(s.name)
                k.isNotEmpty() && (" $q ".contains(" $k ") || k.split(' ').all { it in words })
            }.maxByOrNull { key(it.name).length }?.let { return it }
            // Part of a skill's name ("VPN" → "Ninja VPN"), when only one skill fits.
            return skills.filter { s -> words.all { w -> w in key(s.name).split(' ') } }.singleOrNull()
        }
    }
}
