package com.xarvis.ai.rescue

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.xarvis.ai.hands.HandsBridge
import com.xarvis.ai.rescue.RescueLogic.State
import com.xarvis.ai.rescue.RescueLogic.Trick
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * The phone that's stuck (the S22) gets itself unstuck when the other phone asks ("rescue my S22", link op `task`
 * action `rescue`): it reads its own screen (Hands, every window) and top sensor, tries the safe tricks one at a
 * time — swipe the pill, Home, Back, screen off/on — and reports each change to [report]. If the sensor is covered
 * it waits (up to an hour) for it to clear, then wakes the screen and says so. It never enters the pattern.
 */
object Rescuer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null

    const val HELP_LINE = "Excuse me. This is XARVIS, Rex's phone. Please turn me face up, or uncover my top edge. Thank you."
    private const val SETTLE_MS = 1_600L
    private const val KEEP_ON_MS = 2 * 60_000L
    private const val WAIT_CLEAR_MS = 60 * 60_000L

    /** op = rescue | wake | help | stop. Returns the first line at once; the rest goes to [report]. */
    fun handle(context: Context, op: String, report: (String) -> Unit): String {
        val app = context.applicationContext
        return when (op) {
            "stop" -> {
                job?.cancel()
                job = null
                release()
                "🛟 Rescue stopped."
            }
            "help" -> {
                callForHelp(app)
                "🛟 Calling out loud, 3 times: “$HELP_LINE”"
            }
            "wake", "rescue" -> {
                job?.cancel()
                job = scope.launch {
                    try {
                        if (op == "wake") wakeUp(app, report) else rescue(app, report)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        report("🛟 The rescue hit a problem: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
                if (op == "wake") "🛟 Waking my screen…" else "🛟 On it — checking my screen…"
            }
            else -> "I don't know that rescue request."
        }
    }

    private class Look(val state: State, val near: Boolean?, val app: String?) {
        fun line() = RescueLogic.describe(state, near, app)
    }

    private suspend fun look(context: Context): Look {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        val hands = HandsBridge.service
        val text = runCatching { hands?.allText() }.getOrNull().orEmpty()
        val (w, h) = com.xarvis.ai.hands.ScreenVideo.realSize(context)
        val pad = runCatching { hands?.lockPad(w, h) }.getOrNull() != null
        val state = RescueLogic.state(power?.isInteractive != false, keyguard?.isKeyguardLocked == true, text, pad)
        val app = text.lineSequence().firstOrNull()?.removePrefix("[")?.removeSuffix("]")
        return Look(state, nearNow(context), app)
    }

    private suspend fun wakeUp(context: Context, report: (String) -> Unit) {
        wakeScreen(context)
        delay(SETTLE_MS)
        var l = look(context)
        if (l.state == State.LOCKED) {
            HandsBridge.service?.globalAction("home")
            delay(SETTLE_MS)
            l = look(context)
        }
        val more = when {
            HandsBridge.service == null -> " (XARVIS Hands is off here, so I can't see more than that.)"
            l.state == State.TOUCH_GUARD -> " Say “rescue my S22” and I'll work on it."
            RescueLogic.done(l.state) -> " Screen stays on for 2 minutes."
            else -> ""
        }
        report("🛟 Awake. ${l.line()}$more")
        delay(KEEP_ON_MS)
        release()
    }

    private suspend fun rescue(context: Context, report: (String) -> Unit) {
        val hands = HandsBridge.service
        if (hands == null) {
            wakeScreen(context)
            report("🛟 XARVIS Hands is off on this phone, so I can't see or touch the screen. I've woken it; XARVIS Remote's view may still work.")
            delay(KEEP_ON_MS)
            release()
            return
        }
        var l = look(context)
        report("🛟 ${l.line()}")
        val tried = HashMap<State, Int>()
        var tries = 0
        while (!RescueLogic.done(l.state) && tries < RescueLogic.MAX_TRIES) {
            val list = RescueLogic.tricks(l.state)
            val i = tried[l.state] ?: 0
            if (i >= list.size) break
            tried[l.state] = i + 1
            tries++
            perform(context, hands, list[i])
            delay(SETTLE_MS)
            val next = look(context)
            if (next.state != l.state) report("🛟 ${RescueLogic.did(list[i])} → ${next.line()}")
            l = next
        }
        if (RescueLogic.done(l.state)) return ready(l, report)

        if (l.state == State.TOUCH_GUARD && l.near != false) {
            report("🛟 Nothing gets past it while my top sensor is covered. I'll turn the screen off and watch the sensor for up to an hour — the moment it's clear I'll wake up and tell you. (“call for help on my S22” asks anyone nearby to turn me over.)")
            release()
            hands.globalAction("lock")
            if (!waitClear(context, WAIT_CLEAR_MS)) {
                report("🛟 Still covered after an hour, so I've stopped watching. Say “rescue my S22” to try again.")
                return
            }
            delay(1_000)
            wakeScreen(context)
            delay(SETTLE_MS)
            l = look(context)
            if (l.state == State.LOCKED) {
                hands.globalAction("home")
                delay(SETTLE_MS)
                l = look(context)
            }
            if (RescueLogic.done(l.state)) return ready(l, report, "My sensor is clear. ")
            report("🛟 My sensor is clear and the screen is on. ${l.line()} Try ⬤ Home in the remote view.")
            delay(KEEP_ON_MS)
            release()
            return
        }
        report("🛟 I couldn't get past it. ${l.line()} Try ⬤ Home or 🔒 Lock then 🔓 Unlock in the remote view.")
        release()
    }

    private suspend fun ready(l: Look, report: (String) -> Unit, lead: String = "") {
        report("🛟 Ready. $lead${l.line()} I'll keep the screen on for 2 minutes.")
        delay(KEEP_ON_MS)
        release()
    }

    private suspend fun perform(context: Context, hands: HandsBridge.Hands, trick: Trick) {
        val (w, h) = com.xarvis.ai.hands.ScreenVideo.realSize(context)
        when (trick) {
            Trick.WAKE -> wakeScreen(context)
            // Samsung's pill sits in the middle of the screen; a slow drag from its bottom past its top.
            Trick.SWIPE_PILL -> hands.gesture("swipe", w * 0.5f, h * 0.62f, w * 0.5f, h * 0.22f, 700)
            Trick.SWIPE_UP -> hands.gesture("swipe", w * 0.5f, h * 0.85f, w * 0.5f, h * 0.35f, 400)
            Trick.HOME -> hands.globalAction("home")
            Trick.BACK -> hands.globalAction("back")
            Trick.RECYCLE -> {
                release()
                hands.globalAction("lock")
                delay(3_000)
                wakeScreen(context)
            }
        }
    }

    @Suppress("DEPRECATION") // SCREEN_BRIGHT + ACQUIRE_CAUSES_WAKEUP still wake the screen on Android 13
    private fun wakeScreen(context: Context) = synchronized(this) {
        val power = context.getSystemService(PowerManager::class.java) ?: return@synchronized
        if (wake?.isHeld == true && power.isInteractive) return@synchronized
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        // The benco build has no WAKE_LOCK permission: nothing to wake there.
        wake = runCatching {
            power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "xarvis:rescue")
                .apply { acquire(KEEP_ON_MS + 60_000) }
        }.getOrNull()
    }

    private fun release() = synchronized(this) {
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        wake = null
    }

    // ---- the top (proximity) sensor ----

    private fun sensor(sm: SensorManager): Sensor? =
        sm.getDefaultSensor(Sensor.TYPE_PROXIMITY, true) ?: sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    /** Covered right now? null = no sensor or no reading within 2 s. */
    private suspend fun nearNow(context: Context): Boolean? {
        val sm = context.getSystemService(SensorManager::class.java) ?: return null
        val s = sensor(sm) ?: return null
        return withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) {
                        sm.unregisterListener(this)
                        if (cont.isActive) cont.resume(RescueLogic.near(e.values[0], s.maximumRange))
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }
                sm.registerListener(listener, s, SensorManager.SENSOR_DELAY_NORMAL)
                cont.invokeOnCancellation { sm.unregisterListener(listener) }
            }
        }
    }

    /** Waits until the sensor reads clear (true) or [ms] passes (false). Keeps the processor up only if it must. */
    private suspend fun waitClear(context: Context, ms: Long): Boolean {
        val sm = context.getSystemService(SensorManager::class.java) ?: return false
        val s = sensor(sm) ?: return false
        val cpu = if (s.isWakeUpSensor) null else runCatching {
            context.getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xarvis:rescue-wait")?.apply { acquire(ms + 10_000) }
        }.getOrNull()
        try {
            return withTimeoutOrNull(ms) {
                suspendCancellableCoroutine { cont ->
                    val listener = object : SensorEventListener {
                        override fun onSensorChanged(e: SensorEvent) {
                            if (RescueLogic.near(e.values[0], s.maximumRange)) return
                            sm.unregisterListener(this)
                            if (cont.isActive) cont.resume(true)
                        }
                        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                    }
                    sm.registerListener(listener, s, SensorManager.SENSOR_DELAY_NORMAL)
                    cont.invokeOnCancellation { sm.unregisterListener(listener) }
                }
            } ?: false
        } finally {
            runCatching { cpu?.takeIf { it.isHeld }?.release() }
        }
    }

    // ---- "call for help": said out loud on the alarm volume, 3 times ----

    private fun callForHelp(context: Context) {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context, { status ->
            val t = tts ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech t.shutdown()
            t.language = Locale.UK
            t.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { if (id == "help2") t.shutdown() }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { t.shutdown() }
            })
            repeat(3) { i ->
                t.speak(HELP_LINE, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "help$i")
                if (i < 2) t.playSilentUtterance(1_500, TextToSpeech.QUEUE_ADD, "gap$i")
            }
        }, "com.google.android.tts")
    }
}
