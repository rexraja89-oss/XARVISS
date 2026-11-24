package com.xarvis.ai.mission

import com.xarvis.ai.agent.PhoneAgent
import com.xarvis.ai.task.SkillMatch

/**
 * Carries out a [Mission] on a phone, step by step, with no brain: the steps are exact, so it's fast and free (the
 * same idea as replaying a taught skill). Every step is written into the report; `look` adds a screenshot and the
 * screen's words; a step that fails (unless it's a `try`) ends the mission with a look at where it stopped.
 *
 * Rex's rules hold here too: nothing with money (banking/payment apps are never opened, and if one comes to the front
 * the mission presses Home and stops), no payment/password/code buttons, nothing typed into password boxes, no codes
 * typed. His STOP on the banner ends it.
 */
class MissionRunner(private val phone: PhoneAgent.Target, private val io: Io) {

    interface Io {
        suspend fun pause(ms: Long)
        fun now(): Long
        fun progress(line: String)
        /** XARVIS's own saved crash reports (newest first), or "" when there are none. */
        suspend fun crashes(): String
        /** Copies [path] from the bridge repo onto the phone; says where it went. Throws when it can't. */
        suspend fun download(path: String): String = throw IllegalStateException("downloads aren't possible here")
        /** Sends the phone's newest recording/video/picture to the bridge repo; a line saying where it went. */
        suspend fun upload(what: String): String = throw IllegalStateException("uploads aren't possible here")
    }

    data class Look(val n: Int, val afterStep: Int, val title: String, val jpeg: ByteArray?, val text: String)

    data class Report(
        val mission: Mission,
        val finished: Boolean,
        val summary: String,
        val lines: List<String>,
        val looks: List<Look>,
        val seconds: Long,
    )

    private val lines = ArrayList<String>()
    private val looks = ArrayList<Look>()

    suspend fun run(mission: Mission): Report {
        val start = io.now()
        lines.clear()
        looks.clear()
        if (mission.problems.isNotEmpty() || mission.steps.isEmpty()) {
            val why = mission.problems.ifEmpty { listOf("the mission has no steps") }
            return Report(mission, false, "Not started: " + why.joinToString("; "), why.map { "⚠ $it" }, emptyList(), 0)
        }
        var finished = true
        var summary = "All ${mission.steps.size} steps done"
        try {
            phone.begin()
            for ((i, step) in mission.steps.withIndex()) {
                val n = i + 1
                if (phone.stopped()) {
                    finished = false; summary = "Stopped: you pressed STOP before step $n"; lines += "⏹ $summary"; break
                }
                if (io.now() - start > MAX_MS) {
                    finished = false; summary = "Stopped: the mission ran longer than ${MAX_MS / 60_000} minutes"; lines += "⏹ $summary"; break
                }
                io.progress("▶ $n/${mission.steps.size}: ${step.text}")
                if (step.onlyIf.isNotBlank() && runCatching { find(phone.screen(), step.onlyIf) }.getOrNull() == null) {
                    lines += "$n. ➖ `${step.text}` — skipped: \"${step.onlyIf}\" isn't on screen"
                    continue
                }
                val result = runCatching { act(n, step) }.getOrElse { Outcome(false, it.message ?: it.javaClass.simpleName) }
                lines += "$n. ${if (result.ok) "✅" else if (step.optional) "➖" else "❌"} `${step.text}` — ${result.detail}"
                if (result.hardStop) {
                    finished = false; summary = "Stopped at step $n: ${result.detail}"; break
                }
                if (!result.ok && !step.optional) {
                    finished = false
                    summary = "Stopped at step $n (`${step.text}`): ${result.detail}"
                    look(n, "Where it stopped (step $n)")
                    break
                }
                // A banking or payment app must never stay in front (Rex's rule), however it got there.
                val app = runCatching { phone.screen().app }.getOrDefault("")
                if (PhoneAgent.Safety.moneyApp(app)) {
                    runCatching { phone.key("home") }
                    finished = false
                    summary = "Stopped after step $n: a banking/payment app ($app) came up, and I never use those"
                    lines += "⛔ $summary"
                    break
                }
            }
        } finally {
            runCatching { phone.end() }
        }
        return Report(mission, finished, summary, lines.toList(), looks.toList(), (io.now() - start) / 1000)
    }

