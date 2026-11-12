package com.xarvis.ai.task

import com.xarvis.ai.agent.PhoneAgent

/**
 * Runs a [Plan] on the phone, step by step, for as long as it takes (up to [Limits.maxMinutes]): each turn
 * it reads the screen (text + a numbered screenshot), asks the brain for 1–3 actions, does them, and looks again.
 * A step ends with DONE; when it's stuck the rest of the plan is re-made ([Limits.replans] times), then Rex is asked.
 * It learns: NOTE lines and a look back after the task go into the [LessonBook].
 *
 * Rex's goal is his permission for every step it needs (Nov 2026: "one prompt and it should accomplish the task"), so
 * nothing asks him along the way except what only he can do (a password he types, a captcha, being stuck). Safety,
 * always (on top of what the brain is told): money and passwords are never touched and banking / payment apps are
 * never used (the action is refused and the brain must find another way); sending, posting, uploading, deleting,
 * installing and calling happen only when the goal asks for that kind of thing (else skipped, without asking); STOP
 * ends it at once.
 */
class TaskRunner(
    private val env: Env,
    private val lessons: LessonBook,
    private val limits: Limits = Limits(),
    /** Rex's taught skills by name: a plan step that names one runs it first (fast, no brain), see [Skill]. */
    private val skills: (String) -> Skill? = { null },
) {

    /** The phone and the brain, as the runner needs them (TaskManager on the S22; fakes in the tests). */
    interface Env {
        suspend fun screen(): PhoneAgent.Screen
        /** A screenshot with the [screen]'s element numbers drawn on it, as base64 JPEG; null if there isn't one. */
        suspend fun screenshot(screen: PhoneAgent.Screen): String?
        suspend fun tap(x: Int, y: Int, long: Boolean)
        /** Types into the box with the cursor; whether it took the text. */
        suspend fun type(text: String): Boolean
        suspend fun swipe(direction: String)
        suspend fun key(name: String)
        suspend fun open(app: String): String
        suspend fun openUrl(url: String): String
        /** Saves a text file in the task's project folder; says where. */
        suspend fun save(name: String, text: String): String
        /** The brain (with the screenshot when [imageB64] isn't null). Throws [BrainBusy] when its free limit is reached. */
        suspend fun think(system: String, prompt: String, imageB64: String?): String
        /** Asks Rex and waits for his answer ("yes"/"no" when [yesNo]); null when the task was stopped meanwhile. */
        suspend fun ask(question: String, yesNo: Boolean): String?
        fun progress(line: String)
        fun stopped(): Boolean
        suspend fun pause(ms: Long)
        fun now(): Long
    }

    class BrainBusy(message: String) : Exception(message)

    data class Limits(
        val maxMinutes: Int = 180,
        val actionsPerStep: Int = 45,
        val replans: Int = 3,
        val settleMs: Long = 1_500,
        /** Out of new plans: ask Rex (a normal task) or just end (one step a skill handed over). */
        val askWhenStuck: Boolean = true,
    )

    /** How it went; [replans] and [asked] (times Rex had to answer) tell a clean run from a struggle (practice). */
    data class Outcome(val finished: Boolean, val summary: String, val stepsDone: Int, val plan: Plan, val replans: Int = 0, val asked: Int = 0)

    private sealed interface StepEnd {
        data class Done(val result: String) : StepEnd
        data class Stuck(val why: String) : StepEnd
        data class Ended(val why: String) : StepEnd
    }

    /** Everything that happened, for Rex's screen and for the look back. */
    val log = ArrayList<String>()
    private val guidance = ArrayList<String>()
    private var startedAt = 0L
    /** Rex's goal: what decides whether a Send / Upload / Install button is part of the task. */
    private var goalText = ""
    private var asked = 0

    suspend fun run(goal: String, approved: Plan): Outcome {
        startedAt = env.now()
        goalText = goal
        var plan = approved
        val results = ArrayList<String>()
        var index = 0
        var replans = 0
        var outcome: Outcome? = null
        while (index < plan.steps.size && outcome == null) {
            val step = plan.steps[index]
            say("▶ Step ${index + 1}/${plan.steps.size}: ${step.title}")
            when (val end = runStep(goal, plan, index, results)) {
                is StepEnd.Done -> {
                    results += "Step ${index + 1} (${step.title}): ${end.result.ifBlank { "done" }}"
                    say("✓ Step ${index + 1} done" + if (end.result.isNotBlank()) ": ${end.result.take(160)}" else "")
                    index++
                }
                is StepEnd.Ended -> outcome = Outcome(false, end.why, index, plan)
                is StepEnd.Stuck -> {
                    say("⚠ Stuck on step ${index + 1}: ${end.why}")
                    val newPlan = if (replans < limits.replans) {
                        replans++
                        replan(goal, plan, index, results, end.why)
                    } else {
                        null
                    }
                    if (newPlan != null) {
                        plan = Plan(plan.title, plan.steps.take(index) + newPlan.steps)
                        say("🔁 New plan from step ${index + 1}:\n" + newPlan.steps.joinToString("\n") { "· ${it.title}" })
                    } else if (!limits.askWhenStuck) {
                        outcome = Outcome(false, "Stuck at step ${index + 1}: ${end.why}", index, plan)
                    } else {
                        // Out of ideas: Rex decides.
                        asked++
                        val answer = env.ask(
                            "I'm stuck on step ${index + 1} (${step.title}): ${end.why}\nTell me what to do — or answer \"skip\" or \"stop\".",
                            yesNo = false,
                        )
                        when {
                            answer == null || answer.trim().equals("stop", true) -> outcome = Outcome(false, "Stopped at step ${index + 1}: ${end.why}", index, plan)
                            answer.trim().equals("skip", true) -> {
                                results += "Step ${index + 1} (${step.title}): skipped"
                                index++
                            }
                            else -> guidance += answer.trim()
                        }
                    }
                }
            }
        }
        val result = (outcome ?: Outcome(true, "All ${plan.steps.size} steps done.\n" + results.joinToString("\n"), plan.steps.size, plan))
            .copy(replans = replans, asked = asked)
        say(if (result.finished) "🏁 Task finished." else "⏹ ${result.summary}")
        // The look back ([reflect]) comes after Rex has his answer: it isn't worth making him wait for it.
        return result
    }

    private suspend fun runStep(goal: String, plan: Plan, index: Int, results: List<String>): StepEnd {
        val history = ArrayList<String>()
        var unclear = 0
        var blocked = 0
        var actions = 0
        if (env.stopped()) return StepEnd.Ended("Stopped — you pressed STOP.")
        // A step one of Rex's skills does: replay it (seconds, no brain); if it can't, the brain finishes the step.
        val named = plan.steps[index].skill.takeIf { it.isNotBlank() }?.let { skills(it) }
        if (named != null) {
            say("🎓 Using your skill “${named.name}”")
            val r = SkillRunner(env, lessons, brainHelp = false).run(named)
            if (r.ok) return StepEnd.Done(r.summary)
            history += "(Rex's skill “${named.name}” stopped: ${r.summary.take(200)} — finish this step another way)"
        }
        openStepApp(plan.steps[index], history)
        while (true) {
            if (env.stopped()) return StepEnd.Ended("Stopped — you pressed STOP.")
            val minutes = ((env.now() - startedAt) / 60_000).toInt()
            if (minutes >= limits.maxMinutes) return StepEnd.Ended("Time's up after ${limits.maxMinutes} minutes (stopped at step ${index + 1}).")
            if (actions >= limits.actionsPerStep) return StepEnd.Stuck("this step took more than ${limits.actionsPerStep} actions")
            val screen = env.screen()
            if (Safety.moneyApp(screen.app)) {
                // Never inside a banking or payment app (Rex's rule), whatever led here.
                env.key("home")
                settle()
                history += "(${screen.app} is a banking/payment app: XARVIS never uses those — went Home; do the task another way)"
                if (++blocked >= 3) return StepEnd.Stuck("the only way forward was through a banking or payment app")
                continue
            }
            val image = runCatching { env.screenshot(screen) }.getOrNull()
            val app = screen.app
            val prompt = TaskPrompts.runPrompt(
                goal, plan, index, results, history, guidance,
                lessons.promptFor(listOfNotNull(app.takeIf { it.isNotBlank() }, plan.steps[index].app.takeIf { it.isNotBlank() })),
                screen, image != null, minutes,
            )
            val raw = think(TaskPrompts.RUN_SYSTEM, prompt, image) ?: return StepEnd.Ended("Stopped — you pressed STOP.")
            val list = TaskText.parseActions(raw)
            if (list.isEmpty()) {
                if (++unclear >= 3) return StepEnd.Stuck("the brain's answers didn't follow the action format")
                history += "(that answer wasn't an action line — answer with action lines only)"
                continue
            }
            unclear = 0
            for (action in list) {
                if (env.stopped()) return StepEnd.Ended("Stopped — you pressed STOP.")
                actions++
                when (val r = act(action, screen, index)) {
                    is Act.Did -> history += r.line
                    is Act.Blocked -> {
                        history += r.line
                        if (++blocked >= 3) return StepEnd.Stuck("the only ways forward were payments, passwords, banking apps or things the task didn't ask for")
                        break // the screen didn't change: look again
                    }
                    is Act.End -> return r.end
                }
                if (repeating(history, 6)) return StepEnd.Stuck("the same action kept failing")
                if (repeating(history)) history += "(you're repeating the same action and nothing changes — try a different way, or STUCK)"
            }
        }
    }

    private sealed interface Act {
        data class Did(val line: String) : Act
        data class Blocked(val line: String) : Act
        data class End(val end: StepEnd) : Act
    }

    private suspend fun act(action: TaskAction, screen: PhoneAgent.Screen, index: Int): Act {
        fun element(n: Int) = screen.elements.firstOrNull { it.n == n }
        return when (action) {
            is TaskAction.Done -> Act.End(StepEnd.Done(action.result))
            is TaskAction.Stuck -> Act.End(StepEnd.Stuck(action.why))
            is TaskAction.Ask -> {
                asked++
                say("❓ Asking you: ${action.question}")
                val answer = env.ask(action.question, yesNo = false) ?: return Act.End(StepEnd.Ended("Stopped — you pressed STOP."))
                guidance += answer.trim()
                Act.Did("ASK \"${action.question.take(80)}\" → Rex: ${answer.take(160)}")
            }
            is TaskAction.Note -> {
                val app = screen.app.ifBlank { LessonBook.GENERAL }
                lessons.add(app, action.lesson)
                Act.Did("NOTE (saved for next time)")
            }
            is TaskAction.Open -> {
                if (Safety.moneyApp(action.app)) return Act.Blocked("OPEN ${action.app} → refused: XARVIS never opens banking or payment apps")
                say("📱 Opening ${action.app}")
                Act.Did("OPEN ${action.app} → ${env.open(action.app)}").also { settle() }
            }
            is TaskAction.Url -> {
                if (Safety.money(action.url)) return Act.Blocked("URL ${action.url} → refused: it looks like a payment page")
                say("🌐 Opening ${action.url.take(80)}")
                Act.Did("URL ${action.url} → ${env.openUrl(action.url)}").also { settle() }
            }
            is TaskAction.Tap -> {
                val el = element(action.n) ?: return Act.Did("TAP ${action.n} → there's no [${action.n}] on this screen")
                tapChecked(el.label, el.x, el.y, action.long, screen.app, "${if (action.long) "LONG" else "TAP"} ${el.n} \"${el.label.take(40)}\"")
            }
            is TaskAction.TapXY -> {
                if (screen.width <= 0 || screen.height <= 0) return Act.Did("TAPXY → the screen size isn't known; use a number")
                val x = action.x * (screen.width - 1) / 1000
                val y = action.y * (screen.height - 1) / 1000
                // The nearest labelled thing decides what it is (no tapping "Buy" by coordinates).
                val near = screen.elements.minByOrNull { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) }
                    ?.takeIf { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) < NEAR_PX * NEAR_PX }
                tapChecked(near?.label.orEmpty(), x, y, action.long, screen.app, "TAPXY ${action.x},${action.y}" + (near?.let { " (near \"${it.label.take(30)}\")" } ?: ""))
            }
            is TaskAction.Type -> {
                val el = action.n?.let { element(it) ?: return Act.Did("TYPE ${action.n} → there's no [${action.n}] on this screen") }
                if (el != null && !el.editable) return Act.Did("TYPE ${el.n} → [${el.n}] isn't a text box")
                if (el?.password == true || Safety.secret(action.text) || (el != null && Safety.check(el.label, goalText) == PhoneAgent.Safety.Verdict.NEVER)) {
                    return Act.Blocked("TYPE → refused: that's a password, PIN, OTP or payment box (Rex types those himself)")
                }
                if (el != null) {
                    env.tap(el.x, el.y, false)
                    env.pause(TYPE_SETTLE_MS)
                }
                say("⌨ Typing \"${action.text.take(50)}\"")
                val ok = env.type(action.text)
                settle()
                Act.Did("TYPE${el?.let { " ${it.n}" } ?: ""} \"${action.text.take(60)}\"" + if (ok) "" else " → no text box took it (tap the box first)")
            }
            is TaskAction.Swipe -> {
                env.swipe(action.direction)
                settle()
                Act.Did("SWIPE ${action.direction.uppercase()}")
            }
            TaskAction.Back -> {
                env.key("back")
                settle()
                Act.Did("BACK")
            }
            TaskAction.Home -> {
                env.key("home")
                settle()
                Act.Did("HOME")
            }
            is TaskAction.Wait -> {
                say("⏳ Waiting ${action.seconds} s" + if (action.why.isNotBlank()) " (${action.why.take(60)})" else "")
                // An ad's ✕ / Skip often shows long before the wait is over: look every 2 s and stop waiting when one
                // appears that wasn't there before.
                val before = screen.elements.filter { CLOSE.matches(it.label.trim()) }.map { it.label.trim() }.toSet()
                var left = action.seconds * 1000L
                var waited = 0L
                while (left > 0) {
                    if (env.stopped()) return Act.End(StepEnd.Ended("Stopped — you pressed STOP."))
                    val chunk = minOf(left, 1_000L)
                    env.pause(chunk)
                    left -= chunk
                    waited += chunk
                    if (left > 0 && waited % 2_000L == 0L) {
                        val close = runCatching { env.screen() }.getOrNull()?.elements
                            ?.firstOrNull { CLOSE.matches(it.label.trim()) && it.label.trim() !in before }
                        if (close != null) {
                            say("⏩ \"${close.label.trim()}\" appeared after ${waited / 1000} s — not waiting any longer")
                            return Act.Did("WAIT ${action.seconds} s → ended after ${waited / 1000} s: \"${close.label.trim()}\" appeared [${close.n}]")
                        }
                    }
                }
                Act.Did("WAIT ${action.seconds} s")
            }
            is TaskAction.Save -> {
                val where = env.save(action.name, action.text)
                say("💾 Saved ${action.name}")
                Act.Did("SAVE ${action.name} → $where")
            }
        }
    }

    private suspend fun tapChecked(label: String, x: Int, y: Int, long: Boolean, app: String, line: String): Act {
        when (Safety.check(label, goalText)) {
            PhoneAgent.Safety.Verdict.NEVER -> return Act.Blocked("$line → refused: \"${label.take(40)}\" is about money, a password or a subscription; find the free way")
            PhoneAgent.Safety.Verdict.OUT_OF_SCOPE -> return Act.Blocked(
                "$line → skipped: \"${label.take(40)}\" in ${app.ifBlank { "this app" }} isn't part of Rex's task (close it or go around it)",
            )
            PhoneAgent.Safety.Verdict.OK -> {}
        }
        say("👆 ${if (long) "Long-pressing" else "Tapping"} ${label.take(50).ifBlank { "a spot" }}")
        env.tap(x, y, long)
        settle()
        return Act.Did(line)
    }

    /** The brain, waiting out free-limit pauses (with growing gaps) instead of giving up; null when stopped. */
    private suspend fun think(system: String, prompt: String, image: String?): String? {
        var wait = 30_000L
        while (true) {
            if (env.stopped()) return null
            try {
                return env.think(system, prompt, image)
            } catch (e: BrainBusy) {
                say("⏸ The brain's free limit is reached for now — trying again in ${wait / 1000} s")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                say("⏸ The brain didn't answer (${e.message?.take(80) ?: e.javaClass.simpleName}) — trying again in ${wait / 1000} s")
            }
            var left = wait
            while (left > 0) {
                if (env.stopped()) return null
                env.pause(minOf(left, 1_000L))
                left -= 1_000L
            }
            wait = minOf(wait * 2, 600_000L)
        }
    }

    private suspend fun replan(goal: String, plan: Plan, index: Int, results: List<String>, why: String): Plan? {
        say("🧠 Re-planning the rest…")
        val raw = think(TaskPrompts.REPLAN_SYSTEM, TaskPrompts.replanPrompt(goal, plan, index, results, log, why), null) ?: return null
        return Plan.parse(raw)
    }

    /** The look back: a few lessons for next time (best effort; run after Rex has the result). */
    suspend fun reflect(goal: String, outcome: Outcome) {
        if (env.stopped()) return
        val raw = runCatching { env.think(TaskPrompts.REFLECT_SYSTEM, TaskPrompts.reflectPrompt(goal, outcome.summary, log), null) }.getOrNull() ?: return
        val got = TaskText.parseLessons(raw)
        got.forEach { (app, tip) -> lessons.add(app, tip) }
        if (got.isNotEmpty()) say("📝 Learned ${got.size} thing(s) for next time.")
    }

    /**
     * The step names an installed app and it isn't on screen: open it straight away, without asking the brain (it would
     * only say OPEN — a few seconds saved on every step that starts in a new app).
     */
    private suspend fun openStepApp(step: PlanStep, history: MutableList<String>) {
        val app = step.app.trim()
        if (app.isEmpty() || app.equals("XARVIS", true) || app.contains("://") || Regex("""^[\w-]+(\.[\w-]+)+(/.*)?$""").matches(app)) return
        if (Safety.moneyApp(app)) return
        val now = runCatching { env.screen().app }.getOrDefault("")
        if (now.isNotBlank() && (now.contains(app, true) || app.contains(now, true))) return
        val r = env.open(app)
        if (r.startsWith("opened")) {
            say("📱 Opening $app")
            history += "OPEN $app → $r (done for you before this turn)"
            settle()
        }
    }

    private suspend fun settle() = env.pause(limits.settleMs)

    private fun say(line: String) {
        log += line
        env.progress(line)
    }

    /** The same action [times] times in a row (the runner's own hints in brackets don't count). */
    private fun repeating(history: List<String>, times: Int = 4): Boolean {
        val acts = history.filter { !it.startsWith("(") }
        if (acts.size < times) return false
        val last = acts.takeLast(times)
        return last.all { it == last[0] } && !last[0].startsWith("WAIT")
    }

    /** What Task mode may do by itself: the same rules as XARVIS using the phone (PhoneAgent.Safety), plus payment pages. */
    object Safety {
        fun check(label: String, goal: String): PhoneAgent.Safety.Verdict = PhoneAgent.Safety.check(label, goal)

        /** Only digits (4+), like an OTP, PIN or card number. */
        fun secret(text: String): Boolean = PhoneAgent.Safety.secret(text)

        fun moneyApp(app: String): Boolean = PhoneAgent.Safety.moneyApp(app)

        fun money(url: String): Boolean = Regex("""(?i)(checkout|billing|/pay(?:ment)?\b|/subscribe|/upgrade|/pricing/buy)""").containsMatchIn(url)
    }

    companion object {
        /** A control that ends an ad or a pop-up. */
        internal val CLOSE = Regex("""(?i)^(?:[✕×xX]|close|close ad|skip|skip ad|skip ads|skip video|reward granted|continue to app|dismiss|no thanks|not now)$""")
        private const val TYPE_SETTLE_MS = 500L
        private const val NEAR_PX = 90
    }
}
