package com.xarvis.helper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import com.xarvis.ai.hands.BitrateControl
import com.xarvis.ai.hands.ScreenMap
import com.xarvis.ai.hands.StreamMsg
import com.xarvis.ai.net.DeviceLink
import com.xarvis.ai.net.LinkException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * One help session, from "🙋 Ask for help" to STOP: she allows screen sharing (Android's own prompt, every
 * session), the helping phone gets a notification, connects, sees the screen as live video and — if "✋ Allow
 * tapping" is on — taps and types. It ends when she presses STOP, when Android stops the sharing, or after
 * [IDLE_END_MS] with nobody connected.
 */
object HelperSession {
    enum class State { IDLE, WAITING, HELPING }

    data class Status(val state: State, val by: String? = null, val note: String = "")

    private val _status = MutableStateFlow(Status(State.IDLE))
    val status: StateFlow<Status> = _status

    /** A helping phone that tried to connect before she allowed it (the screen then asks her). */
    val askedBy = MutableStateFlow<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var projection: MediaProjection? = null
    @Volatile private var display: VirtualDisplay? = null
    @Volatile private var live: DeviceLink.Channel? = null
    @Volatile private var lastViewer = 0L

    val active: Boolean get() = projection != null

    /** She allowed screen sharing ([p]): wait for the helping phone(s), and tell them. */
    fun begin(context: Context, p: MediaProjection) {
        projection?.takeIf { it !== p }?.let { runCatching { it.stop() } }
        projection = p
        // Android 14+ wants this before the screen is mirrored.
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (projection === p) end(context, "Screen sharing stopped.")
            }
        }, ui)
        lastViewer = System.currentTimeMillis()
        askedBy.value = null
        _status.value = Status(State.WAITING, note = "Telling them…")
        ui.removeCallbacks(idleCheck)
        idleCheck = Runnable { checkIdle(context) }.also { ui.postDelayed(it, 30_000) }
        askOwners(context)
    }

    /** Sends "help" to every phone that may help this one (usually one: Rex's). */
    private fun askOwners(context: Context) {
        val link = HelperApp.link(context)
        scope.launch {
            val owners = link.pairedPeers()
            val results = owners.map { p ->
                p.name to runCatching { link.askForHelp(p) }.exceptionOrNull()?.message
            }
            val note = when {
                owners.isEmpty() -> "Not linked yet."
                results.any { it.second == null } -> results.filter { it.second == null }.joinToString { it.first } + " got your request. Please wait…"
                else -> "Couldn't reach them: " + results.joinToString { "${it.first}: ${it.second}" } + ". They can still connect when they see this."
            }
            if (_status.value.state == State.WAITING) _status.value = _status.value.copy(note = note)
        }
    }

    /** Ends the help: the helper is told why ([stopped]: she pressed STOP), and the screen isn't shared any more. */
    fun end(context: Context, why: String = "", stopped: Boolean = false) {
        live?.let { ch ->
            if (stopped) runCatching { ch.fail("They pressed STOP on their phone.") }
            runCatching { ch.close() }
        }
        live = null
        ui.removeCallbacks(idleCheck)
        runCatching { display?.release() }
        display = null
        projection?.let { p ->
            projection = null
            runCatching { p.stop() }
        }
        HelperHands.instance?.helpingBar(null)
        _status.value = Status(State.IDLE, note = why)
        runCatching { context.stopService(Intent(context, HelperService::class.java)) }
    }

    private var idleCheck = Runnable {}

    private fun checkIdle(context: Context) {
        if (projection == null) return
        if (live == null && System.currentTimeMillis() - lastViewer > IDLE_END_MS) {
            end(context, "Help ended: nobody connected for ${IDLE_END_MS / 60_000} minutes.")
            return
        }
        ui.postDelayed(idleCheck, 30_000)
    }

    /**
     * The helping phone [from] connects (DeviceLink op "stream"): her screen as live H.264 video, its fingers, keys
     * and text back. Refused (and she's asked) when she hasn't allowed sharing.
     */
    suspend fun stream(context: Context, from: String, req: JSONObject, ch: DeviceLink.Channel, accept: (JSONObject) -> Unit) {
        val p = projection
        if (p == null) {
            askedBy.value = from
            HelperService.askToAllow(context, from)
            throw LinkException("they haven't allowed it yet. XARVIS Helper is asking them now: when they tap Allow, you'll get a notification.")
        }
        live?.close()
        live = ch
        lastViewer = System.currentTimeMillis()
        // Through the relay (Rex's free 1 GB a month): smaller video, like XARVIS's own phones.
        val viaRelay = req.optString("route") == "relay"
        val maxSide = req.optInt("max", 1440).coerceIn(480, 1920).let { if (viaRelay) minOf(it, 720) else it }
        val kbps = req.optInt("kbps", 4000).coerceIn(300, 8000).let { if (viaRelay) minOf(it, 500) else it }
        val (w, h) = realSize(context)
        // Away from home the video starts lighter and follows what the line carries.
        val rate = BitrateControl(max = kbps * 1000, start = BitrateControl.startFor(req.optString("route"), kbps * 1000))
        val enc = withContext(Dispatchers.Main) { startEncoder(context, p, w, h, maxSide, rate.current) }
            ?: throw LinkException("their phone couldn't start sharing its screen")
        accept(
            JSONObject().put("ok", true).put("mode", "video").put("w", w).put("h", h).put("vw", enc.width).put("vh", enc.height)
                .put("smoothable", false).put("control", HelperHands.instance != null),
        )
        _status.value = Status(State.HELPING, by = from)
        HelperHands.instance?.helpingBar(from)
        HelperService.update(context, from)
        try {
            coroutineScope {
                // The helper's fingers, keys and pings.
                launch(Dispatchers.IO) {
                    try {
                        while (true) {
                            val (type, payload) = ch.receive()
                            lastViewer = System.currentTimeMillis()
                            when (type) {
                                StreamMsg.TOUCH -> finger(context, JSONObject(String(payload)), enc)
                                StreamMsg.INPUT -> command(context, ch, JSONObject(String(payload)), enc)
                                StreamMsg.PING -> ch.send(StreamMsg.PONG, payload)
                                StreamMsg.KEY_FRAME -> {
                                    enc.config?.let { ch.send(StreamMsg.VIDEO_CONFIG, it) }
                                    enc.keyFrame()
                                }
                                StreamMsg.BYE -> break
                            }
                        }
                    } finally {
                        stopEncoder(enc)
                        ch.close()
                    }
                }
                // Her screen.
                launch(Dispatchers.IO) {
                    try {
                        enc.pump { type, bytes ->
                            val t0 = System.nanoTime()
                            ch.send(type, bytes)
                            val now = System.nanoTime()
                            rate.sent(now - t0, now)?.let { enc.setBitrate(it) }
                            lastViewer = System.currentTimeMillis()
                        }
                    } finally {
                        ch.close()
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // The helper left or the network dropped.
        } finally {
            stopEncoder(enc)
            ch.close()
            lastViewer = System.currentTimeMillis()
            if (live === ch) {
                live = null
                if (projection != null) {
                    HelperHands.instance?.helpingBar(null)
                    _status.value = Status(State.WAITING, note = "$from left. They can come back while this is on — or tap STOP.")
                    HelperService.update(context, null)
                }
            }
        }
    }

    /** A live finger from the helper: {"a":"down"|"move"|"up","x":0–1,"y":0–1} of the video (see ScreenMap). */
    private fun finger(context: Context, t: JSONObject, enc: Encoder) {
        val hands = HelperHands.instance ?: return
        val (w, h) = realSize(context) // now: she may have turned the phone
        val a = t.optString("a")
        val (x, y) = ScreenMap.toScreen(t.optDouble("x", 0.5), t.optDouble("y", 0.5), w, h, enc.width, enc.height, clamp = a != "down") ?: return
        when (a) {
            "down" -> hands.touchDown(x, y)
            "move" -> hands.touchMove(x, y)
            "up" -> hands.touchUp(x, y)
        }
    }

    /** Taps, keys, text, paste and copy from the helper's buttons. Lock/unlock aren't offered on a helped phone. */
    private fun command(context: Context, ch: DeviceLink.Channel, cmd: JSONObject, enc: Encoder) {
        val hands = HelperHands.instance ?: return
        ui.post {
            runCatching {
                when (val kind = cmd.optString("kind")) {
                    "tap", "long", "swipe" -> {
                        val (w, h) = realSize(context)
                        val from = ScreenMap.toScreen(cmd.optDouble("x", 0.5), cmd.optDouble("y", 0.5), w, h, enc.width, enc.height)
                        val to = ScreenMap.toScreen(cmd.optDouble("x2", 0.5), cmd.optDouble("y2", 0.5), w, h, enc.width, enc.height, clamp = true)!!
                        if (from != null) hands.gesture(kind, from.first, from.second, to.first, to.second, cmd.optLong("ms", 300))
                    }
                    "key" -> hands.globalAction(cmd.optString("name"))
                    "text" -> hands.typeText(cmd.optString("text"), cmd.optBoolean("enter"))
                    "paste" -> {
                        val text = cmd.optString("text")
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("From your helper", text))
                        hands.typeText(text, false)
                    }
                    "copy" -> {
                        val text = hands.selectedText()
                        scope.launch { runCatching { ch.send(StreamMsg.INFO, JSONObject().put("clip", text ?: JSONObject.NULL).toString().toByteArray()) } }
                    }
                }
            }
        }
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

    /**
     * A new encoder for this connection. The screen mirror (VirtualDisplay) is made once per session and only
     * pointed at the new encoder after that: Android 14+ allows one mirror per screen-sharing consent.
     */
    private fun startEncoder(context: Context, p: MediaProjection, w: Int, h: Int, maxSide: Int, bitrate: Int): Encoder? {
        val scale = minOf(1f, maxSide.toFloat() / maxOf(w, h))
        val vw = ((w * scale).toInt() / 16).coerceAtLeast(1) * 16
        val vh = ((h * scale).toInt() / 16).coerceAtLeast(1) * 16
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, vw, vh).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            // A full picture every 10 s, not 3: on 4G each one is a burst that stalls the stream (the viewer asks
            // for one itself when it starts or falls behind).
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 200_000L)
        }
        val codec = runCatching { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC) }.getOrNull() ?: return null
        return try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()
            val dpi = context.resources.displayMetrics.densityDpi
            val d = display
            if (d == null) {
                display = p.createVirtualDisplay("xarvis-helper", vw, vh, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
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
        // The mirror waits for the next connection — unless a newer connection already took it over.
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

    /** With nobody connected for this long, the help ends by itself (she can ask again any time). */
    const val IDLE_END_MS = 10 * 60_000L
}
