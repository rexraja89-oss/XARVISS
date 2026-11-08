package com.xarvis.ai.agent

/**
 * Rex asking XARVIS to do something ON his other phone (typed on the benco about the S22): "open WhatsApp
 * on my S22", "open whatsapp on s22 and read my last message", "tap Chats on S22", "type hello on S22",
 * "what's on my S22 screen". XARVIS Hands on that phone does it (link op "act"); reading its screen goes
 * to the brain with the screen's text. Other questions that name the phone ("what's the battery on my
 * S22") are left alone.
 */
object OtherPhoneCommands {
    sealed interface Cmd
    data class Open(val app: String, val then: String? = null) : Cmd
    data class Read(val question: String) : Cmd
    data class Tap(val label: String) : Cmd
    data class Type(val text: String) : Cmd

    private val LEAD = Regex("""(?i)^(?:(?:hey\s+)?[xj]arvis[,\s]+)?(?:please\s+|can you\s+|could you\s+|now\s+)?""")
    private val OPEN = Regex("""(?i)^open\s+(?:the\s+|my\s+)?(.+?)(?:\s+app)?(?:\s+and\s+(?:then\s+)?(.+))?$""")
    private val TAP = Regex("""(?i)^(?:tap|press|click|hit)\s+(?:on\s+)?(?:the\s+)?["“']?(.+?)["”']?(?:\s+button)?$""")
    private val TYPE = Regex("""(?i)^(?:type|write|enter)\s+["“']?(.+?)["”']?$""")
    private val READ_VERB = Regex("""(?i)^(?:read|check|look at|see|scan)\b""")
    private val WHATS_THERE = Regex("""(?i)^(?:tell me\s+)?what(?:'s|\s+is)(?:\s+there|\s+showing|\s+open)?$""")
    private val ABOUT_MESSAGES = Regex("""(?i)\b(?:message|messages|chat|chats|notification|notifications|email|emails|mail|sms)\b""")
    private val ASKING = Regex("""(?i)^(?:what|who|any|read|tell|show)\b""")
    private val SCREEN = Regex("""(?i)\bscreen\b""")

    /** [deviceWords]: the words of the linked phone's name ("galaxy", "s22", "ultra"), not this phone's. */
    fun parse(message: String, deviceWords: Set<String>): Cmd? {
        if (deviceWords.isEmpty()) return null
        val t = message.trim().trimEnd('.', '!', '?').trim()
        val dw = deviceWords.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        val onDevice = Regex("""(?i)\s*\b(?:on|in|from|at)\s+(?:my\s+|the\s+)?(?:(?:$dw)(?:'s)?\s*){1,3}(?:phone|mobile|device)?(?:'s)?(?:\s+screen)?\b""")
        val m = onDevice.find(t)
        if (m == null) {
            // "read my S22 screen", "check the S22 screen"
            val myScreen = Regex("""(?i)\b(?:my|the)\s+(?:(?:$dw)(?:'s)?\s*){1,3}(?:phone'?s?\s+|mobile'?s?\s+)?screen\b""")
            return if (myScreen.containsMatchIn(t) && READ_VERB.containsMatchIn(t.replace(LEAD, ""))) Read(t) else null
        }
        val rest = t.replaceRange(m.range, " ").toString().replace(Regex("""\s+"""), " ").trim().replace(LEAD, "").trim()
        OPEN.matchEntire(rest)?.let { o ->
            return Open(o.groupValues[1].trim(), o.groupValues[2].trim().ifEmpty { null })
        }
        return action(rest, t)
    }

    /** What to do after opening an app ("… and read my last message"): tap, type, or read. */
    fun followUp(text: String): Cmd = action(text.trim().replace(LEAD, "").trim(), text) ?: Read(text)

    private fun action(rest: String, original: String): Cmd? {
        TAP.matchEntire(rest)?.let { return Tap(it.groupValues[1].trim()) }
        TYPE.matchEntire(rest)?.let { return Type(it.groupValues[1]) }
        val readish = READ_VERB.containsMatchIn(rest) || SCREEN.containsMatchIn(original) || WHATS_THERE.matches(rest) ||
            (ABOUT_MESSAGES.containsMatchIn(rest) && ASKING.containsMatchIn(rest))
        return if (readish) Read(original) else null
    }
}
