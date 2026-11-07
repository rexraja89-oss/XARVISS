package com.xarvis.ai.workflow

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.xarvis.ai.R

/**
 * Android silently blocks an app in the background from opening another app (no error, nothing
 * happens). That bit Rex when a slow reply arrived after he'd switched away: XARVIS said "Opening
 * LinkedIn" and nothing opened. So when no XARVIS screen is in front, the launch also goes into a
 * heads-up "Tap to open" notification — opening an app from a notification tap is always allowed.
 */
object LaunchNotice {
    private const val CHANNEL = "xarvis_open_app"

    /** Whether one of XARVIS's screens is in front right now (so Android lets it open apps). */
    fun inForeground(): Boolean = runCatching {
        ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance ==
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }.getOrDefault(true)

    fun show(context: Context, label: String, intent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Open app", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "\"Tap to open\" when XARVIS can't open an app from the background"
            },
        )
        val id = (label.hashCode() and 0xFFFF) + 3000
        val open = PendingIntent.getActivity(
            context, id, Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_xarvis)
            .setContentTitle("Tap to open $label")
            .setContentText("XARVIS was in the background, so Android needs your tap.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(5 * 60_000L)
            .build()
        manager.notify(id, n)
    }
}
