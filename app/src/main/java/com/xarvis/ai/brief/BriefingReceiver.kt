package com.xarvis.ai.brief

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xarvis.ai.service.XarvisService

/**
 * Fires on the daily alarm: re-arms tomorrow's alarm, then hands the real work (reading email over
 * IMAP etc., which can take a few seconds) to the foreground service so it isn't cut short by the
 * ~10s a broadcast receiver is given.
 */
class BriefingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Arm tomorrow first, so a failure today never breaks the daily cycle.
        BriefingSchedule.schedule(context)
        if (!BriefingSchedule.isEnabled(context)) return
        XarvisService.briefNow(context)
    }
}
