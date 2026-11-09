package com.xarvis.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/** "🙋 <her phone> asks for your help" — tapping it opens her screen (RemoteScreenActivity) straight away. */
object HelpRequestNotice {
    private const val CHANNEL = "xarvis_help_request"

    fun show(context: Context, peerId: String, name: String, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Family help requests", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When a family phone with XARVIS Helper asks you for help"
                enableVibration(true)
            },
        )
        val id = 4900 + (peerId.hashCode() and 0xFF)
        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, RemoteScreenActivity::class.java).putExtra(RemoteScreenActivity.EXTRA_PEER_ID, peerId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            id,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_xarvis)
                .setContentTitle("🙋 $name asks for your help")
                .setContentText(text.ifBlank { "Tap to see their screen." })
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .build(),
        )
    }
}
