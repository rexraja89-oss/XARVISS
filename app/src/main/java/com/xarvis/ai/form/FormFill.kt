package com.xarvis.ai.form

import com.xarvis.ai.agent.PhoneAgent
import org.json.JSONObject

/**
 * "Xarvis, fill this form" (Rex, 22 Nov 2026: "an option always available to ask XARVIS to fill forms from the
 * information it has, like my CV… I review and send"). Reads the text boxes on the screen, asks the brain what goes
 * in each from Rex's CV and details, types them in, scrolls on, and repeats. It never presses a button (no Submit,
 * Next, Pay), never types passwords, ID / passport numbers, card or bank details, and never makes facts up: what the
 * CV doesn't say stays empty and is listed for Rex. Pure logic here (tested in FormFillTest); the phone side is [Env].
 */
object FormFill {
    interface Env {
        suspend fun screen(): PhoneAgent.Screen
        /** Puts [text] in the box at ([x], [y]), replacing what's there; whether it worked. */
        suspend fun setField(x: Int, y: Int, text: String): Boolean
        suspend fun scrollDown()
        suspend fun think(system: String, prompt: String): String
        fun progress(line: String)
        suspend fun pause(ms: Long)
    }

    data class Field(val n: Int, val label: String, val near: String, val hint: String, val value: String, val x: Int, val y: Int) {
        /** What identifies it across scrolls (positions change, words don't). */
        val key: String get() = (near + "|" + label + "|" + hint).lowercase()
        val name: String get() = listOf(near, label, hint).firstOrNull { it.isNotBlank() && it != "text box" } ?: "a box"
    }

    data class Result(val filled: List<String>, val sensitive: List<String>, val notInCv: List<String>, val kept: List<String>)

    /** Never filled by XARVIS: Rex types these himself. */
    private val SENSITIVE = Regex(
        """(?i)\b(pass ?word|passcode|pin\b|otp|one[- ]time|cvv|cvc|card ?(number|no)|credit|debit|iban|swift|bic\b|sort code|routing|account (number|no)|bank|""" +
            """id ?(number|no)|identity (number|no)|national id|emirates id|aadhaar|passport|social security|ssn|tax (number|no|id)|pan ?(number|card)|""" +
            """signature|captcha|security (question|answer)|maiden)""",
    )

    fun sensitive(f: Field): Boolean = SENSITIVE.containsMatchIn(f.label + " " + f.near + " " + f.hint)

    /** The text boxes on [screen], each with the nearest words above it (web forms put their labels there). */
    fun fields(screen: PhoneAgent.Screen): List<Field> {
        val words = screen.elements.filter { !it.editable && it.label.isNotBlank() }
        return screen.elements.filter { it.editable && !it.password }.map { e ->
            val near = words.filter { it.y < e.y + 6 && e.y - it.y < 260 }
                .minByOrNull { (e.y - it.y) * 3 + kotlin.math.abs(e.x - it.x) / 4 }?.label.orEmpty()
            Field(e.n, e.label, if (near == e.label) "" else near.take(80), e.hint, e.value, e.x, e.y)
        }
    }

    const val SYSTEM = """You fill in forms on Rex's phone from his CV and his details.
For each numbered form box you get, decide what to type in it.
Rules:
- Use only facts from the CV and details. Never invent anything: no made-up dates, numbers, addresses, names or references. If the CV doesn't say, answer null.
- Match what the box asks for and its format (a date box gets a date in the format shown, a phone box a phone number, a years box a number).
- Short boxes get short answers; a summary/description/duties box may get up to 600 characters of plain sentences.
- Never fill passwords, ID or passport numbers, card or bank details: null.
Answer with JSON only, like {"1": "Sajjad", "2": null}."""

    fun prompt(cv: String, details: String, fields: List<Field>): String = buildString {
        append("REX'S DETAILS:\n").append(details.trim()).append("\n\n")
        append("REX'S CV:\n").append(cv.trim().take(9000)).append("\n\n")
        append("FORM BOXES (number: label | words near it | hint):\n")
        fields.forEach { append(it.n).append(": ").append(it.label).append(" | ").append(it.near).append(" | ").append(it.hint).append('\n') }
    }

    /** The brain's {"n": "value" | null} as a map of what to type (blank and null left out). */
    fun answers(text: String): Map<Int, String> {
        val s = text.indexOf('{'); val e = text.lastIndexOf('}')
        if (s < 0 || e <= s) return emptyMap()
        val o = runCatching { JSONObject(text.substring(s, e + 1)) }.getOrNull() ?: return emptyMap()
        val out = HashMap<Int, String>()
        for (k in o.keys()) {
            val n = k.trim().toIntOrNull() ?: continue
            if (o.isNull(k)) continue
            val v = o.optString(k).trim()
            if (v.isNotEmpty() && !v.equals("null", true)) out[n] = v.take(1200)
        }
        return out
    }

    const val MAX_PAGES = 8

    suspend fun run(env: Env, cv: String, details: String): Result {
        val filled = ArrayList<String>(); val sens = ArrayList<String>(); val missing = ArrayList<String>(); val kept = ArrayList<String>()
        val seen = HashSet<String>()
        var lastSig = ""
        for (page in 0 until MAX_PAGES) {
            val screen = env.screen()
            val sig = screen.elements.joinToString("|") { it.label + it.y / 40 }
            if (page > 0 && sig == lastSig) break               // the page didn't move: the end of the form
            lastSig = sig
            val todo = ArrayList<Field>()
            for (f in fields(screen)) {
                if (!seen.add(f.key)) continue
                when {
                    sensitive(f) -> sens += f.name
                    f.value.isNotBlank() -> kept += f.name        // already has something: Rex's own typing stays
                    else -> todo += f
                }
            }
            if (todo.isNotEmpty()) {
                env.progress("✍ Reading ${todo.size} box${if (todo.size == 1) "" else "es"}…")
                val ans = answers(env.think(SYSTEM, prompt(cv, details, todo)))
                for (f in todo) {
                    val v = ans[f.n]
                    if (v == null) { missing += f.name; continue }
                    if (env.setField(f.x, f.y, v)) filled += f.name else missing += f.name
                    env.pause(250)
                }
            }
            env.scrollDown()
            env.pause(900)
        }
        return Result(filled, sens, missing, kept)
    }

    fun summary(r: Result): String = buildString {
        if (r.filled.isEmpty() && r.sensitive.isEmpty() && r.notInCv.isEmpty()) {
            append("✍ I found no text boxes to fill on this screen. Open the form, then ask me again.")
            return@buildString
        }
        append("✍ Filled ${r.filled.size} box${if (r.filled.size == 1) "" else "es"} from your CV.")
        if (r.sensitive.isNotEmpty()) append("\n🔒 Left for you (private): ").append(r.sensitive.distinct().take(8).joinToString(", "))
        if (r.notInCv.isNotEmpty()) append("\n❔ Not in your CV, left empty: ").append(r.notInCv.distinct().take(10).joinToString(", "))
        if (r.kept.isNotEmpty()) append("\n↩ Already had text, untouched: ").append(r.kept.distinct().take(6).joinToString(", "))
        append("\nDrop-down lists and tick boxes are yours to choose. Nothing was sent: check it, then press Submit yourself.")
    }
}
