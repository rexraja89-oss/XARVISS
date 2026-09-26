package com.xarvis.ai.agent

import com.xarvis.ai.workflow.Step
import java.time.LocalTime

/** Reads the times and durations in Gemma's alarm and timer tool lines. */
object TimeSpecs {

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
}
