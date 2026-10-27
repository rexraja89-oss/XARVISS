package com.xarvis.ai.brief

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xarvis.ai.XarvisApp
import com.xarvis.ai.service.XarvisService

/**
 * Handles the daily-briefing alarm and the notification's **Stop** button.
 * - On the alarm: re-arm tomorrow, then hand the real work (reading email over IMAP etc., which can
 *   take a few seconds) to the foreground service so it isn't cut short by the ~10s a receiver gets.
 * - On Stop: silence the briefing immediately and clear the notification.
 */
class BriefingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STOP -> {
                (context.applicationContext as XarvisApp).core.stopSpeaking()
                BriefingNotice.cancel(context)
            }
            else -> {
                // Arm tomorrow first, so a failure today never breaks the daily cycle.
                BriefingSchedule.schedule(context)
                if (!BriefingSchedule.isEnabled(context)) return
                XarvisService.briefNow(context)
            }
        }
    }

    companion object {
        const val ACTION_STOP = "com.xarvis.ai.BRIEF_STOP"
    }
}
