package com.xarvis.ai.hands

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.xarvis.ai.net.DeviceLink
import com.xarvis.ai.net.LinkException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * This phone's screen, seen and controlled by the linked phone — like TeamViewer, between Rex's own two
 * paired phones (the link is end-to-end encrypted and only paired phones can ask). The S22 hosts: XARVIS
 * Hands takes the screenshots (Accessibility, ~3 a second, no screen-recording prompt) and does the taps,
 * swipes, keys and typing; the benco shows it ([com.xarvis.ai.RemoteScreenActivity]).
 *
 * Never hidden: it only works after Rex ticks "Let my linked phone see and control this phone" on THIS
 * phone (☰ → XARVIS Hands; off by default), a red "… is controlling this phone · STOP" banner shows the
 * whole time, the screen stays on while it's in use, and STOP (or 15 s without use) ends it.
 */
object ScreenHost {
    private const val PREFS = "hands"
    private const val ALLOW = "remoteControl"
    private const val IDLE_END_MS = 15_000L
    private const val STOP_BLOCK_MS = 60_000L
    private const val WAKE_MAX_MS = 30 * 60_000L

    class Frame(val jpeg: ByteArray, val width: Int, val height: Int)

    /** Shown in XARVIS's chat when a session starts (the controller's name) and ends (null). */
    @Volatile var onSession: ((String?) -> Unit)? = null

    fun allowed(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ALLOW, false)

