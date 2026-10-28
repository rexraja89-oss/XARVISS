package com.xarvis.ai.remind

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.xarvis.ai.MainActivity
import com.xarvis.ai.R

/**
 * Shows a reminder / nudge as a notification with a **Stop** button (like an alarm), so the spoken
 * message can be silenced. Pops as a heads-up but makes no sound of its own — the voice is the audio.
 */
object ProactiveNotice {
    private const val CHANNEL = "xarvis_proactive"

    fun show(context: Context, title: String, text: String) {
        createChannel(context)
        val id = (System.currentTimeMillis() % 100000).toInt() + 1000
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getBroadcast(
            context, id,
            Intent(context, ProactiveReceiver::class.java)
                .setAction(ProactiveReceiver.ACTION_STOP)
                .putExtra(ProactiveReceiver.EXTRA_NOTIF, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_xarvis)
            .setContentTitle(title)
            .setContentText(text.take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "Reminders & nudges", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Spoken reminders and heads-up nudges"
            setSound(null, null)
            enableVibration(true)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
