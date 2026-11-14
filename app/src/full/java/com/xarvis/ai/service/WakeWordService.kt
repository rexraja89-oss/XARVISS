package com.xarvis.ai.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.xarvis.ai.MainActivity
import com.xarvis.ai.R
import com.xarvis.ai.XarvisApp
import com.xarvis.ai.tools.PermissionGate
import com.xarvis.wake.WakeEars
import com.xarvis.wake.WakeHost

/**
 * "Xarvis" on the S22: XARVIS listens with the microphone, screen on or off (offline, on this
 * phone). When it hears its name it says "Yes, sir?", hears what Rex says, answers aloud and keeps
 * listening for more — then goes back to waiting for "Xarvis" (com.xarvis.wake.WakeEars). Android
 * shows a notification and the green mic dot the whole time; the notification's Turn off stops it.
 *
 * Android only lets a service use the microphone if it was started while XARVIS was on screen —
 * or by an app with an accessibility service, which XARVIS Hands is: so after a restart the ears
 * come back by themselves when Hands is on (XarvisHandsService starts them), else when Rex opens
 * XARVIS.
 */
class WakeWordService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var ears: WakeEars? = null
    private var lock: PowerManager.WakeLock? = null
    private var shown = ""

    private val host = object : WakeHost {
        override fun state(): Int = core().voiceState()
        override fun wake(interrupting: Boolean, done: (Boolean) -> Unit) = core().voiceWake(interrupting) { done(true) }
        override fun heard(text: String, done: (Boolean) -> Unit) = core().voiceHeard(text, done)
        override fun over() = core().voiceOver()
    }

    /** Keeps the notification saying what the ears are doing (listening, or waiting for XARVIS to be opened). */
    private val watch = object : Runnable {
        override fun run() {
            val now = ears?.status ?: return
            if (now != shown) {
                shown = now
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(now))
            }
            main.postDelayed(this, 3000)
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            WakeWord.setEnabled(this, false)
            shutDown()
            return START_NOT_STICKY
        }
        if (!PermissionGate.has(this, Manifest.permission.RECORD_AUDIO)) {
            shutDown()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(ears?.status ?: LISTENING), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            Log.w(TAG, "Android won't let the mic listen in the background now; open XARVIS to start it", e)
            shutDown()
            return START_NOT_STICKY
        }
        if (ears == null) {
            ears = WakeEars(this, host).also { it.start() }
            // The microphone keeps the phone's processor awake by itself; this makes sure of it on every phone.
            lock = runCatching {
                (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xarvis:ears").apply { acquire() }
            }.getOrNull()
            main.postDelayed(watch, 3000)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(watch)
        ears?.stop()
        ears = null
        runCatching { lock?.takeIf { it.isHeld }?.release() }
        lock = null
        super.onDestroy()
    }

    private fun shutDown() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun core() = (application as XarvisApp).core

    private fun notification(status: String): android.app.Notification {
        val channel = NotificationChannel(CHANNEL_ID, "Wake word", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while XARVIS listens for \"Xarvis\""
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val open = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 11, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val blocked = status.startsWith("Android is keeping")
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_xarvis)
            .setContentTitle(if (blocked) "\"Xarvis\" is paused — tap to open XARVIS" else status)
            .setContentText(
                if (blocked) "After a restart Android gives the microphone back when XARVIS is opened once."
                else "Say \"Xarvis\", wait for \"Yes, sir?\", then speak."
            )
            .setContentIntent(open)
            .addAction(0, "Turn off", stop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "XarvisWakeWord"
        private const val CHANNEL_ID = "xarvis_wakeword"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_STOP = "com.xarvis.ai.WAKEWORD_STOP"
        private const val LISTENING = "Listening for \"Xarvis\""

        /**
         * Starts listening, or — when it's already running but Android was withholding the microphone — gets it
         * the microphone. Only works from XARVIS on screen or from XARVIS Hands (see the class note).
         */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't start listening now", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }
}
