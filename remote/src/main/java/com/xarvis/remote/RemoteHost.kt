package com.xarvis.remote

import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.Display
import com.xarvis.ai.hands.BitrateControl
import com.xarvis.ai.hands.ScreenMap
import com.xarvis.ai.hands.StreamMsg
import com.xarvis.ai.net.DeviceLink
import com.xarvis.ai.net.LinkException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * This phone, controlled by a linked phone with XARVIS Remote — no one needs to be here to allow it (Rex's own
 * phone, switched on once with "Let my linked phones control this phone"). Live H.264 video once screen sharing was
 * allowed ("Start now", tappable from the other phone's picture), else Accessibility screenshots (~3 a second);
 * live finger, keys, text, lock and unlock back. A red ✱ shows the whole time; STOP ends it for a minute.
 */
object RemoteHost {
    private val ui = Handler(Looper.getMainLooper())

    @Volatile private var projection: MediaProjection? = null
    /** Android 14+ allows one screen mirror per "Start now": it's made once and pointed at each new encoder. */
    @Volatile private var display: VirtualDisplay? = null
    @Volatile private var live: DeviceLink.Channel? = null
    @Volatile private var stoppedUntil = 0L
    private var wake: PowerManager.WakeLock? = null

    /** Who is controlling this phone now (null: nobody), for the notification and the screen. */
    @Volatile var controller: String? = null
        private set

    /** Live video is possible (screen sharing was allowed since this phone started). */
    val videoReady: Boolean get() = projection != null

    // ---- screen sharing ("Start now") ----

    fun attach(context: Context, p: MediaProjection) {
        projection?.takeIf { it !== p }?.let { old -> runCatching { old.stop() } }
        runCatching { display?.release() }
        display = null
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (projection === p) {
                    projection = null
                    runCatching { display?.release() }
                    display = null
                    RemoteService.refresh(context)
                }
            }
        }, ui)
        RemoteService.refresh(context)
    }

    fun release() {
        live?.close()
        live = null
        runCatching { display?.release() }
        display = null
        val p = projection
        projection = null
        runCatching { p?.stop() }
    }

    // ---- sessions ----

    private fun checkAllowed(context: Context): RemoteHands {
        if (!RemotePrefs.hosting(context)) throw LinkException(
            "remote control is switched off on it. On that phone: open XARVIS Remote → switch on \"Let my linked phones control this phone\".",
        )
        if (System.currentTimeMillis() < stoppedUntil) throw LinkException("STOP was pressed on it. Try again in a minute.")
        return RemoteHands.instance ?: throw LinkException(
            "XARVIS Remote's Accessibility switch is switched off on it (Settings → Accessibility → Installed apps → XARVIS Remote → On).",
        )
    }

    /** STOP on this phone (the ✱ or the notification): end it, and keep it out for a minute. */
    fun stopPressed(context: Context) {
        stoppedUntil = System.currentTimeMillis() + STOP_BLOCK_MS
        live?.let { ch -> runCatching { ch.fail("STOP was pressed on it. Try again in a minute.") } }
        endAll(context)
    }

    /** The viewer closed (an older viewer's "screen_stop"). */
    fun stop(context: Context, from: String) {
        if (controller == from) endAll(context)
    }

    fun endAll(context: Context) {
        live?.close()
        live = null
        end(context)
    }

    private fun begin(context: Context, from: String, hands: RemoteHands) {
        controller = from
        wakeScreen(context)
        hands.controlBanner(from) { stopPressed(context) }
        RemoteService.refresh(context)
    }

    private fun end(context: Context) {
        controller = null
        synchronized(this) {
            runCatching { wake?.takeIf { it.isHeld }?.release() }
            wake = null
        }
        RemoteHands.instance?.controlBanner(null) {}
        RemoteService.refresh(context)
    }

    /**
     * The linked phone [from] connects (DeviceLink op "stream"): this screen as live video or pictures, its finger,
     * keys and text back, until it leaves, STOP, or (pictures) "Start now" was tapped — then it reconnects for video.
     */
    suspend fun stream(context: Context, from: String, req: JSONObject, ch: DeviceLink.Channel, accept: (JSONObject) -> Unit) {
        val hands = checkAllowed(context)
        live?.close() // a newer connection from the viewer (it reconnected) takes over
        live = ch
        begin(context, from, hands)
        val (w, h) = realSize(context)
        // Through the relay (Rex's free 1 GB a month): lighter, like XARVIS's own remote control.
        val viaRelay = req.optString("route") == "relay"
        val maxSide = req.optInt("max", 1440).coerceIn(480, 2400).let { if (viaRelay) minOf(it, RELAY_MAX_SIDE) else it }
        val kbps = req.optInt("kbps", 4000).coerceIn(500, 20_000).let { if (viaRelay) minOf(it, RELAY_KBPS) else it }
        val rate = BitrateControl(max = kbps * 1000, start = BitrateControl.startFor(req.optString("route"), kbps * 1000))
        val enc = projection?.let { p -> withContext(Dispatchers.Main) { startEncoder(context, p, w, h, maxSide, rate.current) } }
        accept(
            JSONObject().put("ok", true).put("mode", if (enc != null) "video" else "jpeg")
                .put("w", w).put("h", h).put("vw", enc?.width ?: 0).put("vh", enc?.height ?: 0)
                .put("smoothable", true).put("control", true),
        )
        try {
            coroutineScope {
                // The other phone's finger, keys and pings.
                launch(Dispatchers.IO) {
                    try {
                        while (true) {
                            val (type, payload) = ch.receive()
                            when (type) {
                                StreamMsg.TOUCH -> finger(context, hands, JSONObject(String(payload)), enc)
                                StreamMsg.INPUT -> {
                                    val cmd = JSONObject(String(payload))
                                    if (cmd.optString("kind") == "copy") {
                                        val text = withContext(Dispatchers.Main) { runCatching { hands.selectedText() }.getOrNull() }
                                        ch.send(StreamMsg.INFO, JSONObject().put("clip", text ?: JSONObject.NULL).toString().toByteArray())
                                    } else {
                                        withContext(Dispatchers.Main) { runCatching { command(context, hands, cmd, enc) } }
                                    }
                                }
                                StreamMsg.PING -> ch.send(StreamMsg.PONG, payload)
                                StreamMsg.KEY_FRAME -> enc?.let { e ->
                                    e.config?.let { ch.send(StreamMsg.VIDEO_CONFIG, it) }
                                    e.keyFrame()
                                }
                                StreamMsg.BYE -> break
                            }
                        }
                    } finally {
                        enc?.let { stopEncoder(it) }
                        ch.close()
                    }
                }
                // Locked or not (🔒 Lock / 🔓 Unlock), and a pattern / PIN screen's dots, hidden from the picture.
                launch(Dispatchers.IO) {
                    var last: Boolean? = null
                    var lastPad: String? = null
                    while (!ch.closed) {
                        val now = lockedOrAsleep(context)
                        if (now != last) {
                            ch.send(StreamMsg.INFO, JSONObject().put("locked", now).toString().toByteArray())
                            last = now
                        }
                        val (sw, sh) = realSize(context)
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
                        if (enc != null) {
                            enc.pump { type, bytes ->
                                val t0 = System.nanoTime()
                                ch.send(type, bytes)
                                val now = System.nanoTime()
                                rate.sent(now - t0, now)?.let { enc.setBitrate(it) }
                            }
                        } else {
                            pictures(context, hands, ch, viaRelay, maxSide)
                        }
                    } finally {
                        ch.close()
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // The viewer left or the line dropped (it reconnects by itself).
        } finally {
            enc?.let { stopEncoder(it) }
            ch.close()
            if (live === ch) {
                live = null
                end(context)
            }
        }
    }

    /** Pictures until the viewer leaves — or until live video is allowed, when the viewer reconnects for it. */
    private suspend fun pictures(context: Context, hands: RemoteHands, ch: DeviceLink.Channel, viaRelay: Boolean, maxSide: Int) {
        var lastSent: ByteArray? = null
        var lastSentAt = 0L
        var failingSince = 0L
        while (!ch.closed) {
            if (projection != null) {
                ch.send(StreamMsg.INFO, JSONObject().put("switch", "video").toString().toByteArray())
                return
            }
            // Locked from the viewer (screen off): wait for "🔓 Unlock" instead of failing.
            if (context.getSystemService(PowerManager::class.java)?.isInteractive == false) {
                delay(500)
                continue
            }
            val shot = capture(hands)
            if (shot == null) {
                if (failingSince == 0L) failingSince = System.currentTimeMillis()
                if (System.currentTimeMillis() - failingSince > 10_000) {
                    ch.fail("I couldn't capture its screen (is its screen on?). Trying again…")
                    return
                }
                delay(350)
                continue
            }
            failingSince = 0L
            val jpeg = withContext(Dispatchers.Default) { jpeg(shot, if (viaRelay) RELAY_PICTURE_SIDE else minOf(maxSide, PICTURE_SIDE)) }
            val now = System.currentTimeMillis()
            // Through the relay a still screen isn't sent again (it costs nothing), except now and then.
            val same = viaRelay && lastSent?.contentEquals(jpeg) == true && now - lastSentAt < RELAY_SAME_PICTURE_MS
            if (!same) {
                ch.send(StreamMsg.JPEG, jpeg)
                lastSent = jpeg
                lastSentAt = now
            }
            if (viaRelay) delay(RELAY_PICTURE_GAP_MS)
        }
    }

    private suspend fun capture(hands: RemoteHands): Bitmap? = withTimeoutOrNull(5_000) {
        suspendCancellableCoroutine { cont -> hands.screenshot { bmp -> if (cont.isActive) cont.resume(bmp) } }
    }

    private fun jpeg(shot: Bitmap, maxSide: Int): ByteArray {
        val scale = minOf(1f, maxSide.toFloat() / maxOf(shot.width, shot.height))
        val small = if (scale < 1f) Bitmap.createScaledBitmap(shot, (shot.width * scale).toInt(), (shot.height * scale).toInt(), true) else shot
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return out.toByteArray()
    }

    /** A live finger: {"a":"down"|"move"|"up","x":0–1,"y":0–1} of the picture (through the video's bars, see ScreenMap). */
    private fun finger(context: Context, hands: RemoteHands, t: JSONObject, enc: Encoder?) {
        val (w, h) = realSize(context)
        val a = t.optString("a")
        val (x, y) = ScreenMap.toScreen(t.optDouble("x", 0.5), t.optDouble("y", 0.5), w, h, enc?.width ?: 0, enc?.height ?: 0, clamp = a != "down") ?: return
        when (a) {
            "down" -> hands.touchDown(x, y)
            "move" -> hands.touchMove(x, y)
            "up" -> hands.touchUp(x, y)
        }
    }

    /** The viewer's buttons: taps, keys (Lock too), text, paste, "Make it smooth" and "Unlock". */
    private fun command(context: Context, hands: RemoteHands, cmd: JSONObject, enc: Encoder?): Boolean {
        val (w, h) = realSize(context)
        return when (val kind = cmd.optString("kind")) {
            "tap", "long", "swipe" -> {
                val from = ScreenMap.toScreen(cmd.optDouble("x", 0.5), cmd.optDouble("y", 0.5), w, h, enc?.width ?: 0, enc?.height ?: 0) ?: return false
                val to = ScreenMap.toScreen(cmd.optDouble("x2", 0.5), cmd.optDouble("y2", 0.5), w, h, enc?.width ?: 0, enc?.height ?: 0, clamp = true)!!
                hands.gesture(kind, from.first, from.second, to.first, to.second, cmd.optLong("ms", 300))
            }
            "key" -> hands.globalAction(cmd.optString("name"))
            "text" -> hands.typeText(cmd.optString("text"), cmd.optBoolean("enter"))
            "paste" -> {
                val text = cmd.optString("text")
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("XARVIS Remote", text))
                hands.typeText(text, false)
            }
            // Live video: Android's "Start now" prompt, shown here — Rex taps it on the picture from the other phone.
            "smooth" -> {
                runCatching {
                    context.startActivity(Intent(context, ConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
            }
            "unlock" -> unlock(context, hands)
            else -> false
        }
    }

    /** "🔓 Unlock": screen on, then a swipe up to the pattern / PIN pad, which Rex draws from the other phone. */
    private fun unlock(context: Context, hands: RemoteHands): Boolean {
        synchronized(this) {
            runCatching { wake?.takeIf { it.isHeld }?.release() }
            wake = null
        }
        wakeScreen(context)
        ui.postDelayed({
            if (context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) {
                val (w, h) = realSize(context)
                hands.gesture("swipe", w / 2f, h * 0.80f, w / 2f, h * 0.30f, 300)
            }
        }, 800)
        return true
    }

    private fun lockedOrAsleep(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive == false ||
            context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    /** Screen on (if it was off) and kept on while someone is connected. */
    @Suppress("DEPRECATION") // SCREEN_BRIGHT + ACQUIRE_CAUSES_WAKEUP still work, and are how to wake it from here
    private fun wakeScreen(context: Context) = synchronized(this) {
        val power = context.getSystemService(PowerManager::class.java) ?: return@synchronized
        if (wake?.isHeld == true) {
            // Still held, but the screen went off anyway (🔒 Lock, then a new connection): only a new lock wakes it
            // (Rex, 12 Nov 2026: locked, ended, came back to a dark screen the viewer called "frozen").
            if (power.isInteractive) return@synchronized
            runCatching { wake?.release() }
            wake = null
        }
        wake = runCatching {
            power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "xarvis:remote")
                .apply { acquire(WAKE_MAX_MS) }
        }.getOrNull()
    }

    // ---- the screen as video ----

    private class Encoder(val width: Int, val height: Int, val codec: MediaCodec, val surface: android.view.Surface) {
        @Volatile var running = true
        @Volatile var config: ByteArray? = null

        fun pump(out: (Int, ByteArray) -> Unit) {
            val info = MediaCodec.BufferInfo()
            while (running) {
                val i = try {
                    codec.dequeueOutputBuffer(info, 20_000)
                } catch (e: IllegalStateException) {
                    break
                }
                if (i < 0) continue
                val buf = codec.getOutputBuffer(i)
                if (buf == null || info.size <= 0) {
                    codec.releaseOutputBuffer(i, false)
                    continue
                }
                val bytes = ByteArray(info.size)
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                buf.get(bytes)
                codec.releaseOutputBuffer(i, false)
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (isConfig) config = bytes
                out(if (isConfig) StreamMsg.VIDEO_CONFIG else StreamMsg.VIDEO_FRAME, bytes)
            }
        }

        fun setBitrate(bps: Int) {
            runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) }) }
        }

        fun keyFrame() {
            runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
        }
    }

    private fun startEncoder(context: Context, p: MediaProjection, w: Int, h: Int, maxSide: Int, bitrate: Int): Encoder? {
        val scale = minOf(1f, maxSide.toFloat() / maxOf(w, h))
        val vw = ((w * scale).toInt() / 16).coerceAtLeast(1) * 16
        val vh = ((h * scale).toInt() / 16).coerceAtLeast(1) * 16
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, vw, vh).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            // A full picture every 10 s (the viewer asks for one when it starts or falls behind).
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
            // A still screen makes no new frames: repeat the last one now and then so the stream keeps flowing.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 200_000L)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        val codec = runCatching { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC) }.getOrNull() ?: return null
        return try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()
            val dpi = context.resources.displayMetrics.densityDpi
            val d = display
            if (d == null) {
                display = p.createVirtualDisplay("xarvis-remote", vw, vh, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
                    ?: throw IllegalStateException("no screen mirror")
            } else {
                d.resize(vw, vh, dpi)
                d.surface = surface
            }
            Encoder(vw, vh, codec, surface)
        } catch (e: Exception) {
            runCatching { codec.release() }
            null
        }
    }

    private fun stopEncoder(enc: Encoder) {
        if (!enc.running) return
        enc.running = false
        runCatching { display?.let { if (it.surface === enc.surface) it.surface = null } }
        runCatching { enc.codec.stop() }
        runCatching { enc.codec.release() }
    }

    @Suppress("DEPRECATION")
    fun realSize(context: Context): Pair<Int, Int> {
        val dm = DisplayMetrics()
        context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(dm)
        return if (dm.widthPixels > 0 && dm.heightPixels > 0) dm.widthPixels to dm.heightPixels
        else context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
    }

    private const val STOP_BLOCK_MS = 60_000L
    private const val WAKE_MAX_MS = 4 * 60 * 60_000L
    private const val JPEG_QUALITY = 60
    private const val PICTURE_SIDE = 1100
    private const val RELAY_MAX_SIDE = 720
    private const val RELAY_KBPS = 500
    private const val RELAY_PICTURE_SIDE = 640
    private const val RELAY_PICTURE_GAP_MS = 300L
    private const val RELAY_SAME_PICTURE_MS = 5_000L
}
