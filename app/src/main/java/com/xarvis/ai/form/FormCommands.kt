package com.xarvis.ai.form

/** "fill this form", "fill in the form with my CV", "autofill this form", "fill the form on my S22". */
object FormCommands {
    data class Cmd(val remote: Boolean)

    private const val LEAD = """^(?:(?:hey |ok |okay )?(?:xarvis|jarvis|zarvis)[,.!]?\s+)?(?:please\s+|can you\s+|could you\s+)?"""
    private const val PHONE = """(?:\s+(?:on|in)\s+(?:my\s+|the\s+)?(s\s?22(?:\s+ultra)?|galaxy|samsung|other\s+phone|main\s+phone))?"""
    private const val TAIL = """(?:\s+(?:please|now|sir|for me))*[.!?]*$"""
    private val FILL = Regex(
        LEAD + """(?:auto ?fill|fill(?:\s+(?:in|out|up))?|complete)\s+(?:this|the|my|that)\s+(?:form|application(?:\s+form)?|registration(?:\s+form)?|cv form)""" +
            PHONE + """(?:\s+(?:for me|from|with|using)\s+(?:my\s+)?(?:cv|resume|details|info(?:rmation)?|profile))?""" + PHONE + TAIL,
        RegexOption.IGNORE_CASE,
    )

    fun parse(message: String): Cmd? {
        val m = message.trim()
        if (m.length > 90) return null
        val r = FILL.matchEntire(m) ?: return null
        return Cmd(remote = r.groupValues[1].isNotBlank() || r.groupValues[2].isNotBlank())
    }
}
