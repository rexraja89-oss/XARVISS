package com.xarvis.ai.remind

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The spoken reminders XARVIS keeps for Rex ("remind me to call Atiq at 6"). Stored as JSON on the
 * phone, scheduled with one exact alarm set to the earliest one still ahead; when it fires, the
 * proactive receiver reads the due ones aloud and re-arms the next. Survives reboot and app update.
 */
object Reminders {
    private const val PREFS = "reminders"
    private const val KEY = "list"
    private const val REQ = 7801

    data class Reminder(val id: Long, val atMillis: Long, val text: String)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): List<Reminder> = load(context).sortedBy { it.atMillis }

    fun add(context: Context, atMillis: Long, text: String): Reminder {
        val r = Reminder(System.currentTimeMillis(), atMillis, text.trim())
        save(context, load(context) + r)
        reschedule(context)
        return r
    }

    /** Cancels reminders whose text contains [query] (blank cancels all). Returns how many went. */
    fun cancel(context: Context, query: String): Int {
        val list = load(context)
        val q = query.trim().lowercase()
        val keep = if (q.isBlank()) emptyList() else list.filterNot { it.text.lowercase().contains(q) }
        save(context, keep)
        reschedule(context)
        return list.size - keep.size
    }

    /** The reminders now due; also removes them from the store (the caller re-arms afterwards). */
    fun takeDue(context: Context, now: Long = System.currentTimeMillis()): List<Reminder> {
        val list = load(context)
        val due = list.filter { it.atMillis <= now }
        if (due.isNotEmpty()) save(context, list - due.toSet())
        return due.sortedBy { it.atMillis }
    }

    /** Arm the exact alarm for the earliest reminder still ahead (or cancel it if none). */
    fun reschedule(context: Context) {
        val next = load(context).filter { it.atMillis > System.currentTimeMillis() }.minByOrNull { it.atMillis }
        val pi = pending(context)
        if (next == null) {
            context.getSystemService(android.app.AlarmManager::class.java)?.cancel(pi)
        } else {
            ProactiveReceiver.armExact(context, next.atMillis, pi)
        }
    }

    fun describe(r: Reminder): String = "${r.text} — ${whenLabel(r.atMillis)}"

    /** "today at 6:00 PM", "tomorrow at 7:00 AM", "Wed 5 Nov at 9:00 AM". */
    private fun whenLabel(at: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = at }
        val today = Calendar.getInstance()
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        fun sameDay(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(at))
        return when {
            sameDay(cal, today) -> "today at $time"
            sameDay(cal, tomorrow) -> "tomorrow at $time"
            else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(at)) + " at $time"
        }
    }

    private fun pending(context: Context): PendingIntent {
        val i = Intent(context, ProactiveReceiver::class.java).setAction(ProactiveReceiver.ACTION_REMIND)
        return PendingIntent.getBroadcast(
            context, REQ, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun load(context: Context): List<Reminder> = runCatching {
        val arr = JSONArray(prefs(context).getString(KEY, "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { Reminder(it.getLong("id"), it.getLong("at"), it.getString("text")) }
    }.getOrDefault(emptyList())

    private fun save(context: Context, list: List<Reminder>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("at", it.atMillis).put("text", it.text)) }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }
}
