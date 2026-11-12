package com.xarvis.ai.task

/**
 * What Rex types (or says) to Task mode, on whichever phone he holds: "task: …" / "task on my S22: …" starts one,
 * "answer: …" replies to its question, "stop the task", "task status". Pure, so it's unit-tested.
 *
 * A job in plain words is a task too, without the word "task" (Rex, 12 Nov 2026: "open Ninja VPN and connect it. Wait
 * for any ads…" only opened the app): "open <app> [on my S22] and/then/, <something to do there>" when that something is
 * an action (connect, search, play, close, wait, send…). Just "open WhatsApp", or "… and read my last message", stays
 * with the quicker ways.
 */
object TaskCommands {
    sealed interface Cmd {
        /**
         * [where]: the words naming the phone to do it on ("S22"), or null (here, or the S22 from the benco).
         * [implicit]: said without "task:" ([app] is the app it starts in): XARVIS checks the app exists before taking it.
         */
        data class Start(val goal: String, val where: String?, val app: String? = null, val implicit: Boolean = false) : Cmd
        data class Answer(val text: String) : Cmd
        data object Stop : Cmd
        data object Status : Cmd

        /** "do my VPN", "run my search: dew point tables" ([value]: the changing text, "a | b" for several). */
        data class Skill(val name: String, val value: String? = null, val where: String? = null, val raw: String = name) : Cmd

        /** "my skills" / "what skills do you know". */
        data object Skills : Cmd

        /** "night shift: do my VPN at 2 am" — every night at [hour]:[minute] (24 h). */
        data class Night(val what: String, val hour: Int, val minute: Int, val where: String? = null) : Cmd

        /** "night shift report" / "show the night shift". */
        data class NightList(val where: String? = null) : Cmd

        /** "cancel the night shift". */
        data class NightClear(val where: String? = null) : Cmd
    }

    private const val WHERE = """(?:\s+on\s+(?:my\s+|the\s+)?([\p{L}\p{N}' ]{2,25}?))?"""
    private const val TIME = """(\d{1,2})(?:[:.](\d{2}))?\s*(a\.?m\.?|p\.?m\.?)?"""
    private val SKILL = Regex("""(?is)^(?:(?:hey\s+)?[xj]arvis[,\s]+)?(?:please\s+|can\s+you\s+|now\s+)*(?:do|run)\s+(?:the\s+)?(?:my\s+skill\s+|skill\s+|my\s+)(.+?)[\s.!]*$""")
    private val SKILL_VALUE = Regex("""(?s)^(.+?)\s*(?::|\s[–—-]\s|\s+with\s+)\s*(.+)$""")
    private val SKILL_WHERE = Regex("""(?i)^(.+?)\s+on\s+(?:my\s+|the\s+)?([\p{L}\p{N}' ]{2,25})$""")
    private val SKILLS = Regex("""(?i)^(?:(?:hey\s+)?[xj]arvis[,\s]+)?(?:(?:show|list|tell)\s+(?:me\s+)?|what\s+are\s+)?(?:my|your|the|all|all\s+my)?\s*(?:taught\s+)?skills[\s?.!]*$|^what\s+skills\s+do\s+you\s+(?:know|have)[\s?.!]*$""")
    private val NIGHT_A = Regex("""(?is)^night\s*shift$WHERE\s*[:\-–]\s*(.+?)\s+(?:at|@)\s+$TIME[\s.!]*$""")
    private val NIGHT_B = Regex("""(?is)^night\s*shift$WHERE\s+(?:at|@)\s+$TIME\s*[:\-–]\s*(.+?)[\s.!]*$""")
    private val NIGHT_LIST = Regex("""(?i)^(?:(?:show|tell)\s+(?:me\s+)?|what(?:'s|\s+is)\s+on\s+|what\s+did\s+)?(?:the\s+|my\s+)?night\s*shift(?:\s+(?:report|list|jobs|plan|status|do))?$WHERE[\s?.!]*$""")
    private val NIGHT_CLEAR = Regex("""(?i)^(?:cancel|clear|stop|delete|empty)\s+(?:the\s+|my\s+)?(?:whole\s+)?night\s*shift$WHERE[\s.!]*$""")

    /** 24-hour time from "2", "2:30", "2 am", "11.15 pm"; null if it isn't one. */
    fun hour24(h: Int, ampm: String?): Int? {
        val a = ampm?.lowercase()?.replace(".", "")?.trim()
        return when (a) {
            "am" -> if (h == 12) 0 else h.takeIf { it in 1..11 }
            "pm" -> if (h == 12) 12 else h.takeIf { it in 1..11 }?.plus(12)
            else -> h.takeIf { it in 0..23 }
        }
    }

    private fun night(what: String, h: String, m: String, ampm: String, where: String): Cmd? {
        val hour = hour24(h.toIntOrNull() ?: return null, ampm.ifBlank { null }) ?: return null
        val minute = (m.toIntOrNull() ?: 0).takeIf { it in 0..59 } ?: return null
        val w = what.trim().trimEnd('.', '!')
        if (w.length < 3) return null
        return Cmd.Night(w, hour, minute, where.trim().ifBlank { null })
    }

