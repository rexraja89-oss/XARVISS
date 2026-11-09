package com.xarvis.ai

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.xarvis.ai.hands.StreamMsg
import com.xarvis.ai.net.DeviceLink
import com.xarvis.ai.net.Peer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * Remote control of the linked phone, like TeamViewer. Smooth mode: the other phone's screen arrives as
 * live H.264 video over one encrypted connection (DeviceLink.Channel) and is decoded by this phone's
 * hardware decoder in low-latency mode; the finger goes back live (down / moves / up), so lists scroll as
 * you drag. Until Rex allows screen sharing on the other phone it shows screenshots (~3 a second) on the
 * same connection, with "⚡ Make it smooth". An older XARVIS on the other phone gets the first version's
 * picture-by-picture requests. The top line shows the real delay (a ping there and back).
 */
class RemoteScreenActivity : Activity(), SurfaceHolder.Callback {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ui = Handler(Looper.getMainLooper())
    private val sender = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var stage: FrameLayout
    private lateinit var video: SurfaceView
    private lateinit var picture: ImageView
    private lateinit var smoothButton: Button
    private lateinit var lockButton: Button
    @Volatile private var remoteLocked = false

    private var peer: Peer? = null
    @Volatile private var channel: DeviceLink.Channel? = null
    @Volatile private var mode = "" // "video", "jpeg", or "poll" (older XARVIS on the other phone)
    @Volatile private var leaving = false
    private var screenW = 0
    private var screenH = 0
    private var videoW = 0
    private var videoH = 0
    @Volatile private var decoder: MediaCodec? = null
    @Volatile private var surfaceReady = false
    @Volatile private var framesShown = 0
    @Volatile private var rttMs = -1L
    private var shown: Bitmap? = null
    private var tracking = false
    private var lastMoveAt = 0L
    // Older-XARVIS mode: the whole touch is sent when the finger lifts.
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

