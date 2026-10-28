package com.xarvis.ai.agent

import com.xarvis.ai.brief.BriefingSchedule
import com.xarvis.ai.workflow.Step
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar

/**
 * Reads Rex's reminder requests deterministically (not left to the brain) so "remind me to call
 * Atiq at 6" always lands with the right time and words. Handles creating, listing and cancelling.
 * Returns null if the message isn't about reminders.
 */
object RemindCommands {

    private val STARTS = Regex(
        """(?i)^\s*(?:(?:please|pls|hey|ok|okay|can you|could you|would you|will you|xarvis)\s+)*""" +
            """(?:set (?:a |an )?reminder(?:\s+for me)?|remind me|remind)\b\s*""",
    )
    private val LIST = Regex("""(?i)^(?:what(?:'s| is| are)\s+)?(?:please\s+)?(?:do i have any\s+|show\s+|list\s+|see\s+|my\s+)*reminders?\??$""")
    private val CANCEL = Regex("""(?i)^(?:cancel|clear|delete|remove|stop|forget)\s+(?:all\s+)?(?:my\s+|the\s+)?reminders?\b\s*(.*)$""")

    private val LEAD = Regex("""(?i)^(?:for me\s+)?(?:to|that|about)\s+""")
    private val START_AT = Regex("""(?i)^at\s+(\d[^\s]*(?:\s*[ap]\.?m\.?)?)\s+(?:to\s+|about\s+|that\s+)?(.+)$""")
    private val START_IN = Regex("""(?i)^in\s+(.+?)\s+(?:to|about|that)\s+(.+)$""")
    private val END_IN = Regex("""(?i)\bin\s+(.+)$""")
    private val END_AT = Regex("""(?i)\b(tomorrow\s+)?at\s+(\d[^\s]*(?:\s*[ap]\.?m\.?)?)\s*$""")

    fun parse(message: String, now: Long = System.currentTimeMillis()): Step? {
        val raw = message.trim().trimEnd('.', '!')
        val lower = raw.lowercase()

        if (LIST.matches(lower)) return Step.ListReminders
        CANCEL.find(lower)?.let { return Step.CancelReminders(it.groupValues[1].trim()) }
        if (!STARTS.containsMatchIn(raw)) return null

        var body = raw.replaceFirst(STARTS, "").trim()
        body = body.replaceFirst(LEAD, "").trim()
        if (body.isBlank()) return askWhen()

        // Time/duration at the start: "at 6 to call Atiq", "in 20 minutes to drink water".
        START_AT.find(body)?.let { m ->
            val task = m.groupValues[2].trim()
            clock(m.groupValues[1], now)?.let { (ms, label) ->
                if (task.isNotBlank()) return Step.Remind(ms, task(task), label)
            }
        }
        START_IN.find(body)?.let { m ->
            val secs = TimeSpecs.seconds(m.groupValues[1].trim())
            val task = m.groupValues[2].trim()
            if (secs != null && task.isNotBlank()) return Step.Remind(now + secs * 1000L, task(task), durLabel(secs))
        }

        // Time/duration at the end: "take medicine in 30 minutes", "call Atiq at 6", "call mom tomorrow at 9am".
        END_IN.find(body)?.let { m ->
            val secs = TimeSpecs.seconds(m.groupValues[1].trim())
            if (secs != null) {
                val task = body.substring(0, m.range.first).trim()
                if (task.isNotBlank()) return Step.Remind(now + secs * 1000L, task(task), durLabel(secs))
            }
        }
        END_AT.find(body)?.let { m ->
            val tomorrow = m.groupValues[1].isNotBlank()
            clock(m.groupValues[2], now, tomorrow)?.let { (ms, label) ->
                val task = body.substring(0, m.range.first).trim()
                if (task.isNotBlank()) return Step.Remind(ms, task(task), label)
            }
        }
        return askWhen()
    }

    private fun askWhen() = Step.Respond(
        "When would you like the reminder, sir? For example: \"remind me to call Atiq at 6 pm\", or \"remind me in 20 minutes\".",
    )

    /** Clean the reminder's words and say them back about Rex ("my pills" → "your pills"). */
    private fun task(t: String): String =
        XarvisAgent.toSecondPerson(t.trim().trim('"', '\'').removeSuffix(" please").trim())

    /** A clock spec ("6", "6:30", "7 pm") → the next time it happens (or tomorrow), with a label. */
    private fun clock(spec: String, now: Long, forceTomorrow: Boolean = false): Pair<Long, String>? {
        val lt = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalTime()
        val a = TimeSpecs.alarm(spec.trim(), lt) ?: return null
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, a.hour); set(Calendar.MINUTE, a.minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        var tomorrow = forceTomorrow
        if (forceTomorrow) cal.add(Calendar.DAY_OF_YEAR, 1)
        else if (cal.timeInMillis <= now) { cal.add(Calendar.DAY_OF_YEAR, 1); tomorrow = true }
        val label = (if (tomorrow) "tomorrow at " else "at ") + BriefingSchedule.label(a.hour, a.minute)
        return cal.timeInMillis to label
    }

    private fun durLabel(secs: Int): String = when {
        secs < 60 -> "in $secs second${if (secs == 1) "" else "s"}"
        secs < 3600 -> "in ${secs / 60} minute${if (secs / 60 == 1) "" else "s"}"
        secs % 3600 == 0 -> "in ${secs / 3600} hour${if (secs / 3600 == 1) "" else "s"}"
        else -> "in ${secs / 3600}h ${(secs % 3600) / 60}m"
    }
}
