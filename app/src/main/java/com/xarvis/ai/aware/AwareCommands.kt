package com.xarvis.ai.aware

/**
 * Asking XARVIS about all of Rex's devices at once: "status report", "sitrep", "what's going on", "how are my
 * phones", and "what's new on my S22" / "any missed calls" (its recent notifications). Other device questions
 * ("is my S22 charging?") go to the brain with the live board ([wantsBoard]). Pure, tested.
 */
object AwareCommands {
    sealed interface Cmd {
        data object Report : Cmd

        /** [where]: the device words ("S22"), null = every other device. [callsOnly]: just missed calls. */
        data class Notes(val where: String?, val callsOnly: Boolean = false) : Cmd
    }

    private const val LEAD = """(?:(?:hey\s+)?[xj]arvis[,\s]+)?(?:please\s+|can\s+you\s+|now\s+)*"""
    private val REPORT = Regex(
        """(?i)^$LEAD(?:give\s+me\s+(?:a\s+|the\s+|your\s+)?|show\s+(?:me\s+)?(?:the\s+)?|run\s+(?:a\s+)?)?""" +
            """(?:(?:full\s+)?status(?:\s+(?:report|update|check))?|situation(?:\s+report)?|sit\s?rep|(?:all\s+)?systems?(?:\s+status|\s+check)?|""" +
            """how\s+are\s+(?:my|all\s+my)\s+(?:phones|devices)(?:\s+doing)?|what'?s\s+(?:going\s+on|happening)(?:\s+with\s+(?:my|all\s+my)\s+(?:phones|devices))?|""" +
            """check\s+(?:on\s+)?(?:all\s+)?my\s+(?:phones|devices)|(?:status|state)\s+of\s+(?:all\s+)?(?:my\s+)?(?:phones|devices|everything))""" +
            """(?:,?\s+(?:sir|please|now))?[\s?.!]*$""",
    )
    private const val ON = """(?:\s+(?:on|from)\s+(?:my\s+|the\s+)?([\p{L}\p{N}' ]{2,25}?))?"""
    private val NOTES = Regex(
        """(?i)^$LEAD(?:any\s+|show\s+(?:me\s+)?(?:the\s+)?|read\s+(?:me\s+)?(?:the\s+)?|what\s+are\s+the\s+|check\s+(?:the\s+)?)?(?:new\s+|recent\s+|latest\s+)?""" +
            """(notifications|missed\s+calls?)$ON[\s?.!]*$""",
    )
    private val WHATS_NEW = Regex("""(?i)^$LEAD(?:what'?s|anything)\s+new\s+on\s+(?:my\s+|the\s+)?([\p{L}\p{N}' ]{2,25}?)[\s?.!]*$""")

    fun parse(message: String): Cmd? {
        val m = message.trim()
        if (REPORT.matches(m)) return Cmd.Report
        NOTES.matchEntire(m)?.let { g ->
            return Cmd.Notes(g.groupValues[2].trim().ifBlank { null }, callsOnly = g.groupValues[1].lowercase().startsWith("missed"))
        }
        WHATS_NEW.matchEntire(m)?.let { return Cmd.Notes(it.groupValues[1].trim()) }
        return null
    }

    private val DEVICE_Q = Regex(
        """(?i)\b(?:battery|charg\w*|online|offline|locked|unlock\w*|notification\w*|missed\s+calls?|where\s+is\s+my|my\s+(?:phones|devices)|""" +
            """status|storage|signal|wi-?fi|mobile\s+data|in\s+use|last\s+used|which\s+phone)\b""",
    )

    /** Whether a question for the brain should come with the live board: it names a device or asks about one. */
    fun wantsBoard(message: String, deviceWords: List<String>): Boolean {
        if (DEVICE_Q.containsMatchIn(message)) return true
        val lower = message.lowercase()
        return deviceWords.any { w -> w.length >= 3 && Regex("""\b${Regex.escape(w.lowercase())}\b""").containsMatchIn(lower) }
    }
}
