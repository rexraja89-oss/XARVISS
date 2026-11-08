package com.xarvis.ai.agent

/**
 * A job for XARVIS to do by itself in a phone's apps (PhoneAgent): "send my last photo to my wife on
 * WhatsApp", "check if Ali replied on imo", "play Arijit Singh on YouTube", "turn on dark mode in settings" —
 * an action word first and an app named — or anything after "do this for me:". "… on my S22" means the linked
 * phone. Questions ("how do I turn on dark mode in settings") and plain calls ("call Ali on WhatsApp", which
 * has its own path) are not jobs.
 */
object PhoneTask {
    data class Task(val goal: String, val onOtherPhone: Boolean)

    private const val APPS = "whatsapp|imo|telegram|instagram|insta|facebook|fb|messenger|youtube|yt|chrome|google|gmail|" +
        "settings|gallery|photos|google photos|spotify|maps|google maps|play store|linkedin|twitter|snapchat|tiktok|" +
        "messages|contacts|calendar|clock|files|my files|camera|netflix|amazon|flipkart|swiggy|zomato|uber|ola"
    private val ON_APP = Regex("""(?i)\b(?:on|in|using|via|from|through|inside)\s+(?:my\s+|the\s+)?(?:$APPS)\b(?:\s+app)?""")
    private val LEAD = """(?:(?:hey\s+)?[xj]arvis[,\s]+)?(?:please\s+|can you\s+|could you\s+|go\s+(?:and\s+)?|now\s+)*"""
    private val ACTION = Regex(
        """(?i)^$LEAD(?:send|check|reply|forward|share|post|search|find|look up|look for|turn on|turn off|switch on|""" +
            """switch off|enable|disable|set|change|play|watch|read|see if|see what|tell me if|tell me what|tell me who|""" +
            """find out|show me|message|text|open\s+\S+(?:\s+\S+)?\s+and)\b""",
    )
    private val EXPLICIT = Regex("""(?i)^$LEAD(?:do (?:this|it) for me|use my phone|use the phone|take over)\s*[:,\-–]?\s*(.+)$""")

    /** [otherDeviceWords]: the linked phone's name words ("galaxy", "s22", "ultra" on the benco). */
    fun parse(message: String, otherDeviceWords: Set<String>): Task? {
        val t = message.trim().trimEnd('.', '!', '?').trim()
        var goal = t
        var other = false
        if (otherDeviceWords.isNotEmpty()) {
            val dw = otherDeviceWords.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
            Regex("""(?i)\s*\b(?:on|in|from|at)\s+(?:my\s+|the\s+)?(?:(?:$dw)(?:'s)?\s*){1,3}(?:phone|mobile)?\b""").find(t)?.let {
                goal = t.replaceRange(it.range, " ").toString().replace(Regex("""\s+"""), " ").trim()
                other = true
            }
        }
        EXPLICIT.matchEntire(goal)?.let { return Task(it.groupValues[1].trim(), other) }
        if (ACTION.containsMatchIn(goal) && ON_APP.containsMatchIn(goal)) return Task(goal, other)
        return null
    }
}