    private data class Outcome(val ok: Boolean, val detail: String, val hardStop: Boolean = false)

    private suspend fun act(n: Int, step: Mission.Step): Outcome = when (step.kind) {
        Mission.Kind.OPEN -> {
            if (PhoneAgent.Safety.moneyApp(step.arg)) Outcome(false, "I never open banking or payment apps", hardStop = true)
            else {
                val said = phone.open(step.arg)
                io.pause(SETTLE_MS * 2)
                Outcome(said.startsWith("opened"), said)
            }
        }
        Mission.Kind.TAP, Mission.Kind.LONG -> tap(step.arg, long = step.kind == Mission.Kind.LONG, waitMs = if (step.optional || step.onlyIf.isNotBlank()) TRY_WAIT_MS else TAP_WAIT_MS)
        Mission.Kind.TAPXY -> {
            val s = phone.screen()
            val x = (step.arg.toFloat() * s.width).toInt()
            val y = (step.arg2.toFloat() * s.height).toInt()
            val near = s.elements.filter { it.label.isNotBlank() }.minByOrNull { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) }
            if (near != null && dist(near.x, near.y, x, y) < 90 && never(near.label)) {
                Outcome(false, "the spot is on \"${near.label}\", which looks like money, a password or a code — not touched", hardStop = true)
            } else {
                phone.tap(x, y)
                io.pause(SETTLE_MS)
                Outcome(true, "tapped at ${pct(step.arg)}, ${pct(step.arg2)}" + (near?.label?.takeIf { it.isNotBlank() }?.let { " (near \"$it\")" } ?: ""))
            }
        }
        Mission.Kind.TYPE -> type(step.arg, step.arg2)
        Mission.Kind.CLEAR -> {
            val box = findBox(phone.screen(), step.arg)
            when {
                box == null -> Outcome(false, "no text box called \"${step.arg}\" on screen")
                box.password -> Outcome(false, "that's a password box — yours", hardStop = true)
                phone.setText(box.x, box.y, "") -> { io.pause(600); Outcome(true, "emptied \"${step.arg}\"") }
                else -> Outcome(false, "the box wouldn't empty")
            }
        }
        Mission.Kind.DOWNLOAD -> Outcome(true, io.download(step.arg))
        Mission.Kind.SCROLL -> {
            phone.scroll(step.arg != "up")
            io.pause(SETTLE_MS)
            Outcome(true, "scrolled ${step.arg}")
        }
        Mission.Kind.SWIPE -> {
            val (x, y) = step.arg.split(",").map { it.toFloat() }
            val (x2, y2) = step.arg2.split(",").map { it.toFloat() }
            val ok = phone.swipe(x, y, x2, y2)
            io.pause(SETTLE_MS)
            Outcome(ok, if (ok) "swiped from ${pct(x.toString())}, ${pct(y.toString())} to ${pct(x2.toString())}, ${pct(y2.toString())}" else "this phone can't swipe")
        }
        Mission.Kind.UPLOAD -> Outcome(true, io.upload(step.arg))
        Mission.Kind.KEY -> {
            phone.key(step.arg)
            io.pause(SETTLE_MS)
            Outcome(true, "pressed ${step.arg}")
        }
        Mission.Kind.WAIT -> {
            var left = step.seconds * 1000L
            while (left > 0 && !phone.stopped()) { io.pause(minOf(1_000L, left)); left -= 1_000 }
            Outcome(true, "waited ${step.seconds} s")
        }
        Mission.Kind.WAIT_FOR -> {
            val until = io.now() + step.seconds * 1000L
            var found: PhoneAgent.Element? = null
            while (!phone.stopped()) {
                found = find(phone.screen(), step.arg)
                if (found != null || io.now() >= until) break
                io.pause(POLL_MS)
            }
            if (found != null) Outcome(true, "\"${found.label}\" is on screen") else Outcome(false, "\"${step.arg}\" didn't appear within ${step.seconds} s")
        }
        Mission.Kind.LOOK -> {
            val got = look(n, step.arg.ifBlank { "After step $n" })
            Outcome(true, if (got) "looked (screenshot + screen text below)" else "looked (screen text only: Android gave no screenshot)")
        }
        Mission.Kind.READ -> {
            val text = screenText(phone.screen())
            Outcome(true, "screen: $text")
        }
        Mission.Kind.NOTE -> Outcome(true, step.arg.ifBlank { "(note)" })
        Mission.Kind.URL -> {
            val ok = phone.openUrl(step.arg)
            io.pause(SETTLE_MS * 2)
            Outcome(ok, if (ok) "opened ${step.arg}" else "couldn't open the browser")
        }
        Mission.Kind.CRASHES -> {
            val c = io.crashes()
            Outcome(true, if (c.isBlank()) "no saved crashes" else "crash reports:\n\n```\n${c.take(MAX_CRASH)}\n```")
        }
    }

    private suspend fun tap(label: String, long: Boolean, waitMs: Long): Outcome {
        val until = io.now() + waitMs
        var el: PhoneAgent.Element? = null
        while (!phone.stopped()) {
            el = find(phone.screen(), label)
            if (el != null || io.now() >= until) break
            io.pause(POLL_MS)
        }
        val e = el ?: return Outcome(false, "\"$label\" isn't on screen (waited ${waitMs / 1000} s)")
        if (never(e.label)) return Outcome(false, "\"${e.label}\" looks like money, a password or a code — I never press those", hardStop = true)
        if (long) phone.longPress(e.x, e.y) else phone.tap(e.x, e.y)
        io.pause(SETTLE_MS)
        return Outcome(true, "${if (long) "pressed" else "tapped"} \"${e.label}\"")
    }

    private suspend fun type(text: String, into: String): Outcome {
        if (PhoneAgent.Safety.secret(text)) return Outcome(false, "that looks like a code or PIN — I never type those", hardStop = true)
        if (into.isNotBlank()) {
            val s = phone.screen()
            val box = findBox(s, into) ?: return Outcome(false, "no text box called \"$into\" on screen")
            if (box.password || never(box.label) || never(box.hint)) return Outcome(false, "\"$into\" is a password/PIN/code box — those are yours", hardStop = true)
            // "type … into" puts exactly this text in the box (web boxes kept what was there and added to it: the
            // YouTube handle became "TheFreeVersion-n5iTheFreeVersionTech", 25 Nov 2026).
            if (phone.setText(box.x, box.y, text)) {
                io.pause(SETTLE_MS)
                return Outcome(true, "put \"${text.take(60)}\" into \"$into\"")
            }
            phone.tap(box.x, box.y)
            io.pause(600)
        } else {
            val focused = phone.screen().elements.firstOrNull { it.editable && it.password }
            if (focused != null && phone.screen().elements.count { it.editable } == 1) return Outcome(false, "the only box is a password box — that's yours", hardStop = true)
        }
        val ok = phone.type(text)
        io.pause(SETTLE_MS)
        return Outcome(ok, if (ok) "typed \"${text.take(60)}\"" + (if (into.isNotBlank()) " into \"$into\"" else "") else "no text box took it (tap the box first)")
    }

    /** Screenshot + screen words into the report; whether a picture came. */
    private suspend fun look(n: Int, title: String): Boolean {
        val s = runCatching { phone.screen() }.getOrNull()
        val jpeg = runCatching { phone.look() }.getOrNull()
        looks += Look(looks.size + 1, n, title, jpeg, s?.let { screenText(it) } ?: "(couldn't read the screen)")
        return jpeg != null
    }

    companion object {
        const val MAX_MS = 15 * 60_000L
        const val TAP_WAIT_MS = 8_000L
        /** A `try` or `if` tap: the label is probably there or not at all, so don't wait long. */
        const val TRY_WAIT_MS = 3_000L
        const val POLL_MS = 600L
        const val SETTLE_MS = 1_200L
        private const val MAX_CRASH = 6_000

        private fun dist(x1: Int, y1: Int, x2: Int, y2: Int) = kotlin.math.sqrt(((x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2)).toDouble())
        private fun pct(f: String) = "${((f.toFloatOrNull() ?: 0f) * 100).toInt()}%"

        fun never(label: String): Boolean = label.isNotBlank() && PhoneAgent.Safety.check(label, "") == PhoneAgent.Safety.Verdict.NEVER

        /** The screen's words, top to bottom: "[app] a · b · c". */
        fun screenText(s: PhoneAgent.Screen): String {
            val words = s.elements.sortedWith(compareBy({ it.y }, { it.x }))
                .map { e -> buildString {
                    append(e.label.ifBlank { e.hint }.replace('\n', ' ').trim())
                    if (e.editable) append(if (e.value.isNotBlank()) " [box: ${e.value.take(40)}]" else " [box]")
                } }
                .filter { it.isNotBlank() }
                .distinct()
                .take(150)
            return "[${s.app}] " + words.joinToString(" · ")
        }

        /** The element called [label]: same words, then whole words inside a longer label, then any part of it. */
        fun find(s: PhoneAgent.Screen, label: String): PhoneAgent.Element? {
            val want = SkillMatch.norm(label)
            if (want.isEmpty()) return null
            // Never a text box: what was just typed into a search box would otherwise "be" the result (24 Nov 2026,
            // the first mission tapped the box holding "live translate" instead of a result).
            val els = s.elements.filter { !it.editable && (it.label.isNotBlank() || it.hint.isNotBlank()) }
            fun best(list: List<PhoneAgent.Element>) = list.sortedWith(compareBy({ !it.tappable }, { it.y }, { it.x })).firstOrNull()
            fun name(e: PhoneAgent.Element) = SkillMatch.norm(e.label.ifBlank { e.hint })
            best(els.filter { name(it) == want })?.let { return it }
            if (want.length >= 3) best(els.filter { SkillMatch.hasWords(name(it), want) && name(it).length <= want.length + 40 })?.let { return it }
            if (want.length >= 4) best(els.filter { name(it).contains(want) && name(it).length <= want.length + 40 })?.let { return it }
            return null
        }

        /** The text box for [label]: its own label/hint, else the nearest box under a label with those words. */
        fun findBox(s: PhoneAgent.Screen, label: String): PhoneAgent.Element? {
            val want = SkillMatch.norm(label)
            val boxes = s.elements.filter { it.editable }
            if (boxes.isEmpty()) return null
            boxes.firstOrNull { SkillMatch.norm(it.hint) == want || SkillMatch.norm(it.label) == want }?.let { return it }
            boxes.firstOrNull { val h = SkillMatch.norm(it.hint + " " + it.label); want.length >= 3 && SkillMatch.hasWords(h, want) }?.let { return it }
            val tag = find(PhoneAgent.Screen(s.app, s.elements.filter { !it.editable }, s.width, s.height), label) ?: return null
            return boxes.filter { it.y >= tag.y - 20 && it.y - tag.y < 400 }.minByOrNull { (it.y - tag.y) + kotlin.math.abs(it.x - tag.x) / 4 }
        }

        /** The report as Markdown; screenshots are referenced as shot-N.jpg next to it. */
        fun markdown(r: Report, id: String, phone: String, version: String, at: String): String = buildString {
            appendLine("# Mission $id: ${r.mission.title}")
            appendLine()
            appendLine("- Phone: $phone · XARVIS $version · $at")
            appendLine("- Result: ${if (r.finished) "✅" else "❌"} ${r.summary} (${r.seconds} s)")
            appendLine()
            appendLine("## Steps")
            r.lines.forEach { appendLine(it) }
            for (l in r.looks) {
                appendLine()
                appendLine("## Look ${l.n}: ${l.title} (after step ${l.afterStep})")
                if (l.jpeg != null) appendLine("![shot ${l.n}](shot-${l.n}.jpg)")
                appendLine()
                appendLine("Screen: ${l.text}")
            }
        }
    }
}