    private val link get() = (application as XarvisApp).core.deviceLink

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val d = resources.displayMetrics.density
        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; textSize = 13f; setOnClickListener { onClick() }
        }
        fun row(vararg views: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            views.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        }
        status = TextView(this).apply {
            text = "Connecting…"; textSize = 13f; setTextColor(Color.WHITE)
            setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
        }
        video = SurfaceView(this).apply { holder.addCallback(this@RemoteScreenActivity) }
        picture = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        stage = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(picture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(video, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
            setOnTouchListener { _, e -> onTouch(e) }
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitVideo() }
        }
        smoothButton = button("⚡ Make it smooth (live video)") { makeSmooth() }.apply { visibility = View.GONE }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101418"))
            addView(status)
            addView(smoothButton)
            addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(row(button("◀ Back") { key("back") }, button("⬤ Home") { key("home") }, button("▢ Apps") { key("recents") }, button("🔔") { key("notifications") }))
            lockButton = button("🔒 Lock") { if (remoteLocked) unlock() else confirmLock() }
            addView(row(button("⌨ Type") { type() }, button("⚙ Quick") { key("quick") }, lockButton, button("✖ End") { finish() }))
            addView(row(button("📋 Paste to it") { pasteToIt() }, button("📋 Copy from it") { copyFromIt() }, button("📁 Send file") { pickFile() }))
        }
        setContentView(col)
        scope.launch { run() }
        ui.postDelayed(ticker, 1000)
    }

    // ---- connecting ----

    private suspend fun run() {
        val wanted = intent.getStringExtra(EXTRA_DEVICE)
        val wantedId = intent.getStringExtra(EXTRA_PEER_ID)
        val p = withContext(Dispatchers.IO) {
            wantedId?.let { link.peerById(it) }
                ?: wanted?.let { link.findPeer(it) } ?: runCatching { link.reachable().firstOrNull()?.first }.getOrNull()
        }
        if (p == null) {
            status.text = "⚠ I can't reach your linked phone. Make sure XARVIS is running on it with Tailscale on."
            return
        }
        peer = p
        // Reconnect when the other phone switches to video (Rex just allowed smooth mode there).
        while (scope.isActive && !leaving) {
            if (session(p) != "video") break
            status.text = "⚡ Switching to live video…"
        }
    }

    /** One connection to [p]; returns "video" when the other phone asks to reconnect for smooth video. */
    private suspend fun session(p: Peer): String? {
        status.text = "Connecting to ${p.name}…"
        val opened = runCatching {
            withContext(Dispatchers.IO) {
                link.openChannel(p, "stream", JSONObject().put("max", MAX_SIDE).put("kbps", KBPS))
            }
        }
        val (head, ch) = opened.getOrElse { e ->
            if (e is CancellationException) throw e
            val why = e.message ?: e.javaClass.simpleName
            if (why.contains("unknown request", ignoreCase = true)) {
                pollLoop(p) // the other phone has an older XARVIS
            } else {
                status.text = "⚠ ${p.name}: $why"
            }
            return null
        }
        channel = ch
        screenW = head.optInt("w")
        screenH = head.optInt("h")
        mode = head.optString("mode")
        if (mode == "video") {
            videoW = head.optInt("vw")
            videoH = head.optInt("vh")
            picture.visibility = View.GONE
            smoothButton.visibility = View.GONE
            fitVideo()
            startDecoder()
        } else {
            video.layoutParams = FrameLayout.LayoutParams(1, 1, Gravity.CENTER)
            picture.visibility = View.VISIBLE
            // The benco can't do live video (no screen sharing in its build): no "Make it smooth" there.
            smoothButton.visibility = if (head.optBoolean("smoothable", true)) View.VISIBLE else View.GONE
        }
        status.text = "🖥 ${p.name} · connected"
        // XARVIS Helper without its Accessibility switch: Rex can see but not tap (the ticker keeps saying so).
        viewOnly = !head.optBoolean("control", true)
        val next = withContext(Dispatchers.IO) { readLoop(p, ch) }
        channel = null
        ch.close()
        stopDecoder()
        return next
    }

    /** Everything the other phone sends, until the connection closes. Blocks: runs on an IO thread. */
    private fun readLoop(p: Peer, ch: DeviceLink.Channel): String? {
        try {
            while (true) {
                val (type, payload) = ch.receive()
                when (type) {
                    StreamMsg.VIDEO_CONFIG -> feed(payload, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                    StreamMsg.VIDEO_FRAME -> feed(payload, 0)
                    StreamMsg.JPEG -> {
                        val bmp = BitmapFactory.decodeByteArray(payload, 0, payload.size) ?: continue
                        framesShown++
                        runOnUiThread {
                            picture.setImageBitmap(bmp)
                            shown = bmp
                        }
                    }
                    StreamMsg.PONG -> if (payload.size == 8) rttMs = (System.nanoTime() - ByteBuffer.wrap(payload).long) / 1_000_000
                    StreamMsg.INFO -> {
                        val info = JSONObject(String(payload))
                        if (info.optString("switch") == "video") return "video"
                        if (info.has("clip")) runOnUiThread { gotClip(if (info.isNull("clip")) null else info.optString("clip")) }
                        if (info.has("locked")) info.optBoolean("locked").let { locked -> runOnUiThread { setLocked(locked) } }
                    }
                }
            }
        } catch (e: Exception) {
            if (!leaving) runOnUiThread { status.text = "⚠ ${p.name}: ${e.message ?: "disconnected"}" }
            return null
        }
    }

    // ---- video ----

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        startDecoder()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        stopDecoder()
    }

    private fun startDecoder() {
        if (mode != "video" || !surfaceReady || decoder != null || videoW == 0 || videoH == 0) return
        val d = createDecoder(video.holder.surface)
        if (d == null) {
            status.text = "⚠ This phone couldn't start its video decoder."
            return
        }
        decoder = d
        Thread({ drain(d) }, "xarvis-remote-decode").start()
        send(StreamMsg.KEY_FRAME) // start from a full picture
    }

    private fun createDecoder(out: Surface): MediaCodec? {
        fun make(lowLatency: Boolean): MediaCodec? = runCatching {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoW, videoH)
            if (lowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                codec.configure(format, out, null, 0)
                codec.start()
            } catch (e: Exception) {
                codec.release()
                throw e
            }
            codec
        }.getOrNull()
        return make(true) ?: make(false)
    }

    /** Network thread: hand one encoded frame to the decoder (dropped if the decoder isn't ready). */
    private fun feed(bytes: ByteArray, flags: Int) {
        val d = decoder ?: return
        try {
            val i = d.dequeueInputBuffer(30_000)
            if (i < 0) {
                send(StreamMsg.KEY_FRAME) // fell behind: start again from a full picture
                return
            }
            val buf = d.getInputBuffer(i) ?: return
            buf.clear()
            if (bytes.size > buf.capacity()) {
                d.queueInputBuffer(i, 0, 0, 0, 0)
                send(StreamMsg.KEY_FRAME)
                return
            }
            buf.put(bytes)
            d.queueInputBuffer(i, 0, bytes.size, System.nanoTime() / 1000, flags)
        } catch (e: IllegalStateException) {
            // The decoder is being stopped (screen turned off / leaving).
        }
    }

    /** Show each decoded picture at once — no waiting for its time, which is what keeps the delay low. */
    private fun drain(d: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (decoder === d) {
            val i = try {
                d.dequeueOutputBuffer(info, 20_000)
            } catch (e: IllegalStateException) {
                break
            }
            if (i >= 0) {
                try {
                    d.releaseOutputBuffer(i, true)
                } catch (e: IllegalStateException) {
                    break
                }
                framesShown++
            }
        }
    }

    private fun stopDecoder() {
        val d = decoder ?: return
        decoder = null
        runCatching { d.stop() }
        runCatching { d.release() }
    }

    /** Size the video to the other phone's screen shape, centred. */
    private fun fitVideo() {
        if (mode != "video" || screenW == 0 || screenH == 0 || stage.width == 0 || stage.height == 0) return
        val scale = minOf(stage.width.toFloat() / screenW, stage.height.toFloat() / screenH)
        val w = (screenW * scale).toInt()
        val h = (screenH * scale).toInt()
        val lp = video.layoutParams
        if (lp.width != w || lp.height != h) video.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
    }

    // ---- touches and buttons ----

    /** Where the other phone's screen is drawn on this one. */
    private fun content(): RectF? {
        if (mode == "video") {
            return if (video.width > 1) RectF(video.left.toFloat(), video.top.toFloat(), video.right.toFloat(), video.bottom.toFloat()) else null
        }
        val b = shown ?: return null
        val scale = minOf(stage.width.toFloat() / b.width, stage.height.toFloat() / b.height)
        val left = (stage.width - b.width * scale) / 2
        val top = (stage.height - b.height * scale) / 2
        return RectF(left, top, left + b.width * scale, top + b.height * scale)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun onTouch(e: MotionEvent): Boolean {
        val r = content() ?: return true
        val nx = ((e.x - r.left) / r.width()).coerceIn(0f, 1f).toDouble()
        val ny = ((e.y - r.top) / r.height()).coerceIn(0f, 1f).toDouble()
        if (mode == "poll") return pollTouch(e, nx, ny)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = r.contains(e.x, e.y)
                if (tracking) finger("down", nx, ny)
            }
            MotionEvent.ACTION_MOVE -> {
                val now = System.currentTimeMillis()
                if (tracking && now - lastMoveAt >= MOVE_EVERY_MS) {
                    lastMoveAt = now
                    finger("move", nx, ny)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (tracking) {
                tracking = false
                finger("up", nx, ny)
            }
        }
        return true
    }

    private fun finger(action: String, x: Double, y: Double) =
        send(StreamMsg.TOUCH, JSONObject().put("a", action).put("x", x).put("y", y).toString().toByteArray())

    private fun key(name: String) = command(JSONObject().put("kind", "key").put("name", name))

    /** Lock asks first: getting back in means drawing the other phone's pattern (or typing its PIN) here. */
    private fun confirmLock() {
        val name = peer?.name ?: "the other phone"
        AlertDialog.Builder(this)
            .setTitle("Lock $name?")
            .setMessage("Its screen turns off. To get back in, tap 🔓 Unlock here and draw its pattern (or type its PIN) on the picture.")
            .setPositiveButton("Lock") { _, _ ->
                key("lock")
                setLocked(true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Wake the other phone and open its pattern / PIN screen, for Rex to draw on the picture. */
    private fun unlock() {
        command(JSONObject().put("kind", "unlock"))
        android.widget.Toast.makeText(
            this, "Waking ${peer?.name ?: "it"}… now draw its pattern (or type its PIN) on the picture.", android.widget.Toast.LENGTH_LONG,
        ).show()
    }

    private fun setLocked(locked: Boolean) {
        remoteLocked = locked
        lockButton.text = if (locked) "🔓 Unlock" else "🔒 Lock"
    }

    private fun makeSmooth() {
        command(JSONObject().put("kind", "smooth"))
        status.text = "On ${peer?.name ?: "the other phone"}'s screen (shown here), tap “Start now”. Live video starts right after."
    }

    private fun type() {
        val box = EditText(this).apply { hint = "Text to type on the other phone" }
        val enter = CheckBox(this).apply { text = "Then press Enter / Send"; isChecked = false }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(box); addView(enter)
        }
        AlertDialog.Builder(this)
            .setTitle("Type on ${peer?.name ?: "the other phone"}")
            .setMessage("Tap a text box on its screen first, so the text goes there.")
            .setView(form)
            .setPositiveButton("Type") { _, _ ->
                command(JSONObject().put("kind", "text").put("text", box.text.toString()).put("enter", enter.isChecked))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** A key / text / gesture: on the stream, or (older XARVIS) as its own request. */
    private fun command(cmd: JSONObject) {
        if (mode != "poll") {
            send(StreamMsg.INPUT, cmd.toString().toByteArray())
            return
        }
        val p = peer ?: return
        scope.launch {
            val done = runCatching { withContext(Dispatchers.IO) { link.remoteInput(p, cmd) } }
            done.exceptionOrNull()?.let { if (it !is CancellationException) status.text = "⚠ ${it.message ?: it.javaClass.simpleName}" }
            if (done.getOrNull() == false && cmd.optString("kind") == "text") {
                status.text = "⚠ No text box is selected on ${p.name}: tap one on its screen first, then Type."
            }
        }
    }

    // ---- clipboard and files ----

    /** This phone's clipboard → the other phone's clipboard and its selected text box. */
    private fun pasteToIt() {
        val clip = getSystemService(android.content.ClipboardManager::class.java)?.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isEmpty()) {
            status.text = "⚠ Your clipboard here is empty: copy something first."
            return
        }
        command(JSONObject().put("kind", "paste").put("text", text))
        status.text = "📋 Pasted on ${peer?.name ?: "it"} (and put on its clipboard)."
    }

    /** The other phone's selected text → this phone's clipboard (arrives as an INFO message). */
    private fun copyFromIt() {
        if (mode == "poll") {
            status.text = "⚠ Update XARVIS on ${peer?.name ?: "the other phone"} to copy from it."
            return
        }
        send(StreamMsg.INPUT, JSONObject().put("kind", "copy").toString().toByteArray())
    }

    private fun gotClip(text: String?) {
        if (text.isNullOrEmpty()) {
            status.text = "⚠ Nothing to copy: tap a text box on its screen (or select some text) first."
            return
        }
        getSystemService(android.content.ClipboardManager::class.java)
            ?.setPrimaryClip(android.content.ClipData.newPlainText("From ${peer?.name ?: "linked phone"}", text))
        status.text = "📋 Copied here: " + text.take(60) + if (text.length > 60) "…" else ""
    }

    private fun pickFile() {
        val pick = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*")
        runCatching { startActivityForResult(pick, PICK_FILE) }
    }

    @Deprecated("A plain Activity's result callback; this screen has no Compose")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != PICK_FILE || resultCode != RESULT_OK || uri == null) return
        val p = peer ?: return
        val name = com.xarvis.ai.files.DocumentReader.displayName(this, uri)
        status.text = "📁 Sending $name to ${p.name}…"
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("couldn't read it")
                    if (bytes.size > ShareToLinkedActivity.MAX_BYTES) error("it's over ${ShareToLinkedActivity.MAX_BYTES / 1_000_000} MB")
                    link.sendFile(p, name, contentResolver.getType(uri) ?: "application/octet-stream", bytes)
                }
            }
            status.text = result.fold(
                { "📁 Sent: $it is in ${p.name}'s Downloads › XARVIS." },
                { "⚠ Couldn't send $name: ${it.message ?: it.javaClass.simpleName}" },
            )
        }
    }

    /** Off the main thread, in order. */
    private fun send(type: Int, payload: ByteArray = ByteArray(0)) {
        val ch = channel ?: return
        runCatching { sender.execute { runCatching { ch.send(type, payload) } } }
    }

    /** How the stream travels: home Wi-Fi, Tailscale, or the relay (away from home, lighter pictures). */
    private fun routeLabel(): String = when (channel?.route) {
        "relay" -> " · 🌐 Relay"
        "direct" -> " · ⚡ Direct"
        "tailscale" -> " · Tailscale"
        "wifi" -> " · 🏠 Wi-Fi"
        else -> ""
    }

    /** XARVIS Helper on the other phone without its Accessibility switch: seeing works, tapping doesn't. */
    @Volatile private var viewOnly = false

    /** Once a second: measure the delay, and show how smooth it is. */
    private val ticker = object : Runnable {
        private var lastFrames = 0
        override fun run() {
            if (leaving) return
            send(StreamMsg.PING, ByteBuffer.allocate(8).putLong(System.nanoTime()).array())
            val fps = framesShown - lastFrames
            lastFrames = framesShown
            val p = peer
            if (p != null && channel != null) {
                val delay = if (rttMs >= 0) " · ${rttMs} ms delay" else ""
                status.text = when (mode) {
                    "video" -> "⚡ ${p.name} · live · $fps fps$delay${routeLabel()}" +
                        if (viewOnly) "\n👀 View only: to let you tap, they use \"✋ Allow tapping\" in XARVIS Helper" else ""
                    "jpeg" -> "🖥 ${p.name} · $fps pictures/s$delay${routeLabel()}" + if (smoothButton.visibility == View.VISIBLE) " · tap ⚡ for live video" else ""
                    else -> status.text
                }
            }
            ui.postDelayed(this, 1000)
        }
    }

    // ---- older XARVIS on the other phone: one picture per request ----

    private suspend fun pollLoop(p: Peer) {
        mode = "poll"
        picture.visibility = View.VISIBLE
        var fails = 0
        while (scope.isActive && !leaving) {
            try {
                val f = withContext(Dispatchers.IO) { link.remoteScreen(p, POLL_MAX_SIDE) }
                val bmp = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(f.jpeg, 0, f.jpeg.size) }
                if (bmp != null) {
                    picture.setImageBitmap(bmp)
                    shown = bmp
                }
                fails = 0
                status.text = "🖥 ${p.name} · update XARVIS on it for live video"
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                fails++
                val why = e.message ?: e.javaClass.simpleName
                status.text = "⚠ ${p.name}: $why"
                if (fails >= 3 || STOPPERS.any { why.contains(it, ignoreCase = true) }) return
                delay(1_500)
            }
        }
    }

    private fun pollTouch(e: MotionEvent, nx: Double, ny: Double): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = nx.toFloat(); downY = ny.toFloat(); downAt = System.currentTimeMillis()
            }
            MotionEvent.ACTION_UP -> {
                val held = System.currentTimeMillis() - downAt
                val moved = Math.hypot(nx - downX, ny - downY) > 0.02
                val kind = if (moved) "swipe" else if (held > 600) "long" else "tap"
                command(
                    JSONObject().put("kind", kind).put("x", downX.toDouble()).put("y", downY.toDouble())
                        .put("x2", nx).put("y2", ny).put("ms", held.coerceIn(120, 2000)),
                )
            }
        }
        return true
    }

    override fun onDestroy() {
        leaving = true
        ui.removeCallbacks(ticker)
        scope.cancel()
        val ch = channel
        channel = null
        // Say goodbye so the other phone ends at once (its red banner goes away).
        runCatching {
            sender.execute {
                runCatching { ch?.send(StreamMsg.BYE) }
                runCatching { ch?.close() }
            }
        }
        sender.shutdown()
        stopDecoder()
        if (mode == "poll") peer?.let { p -> CoroutineScope(Dispatchers.IO).launch { runCatching { link.remoteScreenStop(p) } } }
        super.onDestroy()
    }

    companion object {
        /** The linked phone to control, by name (optional: else the first one that answers). */
        const val EXTRA_DEVICE = "device"
        /** Or by id (a family phone with XARVIS Helper, from ☰ → Help a family phone or its help notification). */
        const val EXTRA_PEER_ID = "peer_id"
        /** Video: long side and bit rate — sharp text, and light enough for Tailscale on mobile data. */
        private const val MAX_SIDE = 1440
        private const val KBPS = 4000
        private const val MOVE_EVERY_MS = 12L
        private const val POLL_MAX_SIDE = 1100
        private const val PICK_FILE = 7401
        private val STOPPERS = listOf("switched off", "STOP was pressed", "can't be controlled")
    }
}
