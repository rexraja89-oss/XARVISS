package com.xarvis.ai.agent

/**
 * The Job Hunter by voice (caught before the brain, so it never guesses): "hunt jobs [in Nigeria]", "full job
 * hunt", "show my job drafts", "send 10 job applications", "stop sending", "trust agency X" / "block X.com",
 * "any job replies?", "job hunter status", "check LinkedIn jobs for Kazakhstan". Null for anything else
 * (e.g. "find jobs on Naukri" still opens the site, as before).
 */
object JobCommands {

    sealed interface Cmd {
        data class Hunt(val country: String?, val full: Boolean) : Cmd
        data object Drafts : Cmd
        data class Send(val count: Int?) : Cmd
        data object StopSending : Cmd
        data class Trust(val name: String, val trust: Boolean?) : Cmd
        data object Status : Cmd
        data object Replies : Cmd
        data class LinkedIn(val country: String?) : Cmd
    }

    // Number words first: SEND below is built from them (object properties initialise in order).
    private val NUMBERS = listOf(
        "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen",
        "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "twenty five", "thirty", "forty", "fifty",
    )
    private val NUMBER_VALUES = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 25, 30, 40, 50)
    private fun numberWords() = NUMBERS.sortedByDescending { it.length }.joinToString("|") { it.replace(" ", "\\s+") }

    private const val LEAD = """^(?:(?:hey\s+)?[xj]arvis[,\s]+)?(?:please\s+|now\s+|can\s+you\s+|could\s+you\s+)*"""
    private val SITES = Regex("""(?i)\b(naukri|indeed|bayt|gulftalent|gulf\s+talent|monster|glassdoor)\b""")

    private val HUNT = Regex(
        """(?i)$LEAD(?:start|run|do|begin)?\s*(?:a\s+|the\s+|my\s+)?(full\s+|quick\s+|big\s+)?""" +
            """(?:job\s+hunt(?:ing|er)?|hunt\s+(?:for\s+)?(?:new\s+)?(?:jobs?|vacancies)|find\s+(?:me\s+)?(?:new\s+)?(?:jobs?|vacancies)|""" +
            """search\s+(?:for\s+)?(?:new\s+)?(?:jobs?|vacancies)|look\s+for\s+(?:new\s+)?(?:jobs?|vacancies))""" +
            """(?:\s+(?:for\s+me))?(?:\s+(?:in|for|at)\s+(.+?))?(?:\s+(?:now|today|please|for\s+me))?$""",
    )
    private val DRAFTS = Regex(
        """(?i)$LEAD(?:(?:show|open|see|check|let\s+me\s+see)\s+(?:me\s+)?(?:my\s+|the\s+)?(?:job\s+(?:drafts?|applications?|hunter)|drafts?\s+for\s+jobs|application\s+drafts?)|job\s+drafts?|job\s+hunter)$""",
    )
    private val SEND = Regex(
        """(?i)$LEAD(?:send|proceed\s+(?:and\s+)?send|go\s+ahead\s+and\s+send)\s+(?:the\s+)?(\d{1,3}|all|${numberWords()})?\s*(?:of\s+(?:the|my)\s+)?(?:approved\s+)?(?:job\s+)?(?:applications?|emails?|drafts?|cvs?)(?:\s+now)?$""",
    )
    private val STOP = Regex("""(?i)$LEAD(?:stop|cancel|pause)\s+sending(?:\s+(?:the\s+)?(?:job\s+)?(?:applications?|emails?))?$""")
    private val TRUST = Regex(
        """(?i)$LEAD(trust|block|untrust|unblock|distrust|forget)\s+(?:the\s+)?(?:agency|recruiter|recruitment\s+agency|company|employer)\s+(.{2,60})$""",
    )
    private val TRUST_DOMAIN = Regex("""(?i)$LEAD(trust|block|untrust|unblock)\s+([\w.-]+\.[a-z]{2,24}|[\w.+-]+@[\w.-]+\.[a-z]{2,24})$""")
    private val STATUS = Regex(
        """(?i)$LEAD(?:job\s+hunter\s+status|job\s+status|how\s+many\s+(?:job\s+)?applications?\s+(?:have|did)\s+(?:i|you|we)\s+sen[dt](?:\s+today)?|how\s+is\s+(?:my\s+)?(?:the\s+)?job\s+hunt(?:\s+going)?)$""",
    )
    private val REPLIES = Regex(
        """(?i)$LEAD(?:any\s+(?:job\s+)?repl(?:y|ies)(?:\s+from\s+(?:employers|companies|agencies))?|check\s+(?:for\s+)?(?:job\s+)?repl(?:y|ies)|did\s+any(?:one|body)\s+repl(?:y|ied)(?:\s+to\s+my\s+applications?)?)$""",
    )
    private val LINKEDIN = Regex(
        """(?i)$LEAD(?:check|browse|search|look\s+(?:at|through))\s+(?:my\s+)?linkedin(?:\s+(?:app\s+)?(?:for\s+)?jobs?)?(?:\s+(?:for|in)\s+(.+?))?$""",
    )

    private fun count(word: String): Int? {
        val w = word.trim().lowercase().replace(Regex("\\s+"), " ")
        if (w.isEmpty() || w == "all") return null
        w.toIntOrNull()?.let { return it }
        val i = NUMBERS.indexOf(w)
        return if (i >= 0) NUMBER_VALUES[i] else null
    }

    private val EVERYWHERE = Regex("""(?i)^(all(\s+(my|the|33))?\s+countries|everywhere|worldwide|the\s+world|all\s+over(\s+the\s+world)?)$""")

    fun parse(message: String): Cmd? {
        val t = message.trim().trimEnd('.', '!', '?').trim()
        LINKEDIN.matchEntire(t)?.let { return Cmd.LinkedIn(it.groupValues[1].trim().ifBlank { null }) }
        if (SITES.containsMatchIn(t) || Regex("""(?i)\blinkedin\b""").containsMatchIn(t)) return null
        DRAFTS.matchEntire(t)?.let { return Cmd.Drafts }
        STOP.matchEntire(t)?.let { return Cmd.StopSending }
        SEND.matchEntire(t)?.let { return Cmd.Send(count(it.groupValues[1])) }
        STATUS.matchEntire(t)?.let { return Cmd.Status }
        REPLIES.matchEntire(t)?.let { return Cmd.Replies }
        (TRUST.matchEntire(t) ?: TRUST_DOMAIN.matchEntire(t))?.let { m ->
            val trust = when (m.groupValues[1].lowercase()) {
                "trust" -> true
                "block", "distrust" -> false
                else -> null
            }
            return Cmd.Trust(m.groupValues[2].trim().trimEnd('.'), trust)
        }
        HUNT.matchEntire(t)?.let { m ->
            val place = m.groupValues[2].trim().removePrefix("the ").trim()
            val full = m.groupValues[1].trim().equals("full", true) || m.groupValues[1].trim().equals("big", true) || EVERYWHERE.matches(place)
            val country = place.takeIf { it.isNotEmpty() && !EVERYWHERE.matches(place) }
            // (A place that isn't one of Rex's countries is passed on, so XARVIS can say so.)
            return Cmd.Hunt(country, full)
        }
        return null
    }
}
