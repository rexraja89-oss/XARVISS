package com.xarvis.ai.phone

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import java.util.Calendar

/**
 * Call forwarding on a schedule (Rex, 15 Nov 2026: "get my calls from the S22 to my other number on the benco …
 * I want to set a time frame"). Carriers only forward on/off, so XARVIS switches it at the times: at the start it
 * dials the carrier's forwarding code (**21*number# for every call, **004*number# for only unanswered/busy/
 * unreachable), at the end the cancel code. One exact alarm at the next start or end; re-armed after each, after
 * a restart and after an update — and a switch missed while the phone was off is made then.
 */
object CallForward {
    private const val PREFS = "call_forward"
    private const val REQ = 7741
    private const val CHANNEL = "call_forward"
    const val ACTION = "com.xarvis.ai.CALL_FORWARD"

    data class Plan(
        val enabled: Boolean,
        val number: String,
        val startHour: Int,
        val startMinute: Int,
        val endHour: Int,
        val endMinute: Int,
        /** true: only calls not answered, busy or unreachable. false: every call. */
        val missedOnly: Boolean,
    )

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun plan(c: Context): Plan = prefs(c).run {
        Plan(
            getBoolean("on", false), getString("number", "") ?: "",
            getInt("sh", 21), getInt("sm", 0), getInt("eh", 7), getInt("em", 0), getBoolean("missed", false),
        )
    }

    /** Whether XARVIS may switch forwarding on this phone (it needs to place calls: the S22, not the benco). */
    fun allowed(c: Context): Boolean = c.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    /** What XARVIS last switched it to (null: never). */
    fun forwarding(c: Context): Boolean? = prefs(c).takeIf { it.contains("state") }?.getBoolean("state", false)

    /** Saves [p], switches forwarding now if the plan says so, and arms the next switch. */
    fun save(c: Context, p: Plan) {
        val old = plan(c)
        prefs(c).edit().putBoolean("on", p.enabled).putString("number", p.number.trim())
            .putInt("sh", p.startHour).putInt("sm", p.startMinute).putInt("eh", p.endHour).putInt("em", p.endMinute)
            .putBoolean("missed", p.missedOnly).apply()
        if (!p.enabled) {
            cancelAlarm(c)
            if (forwarding(c) == true) switch(c, false, old)
            return
        }
        // A new number or kind: the old forwarding is replaced by the new one.
        apply(c, force = old.number != p.number.trim() || old.missedOnly != p.missedOnly)
    }

    /**
     * Brings forwarding to what the plan says for now (only dials when that differs from the last switch, unless
     * [force]) and arms the next start or end.
     */
    fun apply(c: Context, force: Boolean = false) {
        val p = plan(c)
        if (!p.enabled || p.number.isBlank() || !allowed(c)) return
        val want = inWindow(p, Calendar.getInstance())
        if (force || forwarding(c) != want) switch(c, want, p)
        arm(c, p)
    }

    /** Forwarding on or off now, by hand ("Forward now" / "Stop now"). */
    fun now(c: Context, on: Boolean): String {
        val p = plan(c)
        if (!allowed(c)) return "XARVIS can't place calls on this phone, so it can't switch forwarding."
        if (on && p.number.isBlank()) return "Type the number to forward to first."
        switch(c, on, p)
        return if (on) "Asked your carrier to forward calls to ${p.number}. A message from the carrier confirms it."
        else "Asked your carrier to stop forwarding."
    }

    private fun switch(c: Context, on: Boolean, p: Plan) {
        val code = when {
            on && p.missedOnly -> "**004*${p.number.filter { it.isDigit() || it == '+' }}#"
            on -> "**21*${p.number.filter { it.isDigit() || it == '+' }}#"
            p.missedOnly -> "##004#"
            else -> "##21#"
        }
        val placed = runCatching {
            // Telecom, not an Activity: works from an alarm with the screen off (Android blocks background activity starts).
            c.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", code, null), Bundle())
        }.isSuccess
        if (!placed) return
        prefs(c).edit().putBoolean("state", on).apply()
        notify(c, if (on) "📞 Calls now go to ${p.number}" else "📞 Calls ring on this phone again",
            if (on) "Until ${label(p.endHour, p.endMinute)} (XARVIS stops it then)." else "Forwarding stopped.")
    }

    /** Whether [now] is inside the time frame (it may run past midnight, e.g. 9 PM–7 AM). */
    fun inWindow(p: Plan, now: Calendar): Boolean {
        val m = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val s = p.startHour * 60 + p.startMinute
        val e = p.endHour * 60 + p.endMinute
        return when {
            s == e -> true
            s < e -> m in s until e
            else -> m >= s || m < e
        }
    }

    private fun arm(c: Context, p: Plan) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val at = minOf(next(p.startHour, p.startMinute), next(p.endHour, p.endMinute))
        val pi = pending(c)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun cancelAlarm(c: Context) {
        c.getSystemService(AlarmManager::class.java)?.cancel(pending(c))
    }

    private fun next(hour: Int, minute: Int): Long {
        val now = System.currentTimeMillis()
        val t = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (t.timeInMillis <= now) t.add(Calendar.DAY_OF_YEAR, 1)
        return t.timeInMillis
    }

    private fun pending(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, REQ, Intent(c, CallForwardReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun label(hour: Int, minute: Int): String = "%d:%02d %s".format(((hour + 11) % 12) + 1, minute, if (hour < 12) "AM" else "PM")

    private fun notify(c: Context, title: String, text: String) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Call forwarding", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(c, REQ, Intent(c, CallForwardActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            nm.notify(REQ, android.app.Notification.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.sym_call_outgoing)
                .setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true).build())
        }
    }
}

/** The start or end of the time frame. */
class CallForwardReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == CallForward.ACTION) CallForward.apply(context, force = true)
    }
}
