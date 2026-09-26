package com.xarvis.ai.agent

import com.xarvis.ai.workflow.Step
import java.time.LocalTime

/**
 * Recognises phone actions: "call Ali", "whatsapp Ali: running late", "text mom: hi",
 * "flashlight on", "alarm 7:30 am for gym", "timer 10 minutes", "bluetooth on", "wifi off",
 * plus a few Hindi/Urdu forms ("Ali ko call karo", "torch jalao"). The same forms are the
 * ACTION lines the LLM uses.
 */
object PhoneCommands {

    /** [fromUser] is false for the LLM's ACTION lines, which never place a call without the user tapping. */
    fun parse(text: String, fromUser: Boolean, now: LocalTime = LocalTime.now()): Step? {
        val t = text.trim()

        // Calls. "call me Rex" is about names, not phones.
        if (!Regex("""^call\s+me\b""", RegexOption.IGNORE_CASE).containsMatchIn(t)) {
            find(t, """^(?:please\s+)?(call|phone|ring|dial)\s+(.+)$""")?.let {
                return Step.Call(it[2], direct = fromUser && !it[1].equals("dial", ignoreCase = true))
            }
        }
        find(t, """^(.+?)\s+ko\s+(?:call|phone|fone)(?:\s+(?:karo|kro|kar do|lagao|milao))?$""")
            ?.let { return Step.Call(it[1], direct = fromUser) }

        // Messages: opened ready to send, never sent automatically.
        find(t, """^(?:send\s+(?:an?\s+)?)?whats\s?app(?:\s+message)?\s+(?:to\s+)?(.+?)(?:\s*:\s*|\s+saying\s+|\s+that\s+)(.+)$""")
            ?.let { return Step.WhatsApp(it[1], it[2]) }
        find(t, """^(?:send\s+(?:an?\s+)?)?whats\s?app\s+(?:to\s+)?(.+)$""")
            ?.let { return Step.WhatsApp(it[1], null) }
        find(t, """^(?:send\s+(?:an?\s+)?)?(?:sms|text)(?:\s+message)?\s+(?:to\s+)?(.+?)(?:\s*:\s*|\s+saying\s+)(.+)$""")
            ?.let { return Step.Sms(it[1], it[2]) }

        toggle(t, FLASH)?.let { return Step.Flashlight(it) }
        if (Regex("""^(?:$FLASH)$""", RegexOption.IGNORE_CASE).matches(t)) return Step.Flashlight(true)
        find(t, """^(?:$FLASH)\s+(jalao|chalao|on karo|kholo|bujhao|band karo|off karo|band)$""")
            ?.let { return Step.Flashlight(it[1] in setOf("jalao", "chalao", "on karo", "kholo")) }
        toggle(t, BLUETOOTH)?.let { return Step.Bluetooth(it) }
        toggle(t, WIFI)?.let { return Step.Wifi(it) }

        find(t, """^(?:set\s+(?:an?\s+|the\s+)?)?alarm\s+(?:for\s+|at\s+)?(.+)$""")?.let { return alarm(it[1], now) }
        find(t, """^wake\s+me(?:\s+up)?\s+(?:at\s+)?(.+)$""")?.let { return alarm(it[1], now) }

        find(t, """^(?:set\s+(?:an?\s+|the\s+)?)?timer\s+(?:for\s+)?(.+)$""")
            ?.let { m -> seconds(m[1])?.let { return Step.Timer(it) } }

        return null
    }

    /** "7", "7:30", "7.30 pm", "19:00", "6 am tomorrow for gym", "8 tonight". Null if there's no clear time. */
    fun alarm(spec: String, now: LocalTime): Step.Alarm? {
        val m = Regex("""^(\d{1,2})(?:[:.](\d{2}))?\s*(a\.?\s?m\.?|p\.?\s?m\.?)?(?:\s+(.*))?$""", RegexOption.IGNORE_CASE)
            .find(spec.trim()) ?: return null
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
        val tail = m.groupValues[4].lowercase()
        val meridiem = m.groupValues[3].lowercase().firstOrNull()
            ?: when {
                Regex("""\b(morning|subah)\b""").containsMatchIn(tail) -> 'a'
                Regex("""\b(tonight|evening|night|afternoon|shaam|raat)\b""").containsMatchIn(tail) -> 'p'
                else -> null
            }
        if (minute > 59) return null
        when {
            meridiem != null -> {
                if (hour !in 1..12) return null
                hour = hour % 12 + if (meridiem == 'p') 12 else 0
            }
            hour > 23 -> return null
            hour in 1..12 -> {
                // "alarm 7" means the next 7 o'clock, morning or evening.
                val nowMin = now.hour * 60 + now.minute
                fun wait(h: Int) = ((h * 60 + minute) - nowMin + 24 * 60) % (24 * 60)
                val morning = hour % 12
                hour = if (wait(morning) <= wait(morning + 12)) morning else morning + 12
            }
        }
        val label = Regex("""\b(?:for|to|called|named|label(?:led)?)\s+(.+)$""").find(tail)?.groupValues?.get(1)?.trim()
        return Step.Alarm(hour, minute, label?.ifBlank { null })
    }

    /** "10 minutes", "1 hour 30 min", "90 sec", "half an hour", "5" (minutes). */
    fun seconds(spec: String): Int? {
        val s = spec.lowercase().trim()
        if (Regex("""^(?:half an? hour|30 mins?)$""").matches(s)) return 30 * 60
        s.toIntOrNull()?.let { return it * 60 }
        var total = 0.0
        var any = false
        Regex("""(\d+(?:\.\d+)?)\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\b""").findAll(s).forEach {
            any = true
            val n = it.groupValues[1].toDouble()
            total += n * when (it.groupValues[2].first()) {
                'h' -> 3600
                'm' -> 60
                else -> 1
            }
        }
        return total.toInt().takeIf { any && it > 0 }
    }

    /** "turn on the X", "switch X off", "X on", "X band karo". */
    private fun toggle(t: String, subject: String): Boolean? {
        val m = find(t, """^(?:turn|switch|put)\s+(on|off)\s+(?:the\s+|my\s+)?(?:$subject)$""")
            ?: find(t, """^(?:turn|switch|put)\s+(?:the\s+|my\s+)?(?:$subject)\s+(on|off)$""")
            ?: find(t, """^(?:$subject)\s+(on|off|on karo|off karo|band karo|chalao)$""")
            ?: return null
        return m[1].lowercase() in setOf("on", "on karo", "chalao")
    }

    private fun find(text: String, pattern: String): List<String>? =
        Regex(pattern, RegexOption.IGNORE_CASE).find(text)?.groupValues?.map { it.trim() }

    private const val FLASH = """flash\s?light|torch|flash"""
    private const val BLUETOOTH = """blue\s?tooth"""
    private const val WIFI = """wi-?fi|wifi"""
}
