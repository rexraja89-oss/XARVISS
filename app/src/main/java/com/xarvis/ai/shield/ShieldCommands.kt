package com.xarvis.ai.shield

/** "security check", "is this link safe: …", "scam check: …" — answered by XARVIS Shield, not the brain. */
object ShieldCommands {
    sealed class Cmd {
        object Checkup : Cmd()
        data class Check(val text: String) : Cmd()
    }

    private val CHECKUP = Regex(
        """^(?:(?:hey |ok )?xarvis,? )?(?:run (?:a |the )?|do (?:a |the )?)?(?:security (?:check|checkup|check-up|scan)|scan my (?:phone|device)|check my (?:phone|device) for (?:viruses?|malware|hackers?|spyware)|is my (?:phone|device) (?:safe|secure|hacked)|am i (?:safe|hacked)|shield (?:status|check|report))[.!?]*$""",
        RegexOption.IGNORE_CASE,
    )
    private val ASK = Regex(
        """\b(?:is (?:this|it|that)(?: (?:link|message|sms|text|email|mail|website|site))? (?:safe|a scam|real|fake|legit|genuine|spam)|check (?:this|the) (?:link|message|sms|text|email|website)|scam check|safe to (?:open|click|tap)|kya ye (?:link|message) (?:safe|sahi)|ye scam hai)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(message: String): Cmd? {
        val m = message.trim()
        if (CHECKUP.matches(m)) return Cmd.Checkup
        if (!ASK.containsMatchIn(m)) return null
        val rest = ASK.replace(m, " ").trim(' ', ':', '-', '?', ',', '.', '"', '\n')
        // Nothing to judge ("is this safe?" alone) → let the chat answer.
        return if (rest.length >= 6 || Scam.links(m).isNotEmpty()) Cmd.Check(rest.ifBlank { m }) else null
    }

    /** XARVIS's answer about [text] (a link or a whole message). */
    fun judge(text: String): String {
        val links = Scam.links(text)
        val v = Scam.message(text)
        if (v.level == Scam.Level.SAFE && links.size == 1) {
            val l = Scam.link(links[0])
            return "🛡 " + l.text() + "\nStill: never type a password or code into a page you reached from a message."
        }
        return "🛡 " + v.text() + if (v.level == Scam.Level.SAFE) "\nI looked for the usual tricks (fake bank/parcel/prize/job messages, code requests, lookalike links)." else ""
    }
}
