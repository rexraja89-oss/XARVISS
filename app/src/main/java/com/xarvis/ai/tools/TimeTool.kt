package com.xarvis.ai.tools

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "What day is it?": the phone's current date, time and time zone. Gemma has no clock of its own. */
class TimeTool : DeviceTool {

    override val label = "date and time"

    override fun matches(message: String) = TOPIC.containsMatchIn(message.lowercase())

    override suspend fun read(message: String): String {
        val now = ZonedDateTime.now()
        val date = now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH))
        val time = now.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
        val offset = now.offset.id.let { if (it == "Z") "+00:00" else it }
        return "Current date and time: $date, $time (time zone ${now.zone.id}, UTC$offset)"
    }

    private companion object {
        /** English, plus Hindi/Urdu "kitne baje" (what time), "aaj" (today), "kal" (tomorrow/yesterday), "tarikh" (date). */
        val TOPIC = Regex(
            """\b(time|date|day|days|today|tonight|tomorrow|yesterday|week|weekend|month|year|clock|""" +
                """monday|tuesday|wednesday|thursday|friday|saturday|sunday|""" +
                """baje|bje|aaj|kal|tarikh|tareekh|waqt|samay)\b"""
        )
    }
}
