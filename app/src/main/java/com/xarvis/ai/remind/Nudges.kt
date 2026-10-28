package com.xarvis.ai.remind

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.xarvis.ai.brief.ICal
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The autonomous nudges XARVIS speaks without being asked: a heads-up before a calendar event, and
 * a low-battery warning. Checked on a background poll every [POLL_MINUTES]. Each nudge is said once
 * (de-duplicated in prefs) so it doesn't repeat on every poll.
 */
object Nudges {
    private const val PREFS = "nudges"
    private const val REQ = 7802
    private const val POLL_MINUTES = 15

    // Speak about an event starting within this many minutes.
    private const val LEAD_LOW = 1
    private const val LEAD_HIGH = 20
    private const val BATTERY_LOW = 15

    /** The lines to speak now (may be empty). Reads the calendar if one is configured. */
    suspend fun check(context: Context, icalUrl: String?): List<String> {
        val out = ArrayList<String>()
        batteryLine(context)?.let { out += it }
        if (!icalUrl.isNullOrBlank()) out += calendarLines(context, icalUrl)
        return out
    }

    private fun batteryLine(context: Context): String? {
        val bm = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = bm.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = bm.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else return null
        val status = bm.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val warned = prefs.getBoolean("batWarned", false)
        return when {
            pct in 1..BATTERY_LOW && !charging && !warned -> {
                prefs.edit().putBoolean("batWarned", true).apply()
                "Battery is at $pct percent, sir. Might be worth charging."
            }
            charging || pct > BATTERY_LOW + 5 -> { // recovered: allow warning again next time it drops
                if (warned) prefs.edit().putBoolean("batWarned", false).apply()
                null
            }
            else -> null
        }
    }

    // Cache today's events in memory so the 15-min poll doesn't fetch the calendar every time.
    private var cachedEvents: List<ICal.Event> = emptyList()
    private var cachedAt = 0L
    private const val CACHE_MS = 30 * 60_000L

    private suspend fun calendarLines(context: Context, icalUrl: String): List<String> {
        if (System.currentTimeMillis() - cachedAt > CACHE_MS) {
            cachedEvents = runCatching { ICal.today(icalUrl) }.getOrDefault(emptyList())
            cachedAt = System.currentTimeMillis()
        }
        val events = cachedEvents
        if (events.isEmpty()) return emptyList()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        // Reset the "already nudged" set at the start of each day.
        if (prefs.getString("day", "") != today) prefs.edit().putString("day", today).remove("done").apply()
        val done = prefs.getStringSet("done", emptySet())!!.toMutableSet()

        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val out = ArrayList<String>()
        for (e in events) {
            if (e.minutesOfDay < 0) continue // all-day: can't time a heads-up
            val delta = e.minutesOfDay - nowMin
            if (delta !in LEAD_LOW..LEAD_HIGH) continue
            val key = "$today:${e.minutesOfDay}:${e.title}"
            if (!done.add(key)) continue
            out += "You have ${e.title} in about $delta minute${if (delta == 1) "" else "s"}, sir."
        }
        if (out.isNotEmpty()) prefs.edit().putStringSet("done", done).apply()
        return out
    }

    /** Arm the next background poll (every 15 min); the receiver re-arms each time. */
    fun schedulePoll(context: Context) {
        val at = System.currentTimeMillis() + POLL_MINUTES * 60_000L
        ProactiveReceiver.armExact(context, at, pending(context))
    }

    private fun pending(context: Context): PendingIntent {
        val i = Intent(context, ProactiveReceiver::class.java).setAction(ProactiveReceiver.ACTION_NUDGE)
        return PendingIntent.getBroadcast(
            context, REQ, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
