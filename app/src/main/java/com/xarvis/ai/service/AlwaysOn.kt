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
        if (intent.action !in STARTS) return
        if (AlwaysOn.isEnabled(context)) XarvisService.start(context)
        // "Xarvis": the ears start again too. Android withholds the microphone from a service started here in the
        // background; XARVIS Hands (S22) or opening XARVIS once gives it back (see WakeWordService).
        // Android 15+ forbids starting a microphone service from here at all (it would crash): there XARVIS Hands
        // (S22) or opening XARVIS brings the ears back instead.
        if (android.os.Build.VERSION.SDK_INT < 35) {
            WakeWord.startIfOn(context)
            com.xarvis.ai.ears.EarsLink.kick(context)
        }
        // Alarms are cleared on reboot (and on app update), so re-arm everything scheduled.
        com.xarvis.ai.brief.BriefingSchedule.schedule(context)
        com.xarvis.ai.remind.Reminders.reschedule(context)
        com.xarvis.ai.remind.Nudges.schedulePoll(context)
        com.xarvis.ai.task.NightShiftRunner.schedule(context)
        // Call forwarding: re-arm, and make a switch missed while the phone was off.
        com.xarvis.ai.phone.CallForward.apply(context)
    }

    private companion object {
        val STARTS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
