package com.xarvis.ai.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Whether XARVIS keeps running in the background ("always on" / "always off"). On by default. */
object AlwaysOn {
    private const val PREFS = "service"
    private const val KEY = "alwaysOn"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()
        if (enabled) XarvisService.start(context) else XarvisService.stop(context)
    }
}

/** Restarts the background service after the phone reboots or XARVIS is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (AlwaysOn.isEnabled(context)) XarvisService.start(context)
        }
    }
}
