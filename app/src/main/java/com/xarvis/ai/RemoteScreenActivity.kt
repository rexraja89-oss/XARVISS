package com.xarvis.ai

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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

/**
 * Remote control of the linked phone, like TeamViewer: its screen, live (a few pictures a second over the
 * encrypted link), and Rex's touches on it become taps and swipes there (a quick touch = tap, a held touch
 * = long press, a drag = swipe). Buttons for Back / Home / Apps / notifications / typing. The other phone
 * shows a red "… is controlling this phone · STOP" banner meanwhile (see hands/ScreenHost).
 */
class RemoteScreenActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var screen: ImageView
    private lateinit var status: TextView
    private var peer: Peer? = null
    private var shown: Bitmap? = null
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
            text = "Connecting…"; textSize = 14f; setTextColor(Color.WHITE); setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
        }
        screen = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            setOnTouchListener { _, e -> onTouch(e) }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101418"))
            addView(status)
            addView(screen, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(row(button("◀ Back") { key("back") }, button("⬤ Home") { key("home") }, button("▢ Apps") { key("recents") }, button("🔔") { key("notifications") }))
            addView(row(button("⌨ Type") { type() }, button("⚙ Quick") { key("quick") }, button("🔒 Lock") { key("lock") }, button("✖ End") { finish() }))
        }
        setContentView(col)
        scope.launch { stream() }
    }

    /** Ask for the screen over and over, and show each picture. */
    private suspend fun stream() {
        val wanted = intent.getStringExtra(EXTRA_DEVICE)
        val p = withContext(Dispatchers.IO) {
            wanted?.let { link.findPeer(it) } ?: runCatching { link.reachable().firstOrNull()?.first }.getOrNull()
        }
        if (p == null) {
            status.text = "⚠ I can't reach your linked phone. Make sure XARVIS is running on it with Tailscale on."
            return
        }
        peer = p
        status.text = "Connecting to ${p.name}…"
        var fails = 0
        var frames = 0
        var since = System.currentTimeMillis()
        while (scope.isActive) {
            try {
                val f = withContext(Dispatchers.IO) { link.remoteScreen(p, MAX_SIDE) }
                val bmp = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(f.jpeg, 0, f.jpeg.size) }
                if (bmp != null) {
                    screen.setImageBitmap(bmp)
                    shown = bmp
                }
                fails = 0
                frames++
                val secs = (System.currentTimeMillis() - since) / 1000.0
                if (secs >= 3) {
                    status.text = "🖥 ${p.name} · live · %.1f pictures/s · touch to tap, drag to swipe".format(frames / secs)
                    frames = 0
                    since = System.currentTimeMillis()
                } else if (status.text.startsWith("Connecting") || status.text.startsWith("⚠")) {
                    status.text = "🖥 ${p.name} · live · touch to tap, drag to swipe"
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                fails++
                val why = e.message ?: e.javaClass.simpleName
                status.text = "⚠ ${p.name}: $why"
                // Switched off / STOP / Hands off won't fix themselves: stop asking and leave the reason up.
                if (fails >= 3 || STOPPERS.any { why.contains(it, ignoreCase = true) }) return
                delay(1_500)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun onTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; downAt = System.currentTimeMillis()
            }
            MotionEvent.ACTION_UP -> {
                val start = toScreen(downX, downY) ?: return true
                val end = toScreen(e.x, e.y) ?: start
                val held = System.currentTimeMillis() - downAt
                val moved = Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) > 24 * resources.displayMetrics.density
                val kind = when {
                    moved -> "swipe"
                    held > 600 -> "long"
                    else -> "tap"
                }
                send(
                    JSONObject().put("kind", kind).put("x", start.first).put("y", start.second)
                        .put("x2", end.first).put("y2", end.second).put("ms", held.coerceIn(120, 2000)),
                )
            }
        }
        return true
    }

    /** A point on the picture → 0–1 across and down the other phone's screen (null outside the picture). */
    private fun toScreen(x: Float, y: Float): Pair<Double, Double>? {
        val b = shown ?: return null
        val scale = minOf(screen.width.toFloat() / b.width, screen.height.toFloat() / b.height)
        val left = (screen.width - b.width * scale) / 2
        val top = (screen.height - b.height * scale) / 2
        val nx = (x - left) / (b.width * scale)
        val ny = (y - top) / (b.height * scale)
        if (nx < 0 || nx > 1 || ny < 0 || ny > 1) return null
        return nx.toDouble() to ny.toDouble()
    }

    private fun key(name: String) = send(JSONObject().put("kind", "key").put("name", name))

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
                send(JSONObject().put("kind", "text").put("text", box.text.toString()).put("enter", enter.isChecked))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun send(cmd: JSONObject) {
        val p = peer ?: return
        scope.launch {
            val ok = runCatching { withContext(Dispatchers.IO) { link.remoteInput(p, cmd) } }
            ok.exceptionOrNull()?.let { if (it !is CancellationException) status.text = "⚠ ${it.message ?: it.javaClass.simpleName}" }
            if (ok.getOrNull() == false && cmd.optString("kind") == "text") {
                status.text = "⚠ No text box is selected on ${p.name}: tap one on its screen first, then Type."
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        // Tell the other phone to end now (its red banner goes away) rather than after its 15 s timeout.
        peer?.let { p -> CoroutineScope(Dispatchers.IO).launch { runCatching { link.remoteScreenStop(p) } } }
        super.onDestroy()
    }

    companion object {
        /** The linked phone to control, by name (optional: else the first one that answers). */
        const val EXTRA_DEVICE = "device"
        /** Long side of each picture: sharp enough to read, small enough for ~3 a second over Tailscale. */
        private const val MAX_SIDE = 1100
        private val STOPPERS = listOf("switched off", "STOP was pressed", "can't be controlled")
    }
}
