package com.xarvis.ai.agent

/**
 * Hands-free control by voice (Rex, 15 Nov 2026: "off the alarm", "receive this call", "disconnect this call", "open
 * YouTube… scroll for me, read out loud the titles… click on request… till the advertisement finish"). Short, exact
 * commands, read here (never left to the brain) and done by XARVIS Hands on this phone.
 */
object PhoneControl {

    sealed class Cmd {
        object AlarmOff : Cmd()
        object Snooze : Cmd()
        object Answer : Cmd()
        object HangUp : Cmd()
        /** Open YouTube, with a search when [query] isn't blank. */
        data class YouTube(val query: String) : Cmd()
        data class Scroll(val down: Boolean) : Cmd()
        object Back : Cmd()
        object Read : Cmd()
        /** Play the [index]th video (1-based) on screen, or the one whose title has [words]. */
        data class Play(val index: Int?, val words: String) : Cmd()
        object SkipAd : Cmd()
    }

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val LEAD = """^(?:(?:hey |ok |okay )?(?:xarvis|jarvis|zarvis)[,.!]?\s+)?(?:please\s+|can you\s+|could you\s+)?"""
    private val TAIL = """(?:\s+(?:please|for me|now|sir|right now))*[.!?]*$"""
    private fun cmd(p: String) = re(LEAD + p + TAIL)

    private val ALARM_OFF = cmd("""(?:(?:turn|switch|shut|put)\s+(?:the\s+|this\s+|that\s+)?alarm\s+off|(?:stop|off|dismiss|cancel|kill|silence|turn off|switch off|shut off|shut up|close)\s+(?:the\s+|this\s+|that\s+|my\s+)?alarm|alarm\s+(?:off|band karo|band kar do|bandh karo|stop karo|stop)|alarm band)""")
    private val SNOOZE = cmd("""(?:snooze(?:\s+(?:the\s+|this\s+|my\s+)?alarm)?|snooze it|alarm snooze karo)""")
    private val ANSWER = cmd("""(?:receive|answer|accept|attend|pick up|pickup|pick)\s+(?:the\s+|this\s+|that\s+|my\s+)?(?:phone\s+)?call|(?:pick it up|answer it)|call\s+(?:receive|utha|uthao|utha lo|attend)\s*(?:karo|kar lo|lo)?|phone\s+(?:utha|uthao|utha lo)""")
    private val HANG_UP = cmd("""(?:disconnect|end|hang up|hangup|cut|drop|reject|decline|stop)\s+(?:the\s+|this\s+|that\s+|my\s+)?(?:phone\s+)?call|hang up|(?:call|phone)\s+(?:kaat do|kaato|kat do|cut karo|cut kar do|band karo|disconnect karo)""")
    private val YOUTUBE = cmd("""(?:open|launch|start|go to)\s+youtube(?:\s+and\s+(?:search(?:\s+for)?|find|look for|play)\s+(.+))?|(?:search|find|look for|play)\s+(?:for\s+)?(.+?)\s+(?:on|in)\s+youtube|youtube\s+(?:kholo|open karo)""")
    private val SCROLL = cmd("""(?:scroll|swipe|go)\s*(down|up)?|(?:show me\s+)?(?:more|next)(?: videos?)?|niche karo|upar karo""")
    private val BACK = cmd("""(?:go\s+)?back|peeche|wapas""")
    private val READ = cmd("""(?:read|tell me|say)\s+(?:out\s+)?(?:loud\s+)?(?:me\s+)?(?:the\s+|all\s+the\s+)?(?:titles?|videos?|screen|names?|what(?:'s| is) on (?:the )?screen)(?:\s+(?:you see|on (?:the )?screen|out loud|aloud))?|what do you see|what(?:'s| is) on (?:the )?screen|read (?:it )?(?:out )?(?:loud|aloud)""")
    private val PLAY = cmd("""(?:play|open|click|tap|watch|start)\s+(?:on\s+)?(?:the\s+)?(?:video\s+)?(first|second|third|fourth|fifth|1st|2nd|3rd|4th|5th|last|number\s+\d|no\.?\s*\d|\d|one|two|three|four|five)(?:\s+(?:one|video|result))?|(?:play|click|tap|open|watch)\s+(?:on\s+)?(?:the\s+)?(?:video\s+)?(?:called\s+|about\s+|with\s+)?(.{3,60})""")
    private val SKIP = cmd("""skip(?:\s+(?:the\s+|this\s+)?ads?)?|(?:ad|ads)\s+skip\s*(?:karo)?""")

    private val ORDINALS = mapOf(
        "first" to 1, "1st" to 1, "one" to 1, "second" to 2, "2nd" to 2, "two" to 2, "third" to 3, "3rd" to 3, "three" to 3,
        "fourth" to 4, "4th" to 4, "four" to 4, "fifth" to 5, "5th" to 5, "five" to 5, "last" to -1,
    )

