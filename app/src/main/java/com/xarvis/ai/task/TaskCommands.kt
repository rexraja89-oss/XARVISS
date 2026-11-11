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
        STOP.matchEntire(m)?.let { return Cmd.Stop }
        STATUS.matchEntire(m)?.let { return Cmd.Status }
        ANSWER.matchEntire(m)?.let { return Cmd.Answer(it.groupValues[1].trim()) }
        START.matchEntire(m)?.let { s ->
            val goal = s.groupValues[2].trim()
            if (goal.length < 8) return null
            return Cmd.Start(goal, s.groupValues[1].trim().takeIf { it.isNotEmpty() })
        }
        val i = IMPLICIT.matchEntire(m) ?: return null
        if (!ACTION.containsMatchIn(i.groupValues[3])) return null
        val app = i.groupValues[1].trim().trimEnd(',', '.').trim()
        if (app.isEmpty()) return null
        return Cmd.Start(m.replace(LEAD, "").trim(), i.groupValues[2].trim().takeIf { it.isNotEmpty() }, app, implicit = true)
    }
}
