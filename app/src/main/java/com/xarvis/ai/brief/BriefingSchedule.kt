package com.xarvis.ai.brief

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * The daily-briefing alarm. XARVIS wakes at a set time each day, reads the briefing aloud and posts
 * it as a notification. Uses an exact alarm when the phone allows it (USE_EXACT_ALARM is auto-granted
 * on Android 13); otherwise it falls back to an inexact one that may drift a little. Each firing
 * re-arms the next day's alarm (a one-shot exact alarm is more reliable than a repeating one in doze).
 */
object BriefingSchedule {
    private const val PREFS = "settings"
    private const val ENABLED = "brief_auto"
    private const val HOUR = "brief_hour"
    private const val MIN = "brief_min"
    private const val REQ = 7733
    const val ACTION = "com.xarvis.ai.BRIEF_ALARM"

    // Default: on, 3:33 PM (Rex's choice). The switch and time picker are in the briefing screen.
    private const val DEFAULT_HOUR = 15
    private const val DEFAULT_MIN = 33

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun isEnabled(c: Context): Boolean = prefs(c).getBoolean(ENABLED, true)
    fun hour(c: Context): Int = prefs(c).getInt(HOUR, DEFAULT_HOUR)
    fun minute(c: Context): Int = prefs(c).getInt(MIN, DEFAULT_MIN)

    fun set(context: Context, enabled: Boolean, hour: Int, minute: Int) {
        prefs(context).edit().putBoolean(ENABLED, enabled).putInt(HOUR, hour).putInt(MIN, minute).apply()
        if (enabled) schedule(context) else cancel(context)
    }

    /** Arm the next alarm (no-op if the feature is off). Safe to call repeatedly — it replaces any pending one. */
    fun schedule(context: Context) {
        if (!isEnabled(context)) return
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val at = nextTime(hour(context), minute(context))
        val pi = pending(context)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            // Exact alarms not permitted: still fire, just not to the exact minute.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pending(context))
    }

    /** The next time today/tomorrow at [hour]:[minute]. */
    fun nextTime(hour: Int, minute: Int, from: Long = System.currentTimeMillis()): Long {
        val t = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (t.timeInMillis <= from) t.add(Calendar.DAY_OF_YEAR, 1)
        return t.timeInMillis
    }

    /** "3:33 PM" for the screen. */
    fun label(hour: Int, minute: Int): String {
        val ampm = if (hour < 12) "AM" else "PM"
        val h = ((hour + 11) % 12) + 1
        return "%d:%02d %s".format(h, minute, ampm)
    }

    private fun pending(context: Context): PendingIntent {
        val i = Intent(context, BriefingReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(
            context, REQ, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
