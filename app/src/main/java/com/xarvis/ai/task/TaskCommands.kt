package com.xarvis.ai.task

/**
 * What Rex types (or says) to Task mode, on whichever phone he holds: "task: …" / "task on my S22: …" starts one,
 * "answer: …" replies to its question, "stop the task", "task status". Pure, so it's unit-tested.
 */
object TaskCommands {
    sealed interface Cmd {
        /** [where]: the words naming the phone to do it on ("S22"), or null (here, or the S22 from the benco). */
        data class Start(val goal: String, val where: String?) : Cmd
        data class Answer(val text: String) : Cmd
        data object Stop : Cmd
        data object Status : Cmd
    }

    private val START = Regex(
        """(?is)^(?:hey\s+jarvis[,\s]+)?(?:big\s+|complex\s+)?task(?:\s+(?:on|for)\s+(?:my\s+|the\s+)?([\p{L}\p{N}' -]{2,30}?))?\s*[:\-–]\s*(.+)$""",
    )
    private val ANSWER = Regex("""(?is)^(?:task\s+)?answer\s*[:\-–]\s*(.+)$""")
    private val STOP = Regex("""(?i)^(?:please\s+)?(?:stop|cancel|end|abort)\s+(?:the\s+|my\s+|this\s+)?task\b[\p{L}\p{N}\s']*[.!]*$""")
    private val STATUS = Regex("""(?i)^(?:task\s+status|status\s+of\s+(?:the\s+)?task|how(?:'s|\s+is)\s+the\s+task(?:\s+going)?)[\s?.!]*$""")

    fun parse(message: String): Cmd? {
        val m = message.trim()
        STOP.matchEntire(m)?.let { return Cmd.Stop }
        STATUS.matchEntire(m)?.let { return Cmd.Status }
        ANSWER.matchEntire(m)?.let { return Cmd.Answer(it.groupValues[1].trim()) }
        val s = START.matchEntire(m) ?: return null
        val goal = s.groupValues[2].trim()
        if (goal.length < 8) return null
        return Cmd.Start(goal, s.groupValues[1].trim().takeIf { it.isNotEmpty() })
    }
}
