package com.xarvis.ai.watch

/** "watch the room [for 3 hours] [on my S22]", "stop watching", "photo of the room", "watch status". */
object WatchCommands {
    sealed class Cmd {
        abstract val remote: Boolean
        data class Start(val hours: Int, override val remote: Boolean) : Cmd()
        data class Stop(override val remote: Boolean) : Cmd()
        data class Snap(override val remote: Boolean) : Cmd()
        data class Status(override val remote: Boolean) : Cmd()
    }

    const val DEFAULT_HOURS = 8
    const val MAX_HOURS = 24

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)
    private val LEAD = """^(?:(?:hey |ok |okay )?(?:xarvis|jarvis|zarvis)[,.!]?\s+)?(?:please\s+|can you\s+)?"""
    private val ON_PHONE = """(?:\s+(?:on|from|with|through)\s+(?:my\s+|the\s+)?(s22|s 22|galaxy|samsung|other phone|main phone))?"""
    private val FOR = """(?:\s+for\s+(\d{1,2})\s*(?:hours?|hrs?|h))?"""
    private val TAIL = """(?:\s+(?:please|now|sir))*[.!?]*$"""

    private val START = re(LEAD + """(?:watch|guard|monitor|keep an eye on)\s+(?:the\s+|my\s+|this\s+)?(?:room|house|home|place|flat|office)""" + ON_PHONE + FOR + ON_PHONE + TAIL)
    private val START2 = re(LEAD + """(?:start\s+watching|watch\s+mode\s+on|turn\s+on\s+watch\s+mode|start\s+watch\s+mode)""" + ON_PHONE + FOR + ON_PHONE + TAIL)
    private val STOP = re(LEAD + """(?:stop\s+watching|watch\s+mode\s+off|turn\s+off\s+watch\s+mode|stop\s+watch\s+mode|stop\s+monitoring)(?:\s+(?:the\s+)?room)?""" + ON_PHONE + TAIL)
    private val SNAP = re(LEAD + """(?:(?:send|show)\s+me\s+(?:a\s+)?(?:photo|picture|pic)\s+of\s+the\s+room|(?:room|watch)\s+(?:photo|picture|snapshot)|what(?:'s| is)\s+happening\s+in\s+the\s+room)""" + ON_PHONE + TAIL)
    private val STATUS = re(LEAD + """(?:watch\s+(?:mode\s+)?status|are\s+you\s+watching(?:\s+the\s+room)?)""" + ON_PHONE + TAIL)

    fun parse(message: String): Cmd? {
        val m = message.trim()
        if (m.length > 100) return null
        (START.matchEntire(m) ?: START2.matchEntire(m))?.let { r ->
            val g = r.groupValues
            val hours = g[2].toIntOrNull()?.coerceIn(1, MAX_HOURS) ?: DEFAULT_HOURS
            return Cmd.Start(hours, g[1].isNotBlank() || g[3].isNotBlank())
        }
        STOP.matchEntire(m)?.let { return Cmd.Stop(it.groupValues[1].isNotBlank()) }
        SNAP.matchEntire(m)?.let { return Cmd.Snap(it.groupValues[1].isNotBlank()) }
        STATUS.matchEntire(m)?.let { return Cmd.Status(it.groupValues[1].isNotBlank()) }
        return null
    }

    /** As sent to the other phone (link op `task`, action `watch`). */
    fun op(cmd: Cmd): Pair<String, Int> = when (cmd) {
        is Cmd.Start -> "start" to cmd.hours
        is Cmd.Stop -> "stop" to 0
        is Cmd.Snap -> "snap" to 0
        is Cmd.Status -> "status" to 0
    }
}
