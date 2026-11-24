package com.xarvis.ai.mission

/**
 * A mission from Claude (Rex, 24 Nov 2026: "programming a task whenever you wish inside XARVIS… it performs all the
 * steps and reports back to you"): a short script of exact steps that XARVIS runs with its Hands and answers with a
 * report (screenshots, screen text, what worked). Written by Claude in Rex's private bridge repo, never anywhere else.
 *
 * The language, one step a line (blank lines and `#` comments ignored):
 * ```
 * MISSION: Check Galaxy AI call features      (optional title)
 * PHONE: benco                                (optional; without it the S22 runs it)
 * open Settings
 * tap Galaxy AI                               (quotes optional: tap "Call assist")
 * try tap Call assist                         ("try" = a miss doesn't stop the mission; waits only 3 s)
 * if Skip ad: tap Skip ad                     (runs the step only if "Skip ad" is on screen right now; no waiting)
 * long Photo 1                                (long press)
 * tapxy 0.5 0.3                               (a spot, as parts of the screen width/height)
 * type hello                                  (into the box with the cursor)
 * type hello into Search                      (REPLACES what's in the box labelled/hinted "Search")
 * clear Search                                (empties that box)
 * download channel/logo.png                   (a file from this repo → the phone's Pictures/XARVIS/Missions)
 * scroll down | scroll up
 * swipe left | swipe right [at 30%]          (sideways, e.g. the quick panel's pages; at = height, middle by default)
 * swipe 0.8 0.3 0.2 0.3                      (from x y to x y, as parts of the screen or %)
 * upload recording | video | picture [N]     (the phone's newest one, or the Nth newest → this repo's "media" release)
 * back | home | recents | notifications | quick
 * wait 5                                      (seconds)
 * wait for Connected 30                       (until it's on screen, at most 30 s; 15 by default)
 * look Galaxy AI page                         (screenshot + screen text into the report)
 * read                                        (screen text only)
 * note any words                              (a line in the report)
 * url https://example.com                     (opens it in the browser)
 * crashes                                     (XARVIS's own saved crash reports)
 * ```
 */
data class Mission(val title: String, val phone: String, val steps: List<Step>, val problems: List<String>) {

    data class Step(val line: Int, val text: String, val kind: Kind, val arg: String = "", val arg2: String = "", val seconds: Int = 0, val optional: Boolean = false, val onlyIf: String = "")

    enum class Kind { OPEN, TAP, LONG, TAPXY, TYPE, CLEAR, DOWNLOAD, SCROLL, SWIPE, UPLOAD, KEY, WAIT, WAIT_FOR, LOOK, READ, NOTE, URL, CRASHES }

