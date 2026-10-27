package com.xarvis.ai.brief

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.xarvis.ai.BriefingActivity
import com.xarvis.ai.R

/** Shows the morning briefing as a notification, so it's there to read even if the phone was silent. */
object BriefingNotice {
    private const val CHANNEL = "xarvis_briefing"
    private const val ID = 7

    fun show(context: Context, text: String) {
        createChannel(context)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, BriefingActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_xarvis)
            .setContentTitle("Your briefing, sir")
            .setContentText(text.take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID, n)
    }

    private fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "Morning briefing", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Your daily spoken briefing"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
