package com.xarvis.ai.remind

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.xarvis.ai.XarvisApp
import com.xarvis.ai.service.XarvisService

/**
 * Fires for the proactive alarms (a due reminder, or the periodic nudge poll) and for the
 * notification's Stop button. The real work — reading email/calendar and speaking — is handed to
 * the foreground service so it isn't cut short by the ~10s a broadcast receiver is given.
 */
class ProactiveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_NUDGE -> {
                Nudges.schedulePoll(context) // re-arm the next poll first
                XarvisService.proactive(context)
            }
            ACTION_REMIND -> XarvisService.proactive(context)
            ACTION_STOP -> {
                (context.applicationContext as XarvisApp).core.stopSpeaking()
                val id = intent.getIntExtra(EXTRA_NOTIF, -1)
                if (id >= 0) context.getSystemService(NotificationManager::class.java).cancel(id)
            }
        }
    }

    companion object {
        const val ACTION_REMIND = "com.xarvis.ai.PROACTIVE_REMIND"
        const val ACTION_NUDGE = "com.xarvis.ai.PROACTIVE_NUDGE"
        const val ACTION_STOP = "com.xarvis.ai.PROACTIVE_STOP"
        const val EXTRA_NOTIF = "notif_id"

        /** Set an exact alarm when the phone allows it, else an inexact one (never worse than drift). */
        fun armExact(context: Context, atMillis: Long, pi: PendingIntent) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            try {
                if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
                } else {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
                }
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            }
        }
    }
}
