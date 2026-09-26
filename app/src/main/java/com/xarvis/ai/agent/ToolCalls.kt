package com.xarvis.ai.agent

import com.xarvis.ai.workflow.Step
import java.time.LocalTime

/**
 * Reads the tool lines Gemma writes ("TOOL: contact Atiq") and turns them into steps.
 * Gemma decides which tool to use; this only has to read its choice.
 */
object ToolCalls {

    /** A tool line. Small models sometimes wrap it in markdown or write ACTION instead of TOOL. */
    private val LINE = Regex("""(?im)^[\s*`>-]*(?:TOOL|ACTION)\s*:\s*(.+?)[\s*`]*$""")

    /** The steps asked for in [reply], in order. Unknown tools are ignored. */
    fun parse(reply: String, now: LocalTime = LocalTime.now()): List<Step> =
        LINE.findAll(reply).mapNotNull { parseOne(it.groupValues[1], now) }.toList()

    fun parseOne(call: String, now: LocalTime = LocalTime.now()): Step? {
        val t = call.replace("<", "").replace(">", "").trim().trim('"', '\'', '.').trim()
        arg(t, "time|date|day|clock")?.let { return Step.ReportTime }
        arg(t, "location|where am i|where")?.let { return Step.Location }
        arg(t, "battery|charge")?.let { return Step.Battery }
        arg(t, "bluetooth")?.let { return onOff(it)?.let(Step::Bluetooth) ?: Step.BluetoothStatus }
        arg(t, "wifi|wi-fi")?.let { a -> onOff(a)?.let { return Step.Wifi(it) } }
        arg(t, "flashlight|flash light|torch|flash")?.let { return Step.Flashlight(onOff(it) ?: true) }
        arg(t, "alarm|set alarm")?.let { return TimeSpecs.alarm(it, now) }
        arg(t, "timer|set timer")?.let { a -> return TimeSpecs.seconds(a)?.let(Step::Timer) }
        arg(t, "call|dial")?.takeIf { it.isNotBlank() }?.let { return Step.Call(it) }
        arg(t, "whatsapp|whats app")?.takeIf { it.isNotBlank() }?.let { a ->
            val (who, text) = splitMessage(a)
            return Step.WhatsApp(who, text)
        }
        arg(t, "sms|text|message")?.let { a ->
            val (who, text) = splitMessage(a)
            return if (who.isNotBlank() && text != null) Step.Sms(who, text) else null
        }
        arg(t, "map|maps|navigate|navigate to|directions|directions to")?.let { return Step.ShowMap(it.ifBlank { null }) }
        arg(t, "ask|send to|share to|type in")?.let { a ->
            val (app, text) = splitMessage(a)
            if (app.isNotBlank() && text != null) return Step.AskApp(app, text.trim('"', '\'').trim())
        }
        arg(t, "find in|find|search in")?.let { a ->
            val (app, query) = splitMessage(a)
            if (app.isNotBlank() && query != null) return Step.FindInApp(app, query)
        }
        arg(t, "search|google|web search")?.takeIf { it.isNotBlank() }?.let { return Step.Search(it) }
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

    /** "Ali: running late" -> ("Ali", "running late"); "Ali" -> ("Ali", null). */
    private fun splitMessage(arg: String): Pair<String, String?> {
        val i = arg.indexOf(':')
        return if (i < 0) arg.trim() to null
        else arg.substring(0, i).trim() to arg.substring(i + 1).trim().ifBlank { null }
    }

    /** "on" / "off" at the start of a tool argument; null if neither. */
    private fun onOff(arg: String): Boolean? = when {
        Regex("""^(?:on|enable|start)\b""", RegexOption.IGNORE_CASE).containsMatchIn(arg) -> true
        Regex("""^(?:off|disable|stop)\b""", RegexOption.IGNORE_CASE).containsMatchIn(arg) -> false
        else -> null
    }

    /** The argument after tool name [names] ("contact: Atiq" -> "Atiq"; "time" -> ""), or null. */
    private fun arg(text: String, names: String): String? =
        Regex("""^(?:$names)\b\s*[:=\-]?\s*(.*)$""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim()
}

/**
 * Linking phones stays exact on purpose: the 6-digit pairing code is a security check and
 * must be passed on exactly as typed, not interpreted.
 */
object LinkCommands {
    /** [pairing]: a pairing code is awaited, so a bare 6-digit number is that code. */
    fun parse(message: String, pairing: Boolean = false): Step? {
        val t = message.trim().trimEnd('.', '!', '?')
        if (pairing && Regex("""^\d{6}$""").matches(t)) return Step.PairCode(t)
        if (t.lowercase() in setOf("devices", "linked devices", "my devices", "list devices")) return Step.ListDevices
        find(t, """^(?:pair|link)\s+with\s+(.+)$""")?.let { return Step.PairWith(it) }
        find(t, """^code\s+(\d{6})$""")?.let { return Step.PairCode(it) }
        find(t, """^(?:unlink|unpair)\s+(.+)$""")?.let { return Step.Unlink(it) }
        return null
    }

    private fun find(text: String, pattern: String): String? =
        Regex(pattern, RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim()
}
