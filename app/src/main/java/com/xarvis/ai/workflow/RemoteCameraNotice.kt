package com.xarvis.ai.workflow

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.xarvis.ai.R

/**
 * The linked phone (the benco) asked for a photo while this phone's screen is off or locked. Android
 * won't let XARVIS open its camera from the background, but a full-screen notification — the way an
 * incoming call wakes the phone — may open XARVIS Eyes over the lock screen. Eyes then turns the screen
 * on, says "Smile, sir", shoots, and closes; it never happens with the screen dark or silently.
 * (Needs USE_FULL_SCREEN_INTENT, S22 build; granted at install on Android 13.)
 */
object RemoteCameraNotice {
    private const val CHANNEL = "xarvis_remote_camera"
    private const val ID = 4711
    private const val PREFS = "eyes"
    private const val ALLOW_LOCKED = "remoteWhenLocked"

    /** Rex's switch in XARVIS Eyes: may the linked phone use this camera while the screen is off or locked? */
    fun allowedWhenLocked(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ALLOW_LOCKED, true)

    fun setAllowedWhenLocked(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ALLOW_LOCKED, on).apply()
    }

    /** Screen off, or the lock screen showing. */
    fun asleep(context: Context): Boolean {
        val screenOn = context.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: false
        return !screenOn || locked
    }

    fun show(context: Context, from: String, eyes: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Linked phone camera", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Wakes the screen when your linked phone asks XARVIS Eyes for a photo"
            },
        )
        val open = PendingIntent.getActivity(
            context, ID, Intent(eyes).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_xarvis)
            .setContentTitle("📸 $from asked for a photo")
            .setContentText("XARVIS Eyes is taking it. Tap if it didn't open.")
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(90_000L)
            .build()
        manager.notify(ID, n)
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }
}