    fun parse(message: String): Cmd? {
        val m = message.trim()
        if (m.length > 120) return null
        if (ALARM_OFF.matches(m)) return Cmd.AlarmOff
        if (SNOOZE.matches(m)) return Cmd.Snooze
        if (ANSWER.matches(m)) return Cmd.Answer
        if (HANG_UP.matches(m)) return Cmd.HangUp
        YOUTUBE.matchEntire(m)?.let { r -> return Cmd.YouTube((r.groupValues[1].ifBlank { r.groupValues[2] }).trim()) }
        if (SKIP.matches(m)) return Cmd.SkipAd
        if (READ.matches(m)) return Cmd.Read
        if (BACK.matches(m)) return Cmd.Back
        SCROLL.matchEntire(m)?.let { r -> return Cmd.Scroll(down = !r.groupValues[1].equals("up", true) && !m.contains("upar", true)) }
        PLAY.matchEntire(m)?.let { r ->
            val which = r.groupValues[1].lowercase()
            if (which.isNotBlank()) {
                val n = ORDINALS[which] ?: Regex("""\d""").find(which)?.value?.toInt()
                return Cmd.Play(n, "")
            }
            val words = r.groupValues[2].trim()
            // "play music" / "open whatsapp" are other things: only titles that look like a choice from the list.
            return if (words.split(' ').size >= 2 && !words.startsWith("my ") && !words.contains(" on ")) Cmd.Play(null, words) else null
        }
        return null
    }

    // ---- YouTube's screen ----

    data class Video(val title: String, val detail: String, val x: Int, val y: Int)

    private val DURATION = re(""" - (?:\d+ (?:hours?|minutes?|seconds?)|\d+:\d+)""")
    private val META = re("""( - Go to channel| views| - play (?:video|Short)|, play Short)""")
    private val UI = re("""^(?:search|home|shorts|subscriptions|you|create|library|more options|action menu|notifications|cast|all|explore|account|voice search|filter|back|navigate up)\b""")

    /** The videos on a YouTube screen, top to bottom, from Hands' element list. */
    fun videos(elements: List<PhoneAgent.Element>, height: Int): List<Video> = elements.asSequence()
        .filter { it.tappable && it.label.length >= 20 && !UI.containsMatchIn(it.label) }
        .filter { height <= 0 || it.y in (height * 0.08).toInt()..(height * 0.97).toInt() }
        .filter { DURATION.containsMatchIn(it.label) || META.containsMatchIn(it.label) || it.label.length >= 78 }
        .map { e ->
            val cut = listOfNotNull(DURATION.find(e.label)?.range?.first, META.find(e.label)?.range?.first).minOrNull()
            val title = (if (cut != null) e.label.substring(0, cut) else e.label).trim().trimEnd(',', '-', ' ')
            val detail = e.label.substringAfter("Go to channel - ", "").substringBefore(" - ").trim()
            Video(title, detail, e.x, e.y)
        }
        .filter { it.title.length >= 4 }
        .distinctBy { it.title }
        .sortedBy { it.y }
        .toList()

    /** What XARVIS says about [videos] (≤ 5). */
    fun readOut(videos: List<Video>): String =
        if (videos.isEmpty()) "I don't see any videos on the screen yet, sir."
        else videos.take(5).mapIndexed { i, v -> "${i + 1}. ${v.title}" + if (v.detail.isNotBlank()) ", by ${v.detail}." else "." }
            .joinToString("\n") + "\nSay \"play the first\", \"play\" and a few title words, or \"scroll down\"."

    /** The video Rex chose. */
    fun pick(videos: List<Video>, index: Int?, words: String): Video? {
        if (videos.isEmpty()) return null
        if (index != null) return if (index == -1) videos.last() else videos.getOrNull(index - 1)
        val want = words.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        if (want.isEmpty()) return null
        return videos.maxByOrNull { v -> v.title.lowercase().split(Regex("\\W+")).count { it in want } }
            ?.takeIf { v -> v.title.lowercase().split(Regex("\\W+")).count { it in want } >= 1 }
    }

    /** YouTube's ad controls: a skip button, or signs an ad is showing. */
    private val SKIP_LABEL = re("""^(?:skip(?: ads?)?|skip ad in \d+|skip ads)$""")
    private val AD_SIGN = re("""^(?:ad\b|sponsored|visit advertiser|ad · |my ad center|why this ad)""")

    fun skipButton(elements: List<PhoneAgent.Element>): PhoneAgent.Element? =
        elements.firstOrNull { SKIP_LABEL.matches(it.label.trim()) && !it.label.contains(" in ", true) }

    fun adShowing(elements: List<PhoneAgent.Element>): Boolean =
        elements.any { AD_SIGN.containsMatchIn(it.label.trim()) || SKIP_LABEL.matches(it.label.trim()) }
}
