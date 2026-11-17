package com.xarvis.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After a restart (from before the first unlock: Direct Boot) or an update: reachable again, with nobody at the phone. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // Starting the app (RemoteApp.onCreate) brings the link up; the service keeps it up.
        if (!RemotePrefs.hosting(context)) return
        RemoteService.start(context)
        // Before the first unlock after a restart: tell the controlling phones, so Rex can draw the pattern from afar.
        if (intent?.action == Intent.ACTION_LOCKED_BOOT_COMPLETED || !RestartNews.unlocked(context)) RestartNews.announce(context)
    }
}
