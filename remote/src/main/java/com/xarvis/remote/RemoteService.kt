package com.xarvis.remote

import android.app.Activity
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
 * Keeps XARVIS Remote reachable while "Let my linked phones control this phone" is on: a visible service (Android
 * needs one to keep the link up in the background), started at boot and after updates. It also holds screen
 * sharing for live video once "Start now" was tapped (Android 13 keeps that for as long as this service runs).
 * Its notification says who is connected, with STOP.
 */
class RemoteService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!RemotePrefs.hosting(this)) {
            // Started with startForegroundService: Android wants startForeground even when it stops at once.
            foreground(withVideo = false)
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                RemoteHost.stopPressed(this)
                foreground(withVideo = RemoteHost.videoReady)
            }
            ACTION_CONSENT -> {
                // Android 10+ wants a screen-sharing service running before it hands over the screen.
                foreground(withVideo = true)
                val code = intent.getIntExtra(EXTRA_CODE, 0)
                @Suppress("DEPRECATION")
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java) else intent.getParcelableExtra(EXTRA_DATA)
                val projection = if (code == Activity.RESULT_OK && data != null) {
                    runCatching { getSystemService(MediaProjectionManager::class.java)?.getMediaProjection(code, data) }.getOrNull()
                } else {
                    null
                }
                if (projection != null) RemoteHost.attach(this, projection) else foreground(withVideo = false)
            }
            else -> foreground(withVideo = RemoteHost.videoReady)
        }
        return START_STICKY
    }

    private fun foreground(withVideo: Boolean) {
        val notice = notification(this)
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                if (withVideo) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                startForeground(ID, notice, type)
            } else {
                startForeground(ID, notice)
            }
        }
    }

    override fun onDestroy() {
        RemoteHost.release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "xarvis_remote"
        private const val ID = 7501
        private const val ACTION_STOP = "com.xarvis.remote.STOP"
        private const val ACTION_CONSENT = "com.xarvis.remote.CONSENT"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, RemoteService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RemoteService::class.java)) }
        }

        /** "Start now" was tapped: hand the screen-sharing permission to the service for live video. */
        fun giveConsent(context: Context, code: Int, data: Intent) {
            runCatching {
                context.startForegroundService(
                    Intent(context, RemoteService::class.java).setAction(ACTION_CONSENT).putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data),
                )
            }
        }

        /** The notification follows what's happening: ready, or who is controlling the phone now. */
        fun refresh(context: Context) {
            if (!RemotePrefs.hosting(context)) return
            runCatching { context.getSystemService(NotificationManager::class.java)?.notify(ID, notification(context)) }
        }

        private fun notification(context: Context): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Remote control", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while your linked phones can reach this one"
                    setShowBadge(false)
                },
            )
            val open = PendingIntent.getActivity(
                context, 1, Intent(context, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
            )
            val by = RemoteHost.controller
            val b = Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .setContentIntent(open)
            if (by != null) {
                val stop = PendingIntent.getService(
                    context, 2, Intent(context, RemoteService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
                )
                b.setContentTitle("🔴 $by is controlling this phone")
                    .setContentText("Tap STOP to end it (it can't come back for a minute).")
                    .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "STOP", stop).build())
            } else {
                b.setContentTitle("🛟 XARVIS Remote is ready")
                    .setContentText(
                        if (RemoteHost.videoReady) "Your linked phones can reach this one, with live video."
                        else "Your linked phones can reach this one (pictures; live video after “Start now”).",
                    )
            }
            return b.build()
        }
    }
}
