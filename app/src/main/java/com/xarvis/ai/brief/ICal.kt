package com.xarvis.ai.brief

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

/** Reads today's events from a Google Calendar "Secret iCal address" (.ics). Dependency-free parse. */
object ICal {

    data class Event(val minutesOfDay: Int, val title: String)

    /** Today's events (soonest first), or empty on any problem. */
    suspend fun today(icalUrl: String): List<Event> = withContext(Dispatchers.IO) {
        if (icalUrl.isBlank()) return@withContext emptyList()
        runCatching { parse(fetch(icalUrl)) }.getOrDefault(emptyList())
    }

    private fun fetch(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "XARVIS")
        }
        return try {
            (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        } finally { c.disconnect() }
    }

    /** Pulls VEVENTs, keeps those that start today, returns them sorted. Pure, so it's unit-tested. */
    fun parse(ics: String): List<Event> {
        // Unfold folded lines (iCal continues long lines with a leading space/tab).
        val unfolded = ics.replace("\r\n", "\n").replace("\n ", "").replace("\n\t", "")
        val now = Calendar.getInstance()
        val y = now.get(Calendar.YEAR); val mo = now.get(Calendar.MONTH) + 1; val d = now.get(Calendar.DAY_OF_MONTH)
        val todayStamp = "%04d%02d%02d".format(y, mo, d)
        val out = ArrayList<Event>()
        for (block in unfolded.split("BEGIN:VEVENT").drop(1)) {
            val body = block.substringBefore("END:VEVENT")
            val summary = line(body, "SUMMARY").ifBlank { "(no title)" }
            val dt = line(body, "DTSTART") // may be "20261027T090000Z", "...T090000", or VALUE=DATE "20261027"
            val datePart = dt.take(8)
            if (datePart != todayStamp) continue
            val mins = if (dt.length >= 13 && dt[8] == 'T') {
                (dt.substring(9, 11).toIntOrNull() ?: 0) * 60 + (dt.substring(11, 13).toIntOrNull() ?: 0)
            } else -1 // all-day
            out.add(Event(mins, summary.trim()))
        }
        return out.sortedBy { if (it.minutesOfDay < 0) Int.MAX_VALUE else it.minutesOfDay }
    }

    /** The value of a property line (handles "DTSTART;TZID=...:value" too). */
    private fun line(body: String, prop: String): String {
        val re = Regex("(?m)^" + prop + "(?:;[^:\\n]*)?:(.*)$")
        return re.find(body)?.groupValues?.get(1)?.trim().orEmpty()
    }

    fun format(e: Event): String {
        if (e.minutesOfDay < 0) return "all day — ${e.title}"
        val h = e.minutesOfDay / 60; val m = e.minutesOfDay % 60
        val ampm = if (h < 12) "am" else "pm"; val h12 = ((h + 11) % 12) + 1
        return "%d:%02d %s — %s".format(h12, m, ampm, e.title)
    }
}
