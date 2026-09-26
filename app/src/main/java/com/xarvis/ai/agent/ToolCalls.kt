package com.xarvis.ai.agent

import com.xarvis.ai.workflow.Step

/**
 * Reads the tool lines Gemma writes ("TOOL: contact Atiq") and turns them into steps.
 * Gemma decides which tool to use; this only has to read its choice.
 */
object ToolCalls {

    /** A tool line. Small models sometimes wrap it in markdown or write ACTION instead of TOOL. */
    private val LINE = Regex("""(?im)^[\s*`>-]*(?:TOOL|ACTION)\s*:\s*(.+?)[\s*`]*$""")

    /** The steps asked for in [reply], in order. Unknown tools are ignored. */
    fun parse(reply: String): List<Step> = LINE.findAll(reply).mapNotNull { parseOne(it.groupValues[1]) }.toList()

    fun parseOne(call: String): Step? {
        val t = call.replace("<", "").replace(">", "").trim().trim('"', '\'', '.').trim()
        arg(t, "time|date|day|clock")?.let { return Step.ReportTime }
        arg(t, "remember|save|note")?.takeIf { it.isNotBlank() }?.let { return Step.Remember(XarvisAgent.toSecondPerson(it)) }
        arg(t, "open|launch|open app")?.takeIf { it.isNotBlank() }?.let { return Step.LaunchApp(it.removeSuffix(" app").trim()) }
        arg(t, "contact|find contact|find_contact|number|phone")?.let { name ->
            val cleaned = name.replace(Regex("""(?i)'s\b|\b(?:number|phone|mobile|contact)\b"""), " ").trim()
            if (cleaned.isNotBlank()) return Step.FindContact(cleaned.replace(Regex("""\s+"""), " "))
        }
        return null
    }

    /** What Gemma wrote for the user: its reply minus tool lines, including one still being streamed. */
    fun visibleText(raw: String): String = raw.lines().filterNot { line ->
        val t = line.trimStart(' ', '*', '`', '>', '-').uppercase()
        t.startsWith("TOOL") || t.startsWith("ACTION") ||
            (t.isNotEmpty() && ("TOOL:".startsWith(t) || "ACTION:".startsWith(t)))
    }.joinToString("\n").trim()

    /** The argument after tool name [names] ("contact: Atiq" -> "Atiq"; "time" -> ""), or null. */
    private fun arg(text: String, names: String): String? =
        Regex("""^(?:$names)\b\s*[:=\-]?\s*(.*)$""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim()
}

/**
 * Linking phones stays exact on purpose: the 6-digit pairing code is a security check and
 * must be passed on exactly as typed, not interpreted.
 */
object LinkCommands {
    fun parse(message: String): Step? {
        val t = message.trim().trimEnd('.', '!', '?')
        if (t.lowercase() in setOf("devices", "linked devices", "my devices", "list devices")) return Step.ListDevices
        find(t, """^(?:pair|link)\s+with\s+(.+)$""")?.let { return Step.PairWith(it) }
        find(t, """^code\s+(\d{6})$""")?.let { return Step.PairCode(it) }
        find(t, """^(?:unlink|unpair)\s+(.+)$""")?.let { return Step.Unlink(it) }
        return null
    }

    private fun find(text: String, pattern: String): String? =
        Regex(pattern, RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim()
}
