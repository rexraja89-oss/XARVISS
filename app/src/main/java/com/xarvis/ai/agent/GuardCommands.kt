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

    private val OK_WORD = Regex("""(?i)\b(?:all ?right|alright|okay|ok)\b""")
    private val NAME = Regex("""(?i)\b(?:[xj]arvis|jervis|harvis|xavis|jarvish|charvis|service)\b""")

    /** "It's alright XARVIS" as speech recognition writes it ("it's all right Jarvis", "its alright jervis"…). */
    fun isDisarmPhrase(text: String): Boolean = OK_WORD.containsMatchIn(text) && NAME.containsMatchIn(text)

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