    private val START = Regex(
        """(?is)^(?:hey\s+jarvis[,\s]+)?(?:big\s+|complex\s+)?task(?:\s+(?:on|for)\s+(?:my\s+|the\s+)?([\p{L}\p{N}' -]{2,30}?))?\s*[:\-–]\s*(.+)$""",
    )
    private val ANSWER = Regex("""(?is)^(?:task\s+)?answer\s*[:\-–]\s*(.+)$""")
    private val STOP = Regex("""(?i)^(?:please\s+)?(?:stop|cancel|end|abort)\s+(?:the\s+|my\s+|this\s+)?task\b[\p{L}\p{N}\s']*[.!]*$""")
    private val IMPLICIT = Regex(
        """(?is)^(?:(?:hey\s+)?[xj]arvis[,\s]+)?(?:please\s+|can\s+you\s+|could\s+you\s+|now\s+)*(?:open|launch|go\s+to)\s+(?:the\s+|my\s+)?""" +
            """(.{2,40}?)(?:\s+app)?(?:\s+(?:on|in)\s+(?:my\s+|the\s+)?([\p{L}\p{N}' ]{2,25}?))?""" +
            """\s*(?:,\s*(?:and\s+)?(?:then\s+)?|\s+and\s+(?:then\s+)?|\s+then\s+|\.\s+)(.{3,})$""",
    )
    private val LEAD = Regex("""(?i)^(?:(?:hey\s+)?[xj]arvis[,\s]+)?(?:please\s+|can\s+you\s+|could\s+you\s+|now\s+)*""")
    /** Doing something in the app (reading, one tap or typing alone stay with the quicker ways). */
    private val ACTION = Regex(
        """(?i)\b(?:connect|disconnect|press|click|select|choose|search|find|play|wait|close|skip|send|upload|download|install|""" +
            """log ?in|sign ?in|scroll|watch|record|save|create|make|stop|change|set|turn|switch|enable|disable|enter|write|add|copy|share|""" +
            """let me know|tell me when|start|pause|subscribe|like|follow|join|book|translate|generate|edit|export)\b""",
    )

    private val STATUS = Regex("""(?i)^(?:task\s+status|status\s+of\s+(?:the\s+)?task|how(?:'s|\s+is)\s+the\s+task(?:\s+going)?)[\s?.!]*$""")

    fun parse(message: String): Cmd? {
        val m = message.trim()
        NIGHT_CLEAR.matchEntire(m)?.let { return Cmd.NightClear(it.groupValues[1].trim().ifBlank { null }) }
        NIGHT_A.matchEntire(m)?.let { g -> return night(g.groupValues[2], g.groupValues[3], g.groupValues[4], g.groupValues[5], g.groupValues[1]) }
        NIGHT_B.matchEntire(m)?.let { g -> return night(g.groupValues[5], g.groupValues[2], g.groupValues[3], g.groupValues[4], g.groupValues[1]) }
        NIGHT_LIST.matchEntire(m)?.let { return Cmd.NightList(it.groupValues[1].trim().ifBlank { null }) }
        SKILLS.matchEntire(m)?.let { return Cmd.Skills }
        SKILL.matchEntire(m)?.let { s -> skill(s.groupValues[1])?.let { return it } }
        STOP.matchEntire(m)?.let { return Cmd.Stop }
        STATUS.matchEntire(m)?.let { return Cmd.Status }
        ANSWER.matchEntire(m)?.let { return Cmd.Answer(it.groupValues[1].trim()) }
        START.matchEntire(m)?.let { s ->
            val goal = s.groupValues[2].trim()
            if (goal.length < 8) return null
            return Cmd.Start(goal, s.groupValues[1].trim().takeIf { it.isNotEmpty() })
        }
        val i = IMPLICIT.matchEntire(m) ?: return null
        if (TAUGHT.containsMatchIn(m)) return null
        if (!ACTION.containsMatchIn(i.groupValues[3])) return null
        val app = i.groupValues[1].trim().trimEnd(',', '.').trim()
        if (app.isEmpty()) return null
        return Cmd.Start(m.replace(LEAD, "").trim(), i.groupValues[2].trim().takeIf { it.isNotEmpty() }, app, implicit = true)
    }

    /** A plain "open X and …" that is really about a skill ("… my skill") stays with the skill command. */
    private val TAUGHT = Regex("""(?i)\bmy\s+skill\b""")

    private fun skill(rest: String): Cmd.Skill? {
        var name = rest.trim()
        var value: String? = null
        SKILL_VALUE.matchEntire(name)?.let { v ->
            name = v.groupValues[1].trim()
            value = v.groupValues[2].trim().trimEnd('.', '!').ifBlank { null }
        }
        var where: String? = null
        SKILL_WHERE.matchEntire(name)?.let { w ->
            name = w.groupValues[1].trim()
            where = w.groupValues[2].trim()
        }
        name = name.trim().trimEnd('.', '!', '?').removeSuffix(" skill").trim()
        val words = name.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (name.length < 2 || words.size > 6) return null
        return Cmd.Skill(name, value, where, raw = rest.trim().trimEnd('.', '!', '?'))
    }
}
