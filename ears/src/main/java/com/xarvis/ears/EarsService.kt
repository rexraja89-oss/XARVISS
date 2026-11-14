package com.xarvis.ears

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.xarvis.wake.WakeEars

/**
 * The ears: a foreground service that listens for "Xarvis" with the screen on or off
 * (com.xarvis.wake.WakeEars) and passes what Rex says to XARVIS ([XarvisLink]). Android shows its
 * notification and the green mic dot the whole time.
 *
 * Android only gives a service the microphone when something on screen started it: Ears' own
 * screen, a tap on its notification, or XARVIS when it is opened (XARVIS nudges Ears every time) —
 * and on a phone where XARVIS has an accessibility service, XARVIS at any time. Started any other
 * way (after a phone restart) the service runs but hears silence; it says so in its notification
 * until one of those gives the microphone back.
 */
class EarsService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var ears: WakeEars? = null
    private var link: XarvisLink? = null
    private var lock: PowerManager.WakeLock? = null
    private var shown = ""

    /** Keeps the notification saying what the ears are doing. */
    private val watch = object : Runnable {
        override fun run() {
            val now = statusNow()
            if (now != shown) {
                shown = now
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(now))
            }
            main.postDelayed(this, 3000)
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val wanted = intent?.action != ACTION_STOP && EarsSwitch.isOn(this) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        // Android insists on the notification first, even when the service is about to stop.
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(statusNow()), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            Log.w(TAG, "Android wouldn't start the ears as a microphone service", e)
            runCatching { startForeground(NOTIFICATION_ID, notification(statusNow())) }
            shutDown()
            return START_NOT_STICKY
        }
        if (!wanted) {
            if (intent?.action == ACTION_STOP) EarsSwitch.set(this, false)
            shutDown()
            return START_NOT_STICKY
        }
        if (ears == null) {
            val xarvis = XarvisLink(this).also { link = it }
            xarvis.onChange = {
                // XARVIS on this phone listens by itself (the S22 with its switch on): two listeners would fight
                // over the microphone, so Ears stands down.
                if (xarvis.xarvisHasEars) {
                    stoodDown = true
                    shutDown()
                }
            }
            xarvis.connect()
            ears = WakeEars(this, xarvis).also { it.start() }
            lock = runCatching {
                (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xarvis:ears").apply { acquire() }
            }.getOrNull()
            main.postDelayed(watch, 2000)
        }
        running = true
        stoodDown = false
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacks(watch)
        ears?.stop()
        ears = null
        link?.disconnect()
        link = null
        runCatching { lock?.takeIf { it.isHeld }?.release() }
        lock = null
        super.onDestroy()
    }

    private fun shutDown() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun statusNow(): String {
        val e = ears ?: return "Starting…"
        val x = link
        return when {
            x != null && !x.connected && !XarvisLink.xarvisInstalled(this) -> "XARVIS isn't on this phone"
            else -> e.status
        }
    }

    private fun notification(status: String): Notification {
        val channel = NotificationChannel(CHANNEL_ID, "Listening for \"Xarvis\"", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while XARVIS Ears listens for \"Xarvis\""
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val blocked = status.startsWith("Android is keeping")
        // A tap on the notification is one of the things that lets Android give the microphone back.
        val resume = PendingIntent.getForegroundService(
            this, 20, Intent(this, EarsService::class.java).setAction(ACTION_START), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(this, 21, Intent(this, EarsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 22, Intent(this, EarsService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ears)
            .setContentTitle(if (blocked) "\"Xarvis\" is paused — tap here to resume" else status)
            .setContentText(
                if (blocked) "After a restart Android gives the microphone back when you tap this or open XARVIS."
                else "Say \"Xarvis\", wait for \"Yes, sir?\", then speak."
            )
            .setContentIntent(if (blocked) resume else open)
            .addAction(0, "Turn off", stop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "XarvisEars"
        private const val CHANNEL_ID = "xarvis_ears"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.xarvis.ears.START"
        /** XARVIS's nudge when it comes on screen (or from XARVIS Hands): listen again, with the microphone. */
        const val ACTION_KICK = "com.xarvis.ears.KICK"
        const val ACTION_STOP = "com.xarvis.ears.STOP"

        /** The service is up (for Ears' screen). */
        @Volatile var running = false
            private set

        /** The service stopped itself because this phone's XARVIS listens for "Xarvis" by itself. */
        @Volatile var stoodDown = false
            private set

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, EarsService::class.java).setAction(ACTION_START))
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't start the ears now", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EarsService::class.java))
        }
    }
}

/** Rex's on/off for XARVIS Ears. Off also switches the service off for other apps, so XARVIS's nudge does nothing. */
object EarsSwitch {
    fun isOn(context: Context): Boolean =
        context.getSharedPreferences("ears", Context.MODE_PRIVATE).getBoolean("on", false)

    fun set(context: Context, on: Boolean) {
        context.getSharedPreferences("ears", Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, EarsService::class.java),
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}

/**
 * After a phone restart or an update of Ears: the service comes back with its notification. Android withholds the
 * microphone from a service started like this, so the notification says "tap here to resume" until Rex taps it or
 * opens XARVIS (which nudges Ears).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in STARTS) return
        if (EarsSwitch.isOn(context)) EarsService.start(context)
    }

    private companion object {
        val STARTS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, "android.intent.action.QUICKBOOT_POWERON")
    }
}
