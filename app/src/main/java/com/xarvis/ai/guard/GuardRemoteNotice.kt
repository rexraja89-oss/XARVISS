package com.xarvis.ai.guard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import com.xarvis.ai.R

/** On Rex's other phone: "🚨 Intruder on your S22", loud, with the photo; tapping it opens the Guard remote. */
object GuardRemoteNotice {
    private const val CHANNEL = "xarvis_guard_alert"
    private const val ID = 4813

    fun show(context: Context, from: String, text: String, jpeg: ByteArray?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Intruder alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When someone else touches your guarded phone"
                enableVibration(true)
            },
        )
        val open = PendingIntent.getActivity(
            context, ID, Intent(context, com.xarvis.ai.GuardRemoteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_xarvis)
            .setContentTitle("🚨 Intruder on your $from")
            .setContentText("Tap to see them, speak to them, or stop the alarm.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
        jpeg?.let { BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply { inSampleSize = 4 }) }?.let {
            builder.setLargeIcon(it).setStyle(NotificationCompat.BigPictureStyle().bigPicture(it).setSummaryText(text.lineSequence().first()))
        }
        manager.notify(ID, builder.build())
    }
}
