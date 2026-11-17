package com.xarvis.ai.rescue

/**
 * "rescue my S22" / "my S22 is stuck" → Rescue; "wake up my S22" / "ask S22 Ultra to wake up" → Wake;
 * "call for help on my S22" → Help; "stop the rescue" → Stop. A phone word is required for Wake and Help, so
 * "wake me up at 7" stays an alarm.
 */
object RescueCommands {
    enum class Cmd(val op: String) {
        Rescue("rescue"), Wake("wake"), Help("help"), Stop("stop"),
        /** "restart my S22": first only the warning (what a restart means from afar)… */
        Restart("restart_ask"),
        /** …"yes, restart my S22" restarts it. */
        RestartYes("restart"),
    }

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)
    private const val LEAD = """^(?:(?:hey |ok |okay )?(?:xarvis|jarvis|zarvis)[,.!]?\s+)?(?:please\s+|can you\s+|could you\s+)?"""
    private const val PHONE = """(?:my\s+|the\s+)?(?:galaxy\s+)?(?:s\s?22(?:\s+ultra)?|galaxy|samsung|other\s+phone|main\s+phone|phone)(?:'s)?"""
    private const val TAIL = """(?:\s+(?:please|now|sir|again))*[.!?]*$"""

    private val RESCUE = listOf(
        re(LEAD + """(?:rescue|unstick|unfreeze|help)\s+""" + PHONE + TAIL),
        re(LEAD + """(?:get|bring)\s+""" + PHONE + """\s+(?:unstuck|back|out)""" + TAIL),
        re(LEAD + PHONE + """\s+(?:is|got|seems)\s+(?:stuck|frozen|blocked)""" + TAIL),
        re(LEAD + """rescue\s+mode(?:\s+on\s+""" + PHONE + """)?""" + TAIL),
    )
    private val WAKE = listOf(
        re(LEAD + """wake\s+(?:up\s+)?""" + PHONE + """(?:\s+up)?""" + TAIL),
        re(LEAD + """(?:ask|tell)\s+""" + PHONE + """\s+to\s+wake\s+up""" + TAIL),
        re(LEAD + PHONE + """[,]?\s+wake\s+up""" + TAIL),
        re(LEAD + """turn\s+on\s+""" + PHONE + """\s+screen""" + TAIL),
    )
    private val HELP = listOf(
        re(LEAD + """call\s+(?:out\s+)?for\s+help\s+(?:on|from|through)\s+""" + PHONE + TAIL),
        re(LEAD + PHONE + """\s+call\s+(?:out\s+)?for\s+help""" + TAIL),
        re(LEAD + """(?:ask|tell)\s+(?:someone|somebody|anyone)\s+to\s+(?:turn|flip|pick\s+up|uncover)\s+""" + PHONE + """(?:\s+(?:over|up|face\s+up))?""" + TAIL),
    )
    private val RESTART = re(LEAD + """(yes[,.!]?\s+(?:please\s+)?|go ahead[,.!]?\s+(?:and\s+)?)?(?:restart|reboot)\s+""" + PHONE + TAIL)
    private val STOP = re(LEAD + """(?:stop|cancel|end)\s+(?:the\s+)?(?:rescue|rescuing)(?:\s+(?:on\s+)?""" + PHONE + """)?""" + TAIL)

    fun parse(message: String): Cmd? {
        val m = message.trim()
        if (m.length > 80) return null
        return when {
            STOP.matches(m) -> Cmd.Stop
            RESTART.matches(m) -> if (RESTART.matchEntire(m)!!.groupValues[1].isNotBlank()) Cmd.RestartYes else Cmd.Restart
            HELP.any { it.matches(m) } -> Cmd.Help
            WAKE.any { it.matches(m) } -> Cmd.Wake
            RESCUE.any { it.matches(m) } -> Cmd.Rescue
            else -> null
        }
    }
}
