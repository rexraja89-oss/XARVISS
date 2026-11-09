package com.xarvis.ai.agent

/**
 * Guard mode by voice: "XARVIS, guard my phone" / "guard mode on" arms it (10-second countdown); "guard mode
 * off" / "stop guarding" asks for Rex's fingerprint to turn it off. Null for anything else.
 */
object GuardCommands {
    private const val LEAD = """^(?:(?:hey\s+)?[xj]arvis[,\s]+)?(?:please\s+|now\s+)?"""
    private val ON = Regex(
        """(?i)$LEAD(?:(?:guard|protect|watch)\s+(?:my\s+|the\s+|this\s+)?(?:phone|mobile|device)|guard\s+mode\s+on|""" +
            """(?:turn\s+on|switch\s+on|start|enable|activate)\s+(?:the\s+)?guard(?:\s+mode)?)$""",
    )
    private val OFF = Regex(
        """(?i)$LEAD(?:guard\s+mode\s+off|stop\s+guarding(?:\s+(?:my\s+)?(?:phone|mobile))?|""" +
            """(?:turn\s+off|switch\s+off|stop|disable|deactivate)\s+(?:the\s+)?guard(?:\s+mode)?)$""",
    )

    private val OK_WORD = Regex("""(?i)\b(?:all ?right|alright|okay|ok)\b|ऑल ?राइट|आल ?राइट|ओके""")
    private val ITS_ALRIGHT = Regex("""(?i)\b(?:it'?s|it\s+is)\s+(?:all ?right|alright|ok(?:ay)?)\b|इट्स\s*(?:ऑल ?राइट|आल ?राइट|ओके)""")
    private val NAME = Regex(
        """(?i)\b(?:[xj]arvis|jervis|harvis|xavis|jarvish|charvis|service|travis|javis|jarvas|jarves|garvis|jaavis)\b|जार्विस|जारविस|जर्विस""",
    )

    /**
     * "It's alright XARVIS" as speech recognition writes it ("it's all right Jarvis", "its alright Travis", in Devanagari
     * when the phone listens in Hindi…). "It's alright" alone is enough: it only opens the fingerprint prompt, and the
     * fingerprint is the real lock.
     */
    fun isDisarmPhrase(text: String): Boolean = ITS_ALRIGHT.containsMatchIn(text) || (OK_WORD.containsMatchIn(text) && NAME.containsMatchIn(text))

    private val STOP_ALARM = Regex(
        """(?i)$LEAD(?:stop|turn\s+off|switch\s+off|silence|shut\s+off|end)\s+(?:the\s+)?(?:alarm|siren)""" +
            """(?:\s+(?:on|of)\s+(?:my\s+|the\s+)?[\w' ]{1,30})?(?:\s+now)?$""",
    )
    private val SOUND_ALARM = Regex(
        """(?i)$LEAD(?:sound|ring|trigger|raise|fire)\s+(?:the\s+)?(?:alarm|siren)""" +
            """(?:\s+(?:on|of)\s+(?:my\s+|the\s+)?[\w' ]{1,30})?(?:\s+now)?$""",
    )
    private val WARN = Regex("""(?i)$LEAD(?:tell|warn)\s+(?:the\s+)?(?:intruder|thief|stranger)\s*[:,\-–]?\s+(.+)$""")
    private val SAY_THERE = Regex("""(?i)$LEAD(?:say|speak)\s+(?:on|through|from)\s+(?:my\s+|the\s+)?[\w']+(?:\s+[\w']+){0,2}\s*[:,\-–]\s*(.+)$""")

    /** Guard remote, from the other phone: ("stop" | "panic" | "say", text) — or null. */
    fun remote(message: String): Pair<String, String?>? {
        val t = message.trim().trimEnd('.', '!', '?').trim()
        STOP_ALARM.matchEntire(t)?.let { return "stop" to null }
        SOUND_ALARM.matchEntire(t)?.let { return "panic" to null }
        (WARN.matchEntire(t) ?: SAY_THERE.matchEntire(t))?.let { return "say" to it.groupValues[1].trim() }
        return null
    }

    /** true = arm, false = disarm, null = not about guard mode. */
    fun parse(message: String): Boolean? {
        val t = message.trim().trimEnd('.', '!', '?').trim()
        return when {
            ON.matches(t) -> true
            OFF.matches(t) -> false
            else -> null
        }
    }
}
