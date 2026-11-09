package com.xarvis.ai.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.xarvis.ai.hands.HandsBridge
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Guard mode (Rex: "protect it from my wife 😉"): after "XARVIS, guard my phone" and a 10-second countdown,
 * picking the phone up, unlocking it or touching anything makes XARVIS look through the front camera
 * ([GUARD_SCREEN], S22 build). Rex's face → guard off, quietly. Anyone else, or no face → [GuardAlarm].
 *
 * Movement is judged on the smoothed direction of gravity (the phone being tilted or lifted), so a call or a
 * notification buzzing on the table doesn't count. A partial wake lock keeps the sensor read with the screen off.
 */
object GuardMode : SensorEventListener {
    enum class State { OFF, ARMING, ARMED, CHECKING, ALARM }

    const val GUARD_SCREEN = "com.xarvis.ai.GuardActivity"
    const val EXTRA_MODE = "mode"
    private const val ARM_DELAY_MS = 10_000L
    private const val TILT_DEGREES = 10.0
    private const val LIFT = 1.2f // m/s² change of the smoothed reading
    private const val WAKE_MAX_MS = 12 * 60 * 60_000L

    @Volatile var state = State.OFF
        private set
    val armed: Boolean get() = state == State.ARMED

    private var app: Context? = null
    private val ui = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null
    private var smooth: FloatArray? = null
    private var baseline: FloatArray? = null
    private var settling = 0
    private var strikes = 0

    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT) trigger(c, "phone unlocked")
        }
    }

    /** Guard mode needs the S22 build (camera + face recognition). */
    fun available(context: Context): Boolean = com.xarvis.ai.workflow.hasScreen(context, GUARD_SCREEN)

    /** The face that may touch the phone: the taught face named Rex/Sajjad, else the one taught with most photos. */
    fun owner(context: Context): String? {
        val all = com.xarvis.ai.faces.FaceStore(context).all()
        return all.keys.firstOrNull { it.contains("rex", ignoreCase = true) || it.contains("sajjad", ignoreCase = true) }
            ?: all.maxByOrNull { it.value.size }?.key
    }

    fun arm(context: Context): String {
        if (!available(context)) return "Guard mode needs the S22's camera, sir — ask me there."
        val owner = owner(context) ?: return "Teach me your face first, sir: ☰ → XARVIS Eyes → Teach a face."
        if (state != State.OFF) return "I'm already guarding the phone, sir."
        val c = context.applicationContext
        app = c
        state = State.ARMING
        com.xarvis.ai.service.XarvisService.start(c) // keeps XARVIS alive while it guards
        ui.postDelayed({ startWatching(c) }, ARM_DELAY_MS)
        HandsBridge.log("Guard mode: arming (owner '$owner').")
        return "🛡 Guard mode on in 10 seconds, sir — put the phone down. Only you ($owner) may touch it; anyone else meets the alarm."
    }

    private fun startWatching(c: Context) {
        if (state != State.ARMING) return
        smooth = null
        baseline = null
        settling = 0
        strikes = 0
        val sensors = c.getSystemService(SensorManager::class.java)
        sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        ContextCompat.registerReceiver(c, unlocked, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED)
        wake = runCatching {
            c.getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xarvis:guard")
                ?.apply { acquire(WAKE_MAX_MS) }
        }.getOrNull()
        state = State.ARMED
        HandsBridge.log("Guard mode: guarding.")
    }

    private fun stopWatching() {
        val c = app ?: return
        runCatching { c.getSystemService(SensorManager::class.java)?.unregisterListener(this) }
        runCatching { c.unregisterReceiver(unlocked) }
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        wake = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (state != State.ARMED) return
        val v = event.values
        // Smooth out vibration (a buzzing call), keep the slow change of a lift or tilt.
        val previous = smooth
        val s = previous?.let { p -> FloatArray(3) { i -> p[i] * 0.85f + v[i] * 0.15f } } ?: v.copyOf()
        smooth = s
        val base = baseline
        if (base == null) {
            // Rex may still be holding it after the countdown: wait until it lies still (~1.5 s), then remember that.
            val still = previous != null && (0..2).all { kotlin.math.abs(s[it] - previous[it]) < 0.12f }
            settling = if (still) settling + 1 else 0
            if (settling >= 20) {
                baseline = s.copyOf()
                HandsBridge.log("Guard mode: phone at rest — guarding.")
            }
            return
        }
        val change = sqrt((0..2).sumOf { ((s[it] - base[it]) * (s[it] - base[it])).toDouble() }).toFloat()
        if (change > LIFT || angle(s, base) > TILT_DEGREES) {
            if (++strikes >= 2) trigger(app ?: return, "phone moved")
        } else {
            strikes = 0
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun angle(a: FloatArray, b: FloatArray): Double {
        val dot = (0..2).sumOf { (a[it] * b[it]).toDouble() }
        val la = sqrt((0..2).sumOf { (a[it] * a[it]).toDouble() })
        val lb = sqrt((0..2).sumOf { (b[it] * b[it]).toDouble() })
        if (la < 1e-3 || lb < 1e-3) return 0.0
        return Math.toDegrees(acos((dot / (la * lb)).coerceIn(-1.0, 1.0)))
    }

    /** Someone moved, unlocked or touched the phone: look at them. */
    fun trigger(context: Context, why: String) {
        if (state != State.ARMED) return
        state = State.CHECKING
        HandsBridge.log("Guard mode: $why — checking who it is.")
        launch(context.applicationContext, "check")
    }

    /** The camera saw Rex: guard off, quietly. */
    fun ownerSeen() {
        stopWatching()
        state = State.OFF
        HandsBridge.log("Guard mode: it was Rex — guard off.")
    }

    /** Someone else: the alarm takes over (watching stops; the alarm keeps the screen and voice going). */
    fun intruder() {
        stopWatching()
        state = State.ALARM
    }

    fun disarm() {
        ui.removeCallbacksAndMessages(null)
        stopWatching()
        state = State.OFF
        HandsBridge.log("Guard mode: off.")
    }

    /** Open the guard screen: over the lock screen when it's dark/locked (full-screen notice), else directly. */
    fun launch(context: Context, mode: String) {
        val intent = Intent().setClassName(context, GUARD_SCREEN).putExtra(EXTRA_MODE, mode)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (com.xarvis.ai.workflow.RemoteCameraNotice.asleep(context)) {
            GuardNotice.show(context, intent)
        } else {
            // Allowed from the background because XARVIS Hands (Accessibility) is on.
            runCatching { context.startActivity(intent) }.onFailure { GuardNotice.show(context, intent) }
        }
    }
}

/** A full-screen notification that wakes the phone into the guard screen, the way an incoming call does. */
object GuardNotice {
    private const val CHANNEL = "xarvis_guard"
    private const val ID = 4812

    fun show(context: Context, intent: Intent) {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(
            android.app.NotificationChannel(CHANNEL, "Guard mode", android.app.NotificationManager.IMPORTANCE_HIGH).apply {
                description = "XARVIS checking who touched the phone"
            },
        )
        val open = android.app.PendingIntent.getActivity(
            context, ID, intent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = androidx.core.app.NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(com.xarvis.ai.R.drawable.ic_stat_xarvis)
            .setContentTitle("🛡 XARVIS Guard")
            .setContentText("Checking who touched the phone…")
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setAutoCancel(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
            .setTimeoutAfter(60_000L)
            .build()
        manager.notify(ID, n)
    }

    fun cancel(context: Context) {
        context.getSystemService(android.app.NotificationManager::class.java)?.cancel(ID)
    }
}
