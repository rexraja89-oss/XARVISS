package com.xarvis.helper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder

/**
 * Runs while she's being helped: Android requires a visible service (and notification) for screen sharing.
 * The notification always has STOP. Started by the screen after she allowed sharing (the consent's result).
 */
class HelperService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            HelperSession.end(this, "You pressed STOP.", stopped = true)
            stopSelf()
            return START_NOT_STICKY
        }
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java) else intent?.getParcelableExtra(EXTRA_DATA)
        val notice = notification(this, null)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(ID, notice)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val projection = runCatching { getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data) }.getOrNull()
        if (projection == null) {
            HelperSession.end(this, "Android didn't allow sharing the screen. Try again.")
            return START_NOT_STICKY
        }
        HelperSession.begin(this, projection)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (HelperSession.active) HelperSession.end(this, "Help ended.")
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "xarvis_helper_session"
        private const val CHANNEL_ASK = "xarvis_helper_ask"
        private const val ID = 7101
        private const val ASK_ID = 7102
        const val ACTION_STOP = "com.xarvis.helper.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"

        /** Starts the session with her screen-sharing consent. */
        fun start(context: Context, code: Int, data: Intent) {
            val i = Intent(context, HelperService::class.java).putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        /** The notification's text follows the session: waiting, or who is helping now. */
        fun update(context: Context, by: String?) {
            if (!HelperSession.active) return
            context.getSystemService(NotificationManager::class.java)?.notify(ID, notification(context, by))
        }

        private fun notification(context: Context, by: String?): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Help in progress", NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null) })
            val stop = PendingIntent.getService(
                context, 1, Intent(context, HelperService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
            )
            val open = PendingIntent.getActivity(
                context, 2, Intent(context, HelperActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
            )
            return Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle(if (by != null) "🟢 $by is helping you" else "⏳ Waiting for your helper…")
                .setContentText(if (by != null) "They can see your screen. Tap STOP to end." else "They'll see your screen when they connect. Tap STOP to end.")
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "STOP", stop).build())
                .build()
        }

        /** The helping phone tried to connect before she allowed it: ask her (a heads-up she can tap). */
        fun askToAllow(context: Context, from: String) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ASK, "Help offers", NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(
                context, 3,
                Intent(context, HelperActivity::class.java).putExtra(HelperActivity.EXTRA_OFFER, from).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            manager.notify(
                ASK_ID,
                Notification.Builder(context, CHANNEL_ASK)
                    .setSmallIcon(android.R.drawable.ic_menu_help)
                    .setContentTitle("$from wants to help you")
                    .setContentText("Tap to let them see your screen.")
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
        }

        fun clearAsk(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.cancel(ASK_ID)
        }
    }
}
