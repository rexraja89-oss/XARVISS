package com.xarvis.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After a restart or an update of XARVIS Remote: reachable again, with nobody at the phone. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // Starting the app (RemoteApp.onCreate) brings the link up; the service keeps it up.
        if (RemotePrefs.hosting(context)) RemoteService.start(context)
    }
}