    companion object {
        const val MAX_STEPS = 80

        /** "owner/name" → (owner, name); null if it isn't that shape. */
        fun split(full: String): Pair<String, String>? {
            val m = Regex("""^\s*([A-Za-z0-9-]+)/([A-Za-z0-9._-]+)\s*$""").find(full) ?: return null
            return m.groupValues[1] to m.groupValues[2]
        }

        /** A mission file's id ("2026-11-24-galaxy-ai.txt" → "2026-11-24-galaxy-ai"); null for anything else. */
        fun missionId(file: String): String? =
            Regex("""^([A-Za-z0-9._-]{1,80})\.(?:txt|md)$""").find(file)?.groupValues?.get(1)?.takeIf { !it.startsWith(".") }
        private val KEYS = setOf("back", "home", "recents", "notifications", "quick")

        fun parse(text: String): Mission {
            var title = ""
            var phone = ""
            val steps = ArrayList<Step>()
            val problems = ArrayList<String>()
            text.lines().forEachIndexed { i, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@forEachIndexed
                header(line, "MISSION")?.let { title = it; return@forEachIndexed }
                header(line, "PHONE")?.let { phone = it; return@forEachIndexed }
                val step = step(i + 1, line)
                if (step == null) problems += "line ${i + 1}: I don't understand \"$line\"" else steps += step
            }
            if (steps.size > MAX_STEPS) problems += "more than $MAX_STEPS steps; split it into two missions"
            return Mission(title.ifBlank { "Mission" }, phone, steps.take(MAX_STEPS), problems)
        }

        private fun frac(t: String): Float? = t.removeSuffix("%").toFloatOrNull()?.let { if (it > 1f) it / 100f else it }?.takeIf { it in 0f..1f }

        /** "left [at 30%]" / "right" / "x y x2 y2" → ("x,y", "x2,y2") as parts of the screen; null if it isn't one. */
        private fun swipe(rest: String): Pair<String, String>? {
            val dir = Regex("""^(left|right)(?:\s+at\s+(\S+))?$""", RegexOption.IGNORE_CASE).find(rest.trim())
            if (dir != null) {
                val y = if (dir.groupValues[2].isBlank()) 0.5f else frac(dir.groupValues[2]) ?: return null
                return if (dir.groupValues[1].lowercase() == "left") "0.85,$y" to "0.15,$y" else "0.15,$y" to "0.85,$y"
            }
            val n = rest.trim().split(Regex("""[\s,]+""")).map { frac(it) ?: return null }
            return if (n.size == 4) "${n[0]},${n[1]}" to "${n[2]},${n[3]}" else null
        }

        private fun header(line: String, name: String): String? =
            Regex("""^$name\s*:\s*(.*)$""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.trim()

        private fun unquote(s: String): String {
            val t = s.trim()
            return if (t.length >= 2 && (t.first() == '"' && t.last() == '"' || t.first() == '\'' && t.last() == '\'')) t.substring(1, t.length - 1) else t
        }

        fun step(n: Int, original: String): Step? {
            var line = original
            var optional = false
            var onlyIf = ""
            // "if <label>: <step>" — only when that label is on screen now (one look, no waiting).
            Regex("""^if\s+(.+?)\s*:\s+(\S.*)$""", RegexOption.IGNORE_CASE).find(line)?.let {
                onlyIf = unquote(it.groupValues[1]); line = it.groupValues[2]; optional = true
                if (onlyIf.isBlank()) return null
            }
            Regex("""^try\s+""", RegexOption.IGNORE_CASE).find(line)?.let { optional = true; line = line.substring(it.range.last + 1) }
            val verb = line.substringBefore(' ').lowercase()
            val rest = line.substringAfter(' ', "").trim()
            fun s(kind: Kind, arg: String = "", arg2: String = "", seconds: Int = 0) = Step(n, original, kind, arg, arg2, seconds, optional, onlyIf)
            return when (verb) {
                "open" -> rest.takeIf { it.isNotBlank() }?.let { s(Kind.OPEN, unquote(it)) }
                "tap", "click", "press" -> rest.takeIf { it.isNotBlank() }?.let { s(Kind.TAP, unquote(it)) }
                "long" -> rest.removePrefix("press ").takeIf { it.isNotBlank() }?.let { s(Kind.LONG, unquote(it)) }
                "tapxy" -> {
                    val nums = rest.split(Regex("""[\s,]+""")).mapNotNull { it.removeSuffix("%").toFloatOrNull() }
                    if (nums.size != 2) null else {
                        val (x, y) = nums.map { if (it > 1f) it / 100f else it }
                        if (x !in 0f..1f || y !in 0f..1f) null else s(Kind.TAPXY, x.toString(), y.toString())
                    }
                }
                "type" -> {
                    if (rest.isBlank()) null else {
                        val into = Regex("""^(.*?)\s+into\s+(.+)$""", RegexOption.IGNORE_CASE).find(rest)
                        if (into != null) s(Kind.TYPE, unquote(into.groupValues[1]), unquote(into.groupValues[2])) else s(Kind.TYPE, unquote(rest))
                    }
                }
                "clear" -> rest.takeIf { it.isNotBlank() }?.let { s(Kind.CLEAR, unquote(it)) }
                "download" -> unquote(rest).takeIf { Regex("""^[A-Za-z0-9._/-]{1,200}$""").matches(it) && ".." !in it }?.let { s(Kind.DOWNLOAD, it) }
                "scroll", "swipe" -> when (rest.lowercase()) {
                    "", "down" -> s(Kind.SCROLL, "down")
                    "up" -> s(Kind.SCROLL, "up")
                    else -> swipe(rest)?.let { (a, b) -> s(Kind.SWIPE, a, b) }
                }
                "upload" -> {
                    val num = Regex("""\s+(\d{1,2})$""").find(rest)
                    val nth = (num?.groupValues?.get(1)?.toIntOrNull() ?: 1).coerceAtLeast(1)
                    val words = (if (num != null) rest.substring(0, num.range.first) else rest)
                    when (val what = words.lowercase().removePrefix("the ").removePrefix("newest ").removePrefix("latest ").trim()) {
                        "", "recording", "screen recording" -> s(Kind.UPLOAD, "recording", nth.toString())
                        "video" -> s(Kind.UPLOAD, "video", nth.toString())
                        "picture", "photo", "screenshot" -> s(Kind.UPLOAD, if (what == "screenshot") "screenshot" else "picture", nth.toString())
                        else -> null
                    }
                }
                in KEYS -> if (rest.isBlank()) s(Kind.KEY, verb) else null
                "wait" -> {
                    val f = Regex("""^for\s+(.+?)(?:\s+(\d{1,3})\s*s?)?$""", RegexOption.IGNORE_CASE).find(rest)
                    when {
                        f != null -> s(Kind.WAIT_FOR, unquote(f.groupValues[1]), seconds = (f.groupValues[2].toIntOrNull() ?: 15).coerceIn(1, 300))
                        else -> rest.removeSuffix("s").trim().toIntOrNull()?.let { s(Kind.WAIT, seconds = it.coerceIn(1, 300)) }
                    }
                }
                "look", "report", "screenshot" -> s(Kind.LOOK, unquote(rest))
                "read" -> if (rest.isBlank()) s(Kind.READ) else null
                "note" -> s(Kind.NOTE, rest)
                "url" -> rest.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let { s(Kind.URL, it) }
                "crashes" -> s(Kind.CRASHES)
                else -> null
            }
        }
    }
}
