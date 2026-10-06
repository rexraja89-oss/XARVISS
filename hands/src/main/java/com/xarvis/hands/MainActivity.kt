package com.xarvis.hands

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Hands screen, built in code (no layout files / Compose). It shows the live service diagnostic,
 * a self-tap test (proof Hands can perform a gesture), a "Call in imo" control (the real goal), and
 * a live log of everything Hands does. Everything here only runs when Rex taps a button and watches.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var events: TextView
    private lateinit var last: TextView
    private lateinit var tapTarget: Button
    private lateinit var log: TextView
    private var selfTaps = 0

    private val ui = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val refresh = object : Runnable {
        override fun run() { render(); ui.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt(); val gap = (10 * d).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun header(t: String) = TextView(this).apply {
            text = t; textSize = 13f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0B6E8C")); setPadding(0, pad, 0, gap)
        }

        col.addView(TextView(this).apply {
            text = "XARVIS Hands"; textSize = 24f; setTextColor(Color.parseColor("#0B6E8C"))
        })

        // ---- live service diagnostic ----
        status = TextView(this).apply { textSize = 16f; setPadding(0, gap, 0, 0) }
        events = TextView(this).apply { textSize = 16f }
        last = TextView(this).apply { textSize = 12f; setTextColor(Color.parseColor("#888888")) }
        col.addView(status); col.addView(events); col.addView(last)
        col.addView(Button(this).apply {
            text = "Open Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        // ---- self-tap test ----
        col.addView(header("TEST 1 — can Hands tap?"))
        tapTarget = Button(this).apply {
            text = "TAP TARGET (tapped 0×)"
            setOnClickListener { selfTaps++; (it as Button).text = "TAP TARGET (tapped ${selfTaps}×)" }
        }
        col.addView(tapTarget)
        col.addView(Button(this).apply {
            text = "Run self-tap (Hands taps the target)"
            setOnClickListener { runSelfTap() }
        })

        // ---- call in imo / WhatsApp ----
        col.addView(header("TEST 2 — call a contact in an app"))
        val nameBox = EditText(this).apply { setText("baarish"); hint = "contact name" }
        col.addView(nameBox)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "Call in imo"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { startCall("imo", nameBox.text.toString(), video = false) }
        })
        row.addView(Button(this).apply {
            text = "Call in WhatsApp"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { startCall("whatsapp", nameBox.text.toString(), video = false) }
        })
        col.addView(row)
        col.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener { HandsService.instance?.cancelTask() }
        })

        // ---- live log ----
        col.addView(header("LOG (what Hands is doing)"))
        log = TextView(this).apply {
            textSize = 12f; setTypeface(Typeface.MONOSPACE)
            setTextColor(Color.parseColor("#333333")); setBackgroundColor(Color.parseColor("#F0F0F0"))
            setPadding(gap, gap, gap, gap)
        }
        col.addView(log)

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() { super.onResume(); ui.post(refresh) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(refresh) }

    private fun ensureOn(): Boolean {
        if (HandsService.isRunning()) return true
        Toast.makeText(this, "Turn XARVIS Hands on in Accessibility first.", Toast.LENGTH_LONG).show()
        return false
    }

    private fun runSelfTap() {
        if (!ensureOn()) return
        val loc = IntArray(2); tapTarget.getLocationOnScreen(loc)
        val x = loc[0] + tapTarget.width / 2f; val y = loc[1] + tapTarget.height / 2f
        HandsService.log("Self-tap in 2s — watch the target.")
        ui.postDelayed({ HandsService.instance?.dispatchTap(x, y) }, 2000)
    }

    private fun startCall(app: String, name: String, video: Boolean) {
        if (!ensureOn()) return
        HandsService.instance?.startCallInApp(app, name, video)
    }

    private fun render() {
        val on = HandsService.isRunning()
        status.text = if (on) "✓ Service connected (it started)" else "✗ Service not connected"
        status.setTextColor(if (on) Color.parseColor("#1B8A3A") else Color.parseColor("#B00020"))
        events.text = "Events received: ${HandsService.eventCount}"
        last.text = if (HandsService.lastEventTime > 0)
            "Last: ${HandsService.lastEventPackage} · ${HandsService.lastEventType} · " +
                timeFmt.format(Date(HandsService.lastEventTime))
        else "Last: (no events yet)"

        log.text = synchronized(HandsService.logLines) {
            if (HandsService.logLines.isEmpty()) "(nothing yet)"
            else HandsService.logLines.takeLast(30).joinToString("\n")
        }
    }
}
