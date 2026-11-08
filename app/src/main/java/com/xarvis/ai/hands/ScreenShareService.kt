package com.xarvis.ai.hands

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.xarvis.ai.R

/**
 * Asks Android for screen sharing ("XARVIS will start capturing…" → Start now) for smooth remote control.
 * Rex taps it on the S22 itself — or, while already controlling it from the benco in the slower mode, on
 * the S22's screen as he sees it there. XARVIS never presses it for him. (S22 build: declared in its manifest.)
 */
class ScreenConsentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            finish()
            return
        }
        runCatching { startActivityForResult(manager.createScreenCaptureIntent(), REQUEST) }.onFailure { finish() }
    }

    @Deprecated("Activity result API isn't needed for this one-off prompt")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST && resultCode == RESULT_OK && data != null) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, ScreenShareService::class.java)
                    .putExtra(ScreenShareService.EXTRA_CODE, resultCode).putExtra(ScreenShareService.EXTRA_DATA, data),
            )
        } else {
            Toast.makeText(this, "Smooth mode not allowed: remote control stays in the slower mode.", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    companion object {
        private const val REQUEST = 7301
    }
}

/**
 * Holds the screen-sharing permission while XARVIS runs (Android needs a "media projection" foreground
 * service for it). Its notification says smooth remote control is ready, with a Stop button.
 */
class ScreenShareService : Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ScreenVideo.release()
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        val projection = if (code == Activity.RESULT_OK && data != null) {
            runCatching { getSystemService(MediaProjectionManager::class.java)?.getMediaProjection(code, data) }
                .onFailure { HandsBridge.log("Screen sharing failed: ${it.message}") }.getOrNull()
        } else {
            null
        }
        if (projection == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        ScreenVideo.attach(projection) { stopSelf() }
        HandsBridge.log("Smooth screen sharing is ready for your linked phone.")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ScreenVideo.release()
        super.onDestroy()
    }

    private fun notification(): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Remote control", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while your linked phone may see this screen smoothly"
                setShowBadge(false)
            },
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_xarvis)
            .setContentTitle("🖥 Smooth remote control is ready")
            .setContentText("Your linked phone may see this screen as live video while it controls it.")
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val ACTION_STOP = "com.xarvis.ai.SCREEN_SHARE_STOP"
        private const val CHANNEL = "xarvis_remote_control"
        private const val NOTIFICATION_ID = 7302

        /** Ask for smooth mode (the "Start now" prompt) — from the S22's own screen or a remote session. */
        fun ask(context: Context) {
            runCatching {
                context.startActivity(
                    Intent().setClassName(context, "com.xarvis.ai.hands.ScreenConsentActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { HandsBridge.log("Couldn't ask for screen sharing: ${it.message}") }
        }
    }
}