    fun setAllowed(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ALLOW, on).apply()
        if (!on) end()
    }

    /** The smooth session's stream, closed by STOP. */
    @Volatile private var live: DeviceLink.Channel? = null
    @Volatile private var controller: String? = null
    @Volatile private var lastUse = 0L
    @Volatile private var stoppedUntil = 0L
    @Volatile private var screenW = 0
    @Volatile private var screenH = 0
    private var wake: PowerManager.WakeLock? = null
    private val ui = Handler(Looper.getMainLooper())
    private val idleCheck = object : Runnable {
        override fun run() {
            if (controller == null) return
            if (System.currentTimeMillis() - lastUse > IDLE_END_MS) end() else ui.postDelayed(this, 5_000)
        }
    }

    /** A picture of this screen for [from], at most [maxSide] pixels on its long side. */
    suspend fun frame(context: Context, from: String, maxSide: Int): Frame {
        val hands = hands(context)
        touch(context, from, hands)
        val shot = capture(hands) ?: run { delay(400); capture(hands) }
            ?: throw LinkException("I couldn't capture its screen just now (is its screen on?). Try again.")
        // Touches are placed on the real screen; the picture may be smaller (the benco's Hands app sends it scaled).
        val (realW, realH) = ScreenVideo.realSize(context)
        screenW = realW
        screenH = realH
        return withContext(Dispatchers.Default) {
            val scale = minOf(1f, maxSide.toFloat() / maxOf(shot.width, shot.height))
            val small = if (scale < 1f) {
                Bitmap.createScaledBitmap(shot, (shot.width * scale).toInt(), (shot.height * scale).toInt(), true)
            } else {
                shot
            }
            val out = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            Frame(out.toByteArray(), realW, realH)
        }
    }

    /**
     * A command from [from]: {"kind":"tap"|"long"|"swipe", "x","y","x2","y2" (0–1 of the screen), "ms"},
     * {"kind":"key","name":"back"|"home"|…} or {"kind":"text","text":…,"enter":bool}.
     */
    fun input(context: Context, from: String, cmd: JSONObject): Boolean {
        val hands = hands(context)
        touch(context, from, hands)
        return command(context, hands, cmd)
    }

    private fun command(context: Context, hands: HandsBridge.Hands, cmd: JSONObject): Boolean {
        val (w, h) = size(context)
        return when (val kind = cmd.optString("kind")) {
            "tap", "long", "swipe" -> {
                // Through the picture's black bars when the phone turned (see ScreenMap); a tap on a bar does nothing.
                val from = ScreenMap.toScreen(cmd.optDouble("x", 0.5), cmd.optDouble("y", 0.5), w, h, videoW, videoH) ?: return false
                val to = ScreenMap.toScreen(cmd.optDouble("x2", 0.5), cmd.optDouble("y2", 0.5), w, h, videoW, videoH, clamp = true)!!
                hands.gesture(kind, from.first, from.second, to.first, to.second, cmd.optLong("ms", 300))
            }
            "key" -> hands.globalAction(cmd.optString("name"))
            "text" -> hands.typeText(cmd.optString("text"), cmd.optBoolean("enter"))
            // Smooth mode: show Android's screen-sharing prompt here, for Rex to tap "Start now" himself.
            "smooth" -> {
                ScreenShareService.ask(context)
                true
            }
            "paste" -> paste(context, hands, cmd.optString("text"))
            "unlock" -> unlock(context, hands)
            else -> false
        }
    }

    /**
     * The viewer's "🔓 Unlock" after its "🔒 Lock": screen on, then a swipe up to the lock screen's pattern / PIN
     * pad, which Rex draws or types from the other phone (his finger, live; nothing is stored).
     */
    private fun unlock(context: Context, hands: HandsBridge.Hands): Boolean {
        synchronized(this) {
            runCatching { wake?.takeIf { it.isHeld }?.release() }
            wake = null
        }
        wakeScreen(context)
        ui.postDelayed({
            if (context.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true) {
                val (w, h) = size(context)
                hands.gesture("swipe", w / 2f, h * 0.80f, w / 2f, h * 0.30f, 300)
            }
        }, 800)
        return true
    }

    /** Locked, or its screen is off: the viewer then offers "🔓 Unlock". */
    private fun lockedOrAsleep(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive == false ||
            context.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true

    /** The screen's size now (it changes when the phone turns: a game going landscape). */
    private fun size(context: Context): Pair<Int, Int> = ScreenVideo.realSize(context)

    /** The live video's size while one runs (0: still pictures), for mapping touches through its black bars. */
    @Volatile private var videoW = 0
    @Volatile private var videoH = 0

    /**
     * Smooth remote control for [from] over [ch]: live H.264 video when screen sharing is allowed (else
     * screenshots, ~3 a second), live touches and keys back. Runs until the viewer leaves, STOP, or switching
     * to video (the viewer reconnects).
     */
    suspend fun stream(context: Context, from: String, req: JSONObject, ch: DeviceLink.Channel, accept: (JSONObject) -> Unit) {
        val hands = hands(context)
        touch(context, from, hands)
        // A new connection always wakes the screen (Rex locked it from the viewer, ended, then came back: it stayed
        // dark and the viewer only said "frozen", 12 Nov 2026).
        wakeScreen(context)
        live?.close()
        live = ch
        val (w, h) = ScreenVideo.realSize(context)
        screenW = w
        screenH = h
        // Through the relay (away from home, no direct line): lighter video and pictures, to save data.
        val viaRelay = req.optString("route") == "relay"
        val maxSide = req.optInt("max", 1440).coerceIn(480, 2400).let { if (viaRelay) minOf(it, RELAY_MAX_SIDE) else it }
        val kbps = req.optInt("kbps", 4000).coerceIn(500, 20_000).let { if (viaRelay) minOf(it, RELAY_KBPS) else it }
        // Away from home the video starts lighter and follows what the line carries (BitrateControl).
        val rate = BitrateControl(max = kbps * 1000, start = BitrateControl.startFor(req.optString("route"), kbps * 1000))
        val video = withContext(Dispatchers.Main) {
            ScreenVideo.start(context, maxSide, rate.current)
        }
        videoW = video?.width ?: 0
        videoH = video?.height ?: 0
        accept(
            JSONObject().put("ok", true).put("mode", if (video != null) "video" else "jpeg")
                .put("w", w).put("h", h).put("vw", video?.width ?: 0).put("vh", video?.height ?: 0)
                // Only the S22 build can do live video (screen sharing); the benco sends pictures.
                .put("smoothable", com.xarvis.ai.workflow.hasScreen(context, "com.xarvis.ai.hands.ScreenConsentActivity")),
        )
        try {
            coroutineScope {
                // The viewer's fingers, keys and pings.
                launch(Dispatchers.IO) {
                    try {
                        while (true) {
                            val (type, payload) = ch.receive()
                            lastUse = System.currentTimeMillis()
                            when (type) {
                                StreamMsg.TOUCH -> finger(context, hands, JSONObject(String(payload)))
                                StreamMsg.INPUT -> {
                                    val cmd = JSONObject(String(payload))
                                    if (cmd.optString("kind") == "copy") {
                                        // The viewer's "Copy": send the selected text back for its clipboard.
                                        val text = withContext(Dispatchers.Main) { runCatching { hands.selectedText() }.getOrNull() }
                                        ch.send(StreamMsg.INFO, JSONObject().put("clip", text ?: JSONObject.NULL).toString().toByteArray())
                                    } else {
                                        withContext(Dispatchers.Main) { runCatching { command(context, hands, cmd) } }
                                    }
                                }
                                StreamMsg.PING -> ch.send(StreamMsg.PONG, payload)
                                StreamMsg.KEY_FRAME -> video?.let { v ->
                                    v.config?.let { ch.send(StreamMsg.VIDEO_CONFIG, it) }
                                    v.keyFrame()
                                }
                                StreamMsg.BYE -> break
                            }
                        }
                    } finally {
                        video?.stop()
                        ch.close()
                    }
                }
                // Locked or not, so the viewer shows 🔒 Lock or 🔓 Unlock; and a pattern / PIN screen's dots, which
                // Android hides from the picture (the viewer draws them over the black for Rex to draw on).
                launch(Dispatchers.IO) {
                    var last: Boolean? = null
                    var lastPad: String? = null
                    while (!ch.closed) {
                        val now = lockedOrAsleep(context)
                        if (now != last) {
                            ch.send(StreamMsg.INFO, JSONObject().put("locked", now).toString().toByteArray())
                            last = now
                        }
                        val (sw, sh) = size(context)
                        val pad = runCatching { hands.lockPad(sw, sh) }.getOrNull()
                        if (pad != lastPad) {
                            ch.send(StreamMsg.INFO, JSONObject().put("pad", pad?.let { JSONObject(it) } ?: JSONObject.NULL).toString().toByteArray())
                            lastPad = pad
                        }
                        delay(700)
                    }
                }
                // The screen.
                launch(Dispatchers.IO) {
                    try {
                        if (video != null) {
                            video.pump { type, bytes ->
                                val t0 = System.nanoTime()
                                ch.send(type, bytes)
                                val now = System.nanoTime()
                                rate.sent(now - t0, now)?.let { video.setBitrate(it) }
                                lastUse = System.currentTimeMillis()
                            }
                        } else {
                            // Through the relay a picture is sent only when the screen changed (a still
                            // screen costs nothing); the viewer's pings keep the line alive meanwhile.
                            var lastSent: ByteArray? = null
                            var lastSentAt = 0L
                            while (!ch.closed) {
                                // Rex allowed screen sharing meanwhile: tell the viewer to reconnect for video.
                                if (ScreenVideo.ready) {
                                    ch.send(StreamMsg.INFO, JSONObject().put("switch", "video").toString().toByteArray())
                                    break
                                }
                                // Locked from the viewer (screen off): wait for "🔓 Unlock" instead of failing.
                                if (context.getSystemService(PowerManager::class.java)?.isInteractive == false) {
                                    delay(500)
                                    continue
                                }
                                val jpeg = frame(context, from, if (viaRelay) RELAY_PICTURE_SIDE else maxSide).jpeg
                                val now = System.currentTimeMillis()
                                val same = viaRelay && lastSent?.contentEquals(jpeg) == true && now - lastSentAt < RELAY_SAME_PICTURE_MS
                                if (!same) {
                                    ch.send(StreamMsg.JPEG, jpeg)
                                    lastSent = jpeg
                                    lastSentAt = now
                                }
                                if (viaRelay) delay(RELAY_PICTURE_GAP_MS)
                            }
                        }
                    } catch (e: LinkException) {
                        ch.fail(e.message ?: "its screen stopped")
                        throw e
                    } finally {
                        ch.close()
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // The viewer left or the network dropped: nothing to report back.
        } finally {
            video?.stop()
            if (live === ch) {
                live = null
                videoW = 0
                videoH = 0
            }
            ch.close()
            if (controller == from) end()
        }
    }

    /** A live finger from the viewer: {"a":"down"|"move"|"up","x":0–1,"y":0–1}. */
    private fun finger(context: Context, hands: HandsBridge.Hands, t: JSONObject) {
        val (w, h) = size(context)
        val a = t.optString("a")
        // A finger going down on the black bars (the phone turned) touches nothing; moves and lifts are kept on screen.
        val (x, y) = ScreenMap.toScreen(t.optDouble("x", 0.5), t.optDouble("y", 0.5), w, h, videoW, videoH, clamp = a != "down") ?: return
        when (a) {
            "down" -> hands.touchDown(x, y)
            "move" -> hands.touchMove(x, y)
            "up" -> hands.touchUp(x, y)
        }
    }

    // ---- XARVIS acting on this phone for the linked one: "open WhatsApp on my S22 and read my last message" ----

    /** One of "open" (app name), "read", "tap" (label), "type" (text); the answer to show on the asking phone. */
    fun act(context: Context, from: String, kind: String, arg: String): String {
        val hands = hands(context)
        lastUse = System.currentTimeMillis()
        HandsBridge.log("$from asked: $kind ${arg.take(40)}")
        return when (kind) {
            "open" -> {
                val device = com.xarvis.ai.device.DeviceCapabilityManager(context)
                val app = device.findApp(arg) ?: return "There's no app called \"$arg\" on it."
                // Through Hands: Android lets an Accessibility service start apps from the background.
                if (hands.openApp(app.packageName)) "Opened ${app.label}." else "${app.label} can't be opened from outside."
            }
            "read" -> hands.screenText().ifBlank { "I couldn't read anything on its screen (is it locked or off?)." }
            "tap" -> if (hands.tapText(arg)) "Tapped \"$arg\"." else "I couldn't find \"$arg\" on its screen (or it looks like a payment / password, which I never tap)."
            "type" -> if (hands.typeText(arg, false)) "Typed it." else "No text box is selected on it: tap one first."
            // XARVIS using this phone by itself for the linked one (PhoneAgent): look, tap, scroll, keys, banner.
            "elements" -> hands.screenElements()
            "tapxy" -> {
                val (x, y) = arg.split(",").map { it.trim().toFloatOrNull() ?: 0f } + listOf(0f, 0f)
                hands.gesture("tap", x, y, x, y, 60)
                "ok"
            }
            "swipe" -> {
                val (w, h) = size(context)
                val down = arg != "up"
                hands.gesture("swipe", w / 2f, h * if (down) 0.72f else 0.30f, w / 2f, h * if (down) 0.30f else 0.72f, 350)
                "ok"
            }
            "key" -> if (hands.globalAction(arg)) "ok" else "no such key"
            "begin" -> {
                hands.controlBanner("XARVIS (asked on $from)") {
                    stoppedUntil = System.currentTimeMillis() + STOP_BLOCK_MS
                    HandsBridge.log("STOP pressed: XARVIS stopped working on this phone.")
                    hands.controlBanner(null) {}
                }
                onSession?.invoke("XARVIS (asked on $from)")
                "ok"
            }
            "end" -> {
                hands.controlBanner(null) {}
                onSession?.invoke(null)
                "ok"
            }
            else -> "I don't know how to \"$kind\" there."
        }
    }

    /** Puts [text] on this phone's clipboard and into the selected text box, if any (the viewer's "Paste"). */
    fun paste(context: Context, hands: HandsBridge.Hands, text: String): Boolean {
        context.getSystemService(android.content.ClipboardManager::class.java)
            ?.setPrimaryClip(android.content.ClipData.newPlainText("XARVIS", text))
        return hands.typeText(text, false)
    }

    /** The controlling phone closed its view. */
    fun stop(from: String) {
        if (controller == from) end()
    }

    private fun hands(context: Context): HandsBridge.Hands {
        if (!allowed(context)) throw LinkException(
            "remote control is switched off on it. On that phone: ☰ → XARVIS Hands → tick " +
                "\"Let my linked phone see and control this phone\".",
        )
        if (System.currentTimeMillis() < stoppedUntil) throw LinkException("STOP was pressed on it. Try again in a minute.")
        // The S22 has Hands inside XARVIS; the benco uses the separate XARVIS Hands app.
        return HandsBridge.service ?: ExternalHands.get(context) ?: throw LinkException(
            if (HandsBridge.builtIn) "XARVIS Hands is switched off on it (☰ → XARVIS Hands → Open Accessibility settings → XARVIS Hands → On)."
            else "the XARVIS Hands app on it is off or missing: open XARVIS Hands there, switch it on, and update it to the newest version.",
        )
    }

    private fun touch(context: Context, from: String, hands: HandsBridge.Hands) {
        lastUse = System.currentTimeMillis()
        if (controller == from) return
        controller = from
        HandsBridge.log("Remote control started by $from.")
        wakeScreen(context)
        hands.controlBanner(from) {
            stoppedUntil = System.currentTimeMillis() + STOP_BLOCK_MS
            HandsBridge.log("STOP pressed: remote control ended.")
            end()
        }
        onSession?.invoke(from)
        ui.removeCallbacks(idleCheck)
        ui.postDelayed(idleCheck, 5_000)
    }

    private fun end() {
        live?.close()
        live = null
        val was = controller ?: return
        controller = null
        ui.removeCallbacks(idleCheck)
        synchronized(this) {
            runCatching { wake?.takeIf { it.isHeld }?.release() }
            wake = null
        }
        HandsBridge.service?.controlBanner(null) {}
        HandsBridge.log("Remote control by $was ended.")
        onSession?.invoke(null)
    }

    /** Turn the screen on (if it was off) and keep it on while the session lasts. */
    @Suppress("DEPRECATION") // SCREEN_BRIGHT + ACQUIRE_CAUSES_WAKEUP still work on Android 13 and are the way to wake it
    private fun wakeScreen(context: Context) = synchronized(this) {
        val power = context.getSystemService(PowerManager::class.java) ?: return@synchronized
        if (wake?.isHeld == true) {
            // Still held, but the screen went off anyway (🔒 Lock from the viewer): only a new lock wakes it.
            if (power.isInteractive) return@synchronized
            runCatching { wake?.release() }
            wake = null
        }
        // The benco build has no WAKE_LOCK permission (its banner keeps the screen on instead).
        wake = runCatching {
            power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "xarvis:remote-control",
            ).apply { acquire(WAKE_MAX_MS) }
        }.getOrNull()
    }

    private suspend fun capture(hands: HandsBridge.Hands): Bitmap? = kotlinx.coroutines.withTimeoutOrNull(5_000) {
        suspendCancellableCoroutine { cont -> hands.screenshot { bitmap -> if (cont.isActive) cont.resume(bitmap) } }
    }

    private const val JPEG_QUALITY = 60
    /**
     * Remote control through the relay (EMQX's free 1 GB a month): smaller video (at most ~0.2 GB an hour, much
     * less on a still screen) and pictures sent only when the screen changes. The relay also stops a session
     * once the month's screen allowance is used (Relay.SCREEN_LIMIT_BYTES).
     */
    private const val RELAY_MAX_SIDE = 720
    private const val RELAY_KBPS = 500
    private const val RELAY_PICTURE_SIDE = 640
    private const val RELAY_PICTURE_GAP_MS = 300L
    /** A still screen through the relay is re-sent this often anyway (a picture lost on the way heals). */
    private const val RELAY_SAME_PICTURE_MS = 5_000L
}
