package com.xarvis.ai.agent

/**
 * "Update everything" in plain words (13 Nov 2026, Rex: "update all at one time"): XARVIS, XARVIS Remote and the
 * Updater on this phone ("update everything", "update xarvis"), on a linked phone ("update my S22", "update everything
 * on my benco") or on both ("update both phones"). XARVIS Updater does the work with the small updates. Pure, tested.
 */
object UpdateCommands {
    /**
     * [where]: words naming the phone ("S22"), null = this one. [both]: this phone and the linked ones.
     * [phoneOnly]: the words were just a name ("update my S22") — only a command if they name one of Rex's phones
     * ("update my CV" is for the chat).
     */
    data class Cmd(val where: String?, val both: Boolean = false, val phoneOnly: Boolean = false)

    private val UPDATE = Regex(
        """(?i)^(?:(?:hey\s+)?[xj]arvis[,\s]+)?(?:please\s+|can\s+you\s+|could\s+you\s+|now\s+)*(?:update|upgrade)\s+""" +
            """(.{2,45}?)(?:\s+(?:now|please|for\s+me))?[\s.!?]*$""",
    )
    private const val ON = """(?:\s+on\s+(?:my\s+|the\s+)?([\p{L}\p{N}' ]{2,25}?))?"""
    private val EVERYTHING = Regex(
        """(?i)^(?:all\s+)?(?:the\s+|my\s+)?(?:xarvis(?:\s+apps?)?|apps|everything|all(?:\s+(?:the\s+|my\s+)?(?:xarvis\s+)?apps)?)$ON$""",
    )
    private val BOTH = Regex(
        """(?i)^(?:(?:the\s+|my\s+)?(?:both\s+(?:my\s+|the\s+)?phones|all\s+(?:my\s+|the\s+)?phones|phones)|(?:everything|xarvis|all)\s+on\s+(?:both|all)(?:\s+(?:my\s+)?phones)?)$""",
    )
    private val HERE = Regex("""(?i)^(?:my\s+|this\s+|the\s+)?phone$""")
    /** A phone's name: up to three words ("S22", "s22 ultra", "Rex's S22 Ultra"). */
    private val NAME = Regex("""(?i)^(?:my\s+|the\s+)?([\p{L}\p{N}']{2,25}(?:\s+[\p{L}\p{N}']{1,25}){0,2})$""")

    fun parse(message: String): Cmd? {
        val what = UPDATE.matchEntire(message.trim())?.groupValues?.get(1)?.trim() ?: return null
        if (BOTH.matches(what)) return Cmd(null, both = true)
        if (HERE.matches(what)) return Cmd(null)
        EVERYTHING.matchEntire(what)?.let { return Cmd(it.groupValues[1].trim().ifBlank { null }) }
        NAME.matchEntire(what)?.let { return Cmd(it.groupValues[1].trim(), phoneOnly = true) }
        return null
    }
}
