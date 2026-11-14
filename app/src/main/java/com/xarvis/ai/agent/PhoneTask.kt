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
    /** "open Chrome, go to … and press Run all": an app opened, then more steps (comma, "then", "and"). */
    private val OPEN_THEN = Regex("""(?i)^$LEAD(?:open|launch|start)\s+(?:the\s+)?\S+(?:\s+\S+)?\s*(?:,|;|\band then\b|\bthen\b|\band\b)\s*\S+""")
    /** A web link plus something to do on it ("go to colab…/x and press Run all"). */
    private val LINK_JOB = Regex("""(?i)(?:https?://|www\.|\b[a-z0-9-]+\.(?:com|org|net|io|ai|in|ae|co)/)\S*""")
    private val STEP_WORDS = Regex("""(?i)\b(?:press|tap|click|choose|select|pick|run|sign in|log in|fill|type|enter|scroll|download|upload|go to)\b""")
    private val EXPLICIT = Regex("""(?i)^$LEAD(?:do (?:this|it) for me|use my phone|use the phone|take over)\s*[:,\-–]?\s*(.+)$""")

    /** [otherDeviceWords]: the linked phone's name words ("galaxy", "s22", "ultra" on the benco). */
    fun parse(message: String, otherDeviceWords: Set<String>): Task? {
        val t = message.trim().trim('"', '\'', '“', '”').trim().trimEnd('.', '!', '?').trim()
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
        // Rex, 15 Nov 2026: "open Chrome, go to colab…, choose T4 GPU in Runtime and press Run all" went to the chat
        // brain, which only opened Chrome. Several steps after "open X", or a link with steps on it, is a job.
        if (OPEN_THEN.containsMatchIn(goal)) return Task(goal, other)
        if (LINK_JOB.containsMatchIn(goal) && STEP_WORDS.containsMatchIn(goal)) return Task(goal, other)
        return null
    }
}
