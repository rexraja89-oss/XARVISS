package com.xarvis.ai.brief

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.xarvis.ai.BriefingActivity
import com.xarvis.ai.R

/**
 * Shows the morning briefing as a notification with a **Stop** button (like an alarm) so the spoken
 * briefing can be silenced mid-sentence. Pops as a heads-up but makes no sound of its own — the
 * spoken voice is the only audio.
 */
object BriefingNotice {
    const val ID = 7
    private const val CHANNEL = "xarvis_briefing_v2"

    fun show(context: Context, text: String) {
        createChannel(context)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, BriefingActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, BriefingReceiver::class.java).setAction(BriefingReceiver.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_xarvis)
            .setContentTitle("Your briefing, sir")
            .setContentText(text.take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID, n)
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "Morning briefing", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Your daily spoken briefing"
            setSound(null, null) // silent: the spoken voice is the audio, no notification ding
            enableVibration(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
