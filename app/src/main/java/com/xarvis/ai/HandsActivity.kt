package com.xarvis.ai

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.xarvis.ai.hands.HandsBridge

/**
 * ☰ → XARVIS Hands: the built-in Hands, in the same simple style as the old separate Hands app.
 * Shows whether it's switched on (with the steps and a button to switch it on), a quick test
 * ("call <name> in imo / WhatsApp") and the live log. On the benco build Hands isn't built in, so it
 * points to the separate XARVIS Hands app.
 */
class HandsActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var smooth: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { render(); ui.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val gap = (8 * d).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        fun header(t: String) = TextView(this).apply {
            text = t; textSize = 13f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0B6E8C")); setPadding(0, pad, 0, gap)
        }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; textSize = 16f; setOnClickListener { onClick() }
        }

        col.addView(TextView(this).apply { text = "XARVIS Hands"; textSize = 24f; setTextColor(Color.parseColor("#0B6E8C")) })
        col.addView(TextView(this).apply {
            text = "Lets XARVIS tap inside other apps when you ask, like placing a call in imo or WhatsApp. " +
                "It never taps payments, passwords, OTPs or signatures."
            textSize = 14f; setTextColor(Color.parseColor("#666666"))
        })
        status = TextView(this).apply { textSize = 16f; setPadding(0, gap, 0, gap) }
        col.addView(status)

        if (!HandsBridge.builtIn) {
            col.addView(TextView(this).apply {
                text = "On this phone Hands is the separate \"XARVIS Hands\" app. Open it from your app list."
                textSize = 15f
            })
            // Two-way remote control: the S22 may see and control this phone too, through the Hands app.
            col.addView(header("REMOTE CONTROL — LIKE TEAMVIEWER"))
            col.addView(android.widget.CheckBox(this).apply {
                text = "🖥 Let my linked phone see and control this phone"
                textSize = 16f
                isChecked = com.xarvis.ai.hands.ScreenHost.allowed(this@HandsActivity)
                setOnCheckedChangeListener { _, on -> com.xarvis.ai.hands.ScreenHost.setAllowed(this@HandsActivity, on) }
            })
            col.addView(TextView(this).apply {
                text = "Needs the newest XARVIS Hands app, switched on. Its screen must be on when the other phone " +
                    "connects; a red \"… is controlling this phone · STOP\" banner then shows and keeps it on. Pictures, a " +
                    "few a second (live video is the S22's only)."
                textSize = 13f; setTextColor(Color.parseColor("#666666"))
            })
            setContentView(ScrollView(this).apply { addView(col) })
            return
        }

        col.addView(TextView(this).apply {
            text = "To switch it on: tap the button below → Installed apps → XARVIS Hands → On → Allow.\n" +
                "If it's greyed out: tap it once, then Settings → Apps → XARVIS → ⋮ (top right) → " +
                "Allow restricted settings, and switch it on again."
            textSize = 14f; setPadding(0, 0, 0, gap)
        })
        col.addView(button("Open Accessibility settings") {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        col.addView(button("Open XARVIS App info (for ⋮ → Allow restricted settings)") {
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null)))
            }
        })

        col.addView(header("REMOTE CONTROL — LIKE TEAMVIEWER"))
        col.addView(android.widget.CheckBox(this).apply {
            text = "🖥 Let my linked phone see and control this phone"
            textSize = 16f
            isChecked = com.xarvis.ai.hands.ScreenHost.allowed(this@HandsActivity)
            setOnCheckedChangeListener { _, on ->
                com.xarvis.ai.hands.ScreenHost.setAllowed(this@HandsActivity, on)
                // You're holding this phone now: allow smooth video too (Android asks once each time XARVIS starts).
                if (on && !com.xarvis.ai.hands.ScreenVideo.ready) com.xarvis.ai.hands.ScreenShareService.ask(this@HandsActivity)
            }
        })
        smooth = TextView(this).apply { textSize = 14f; setPadding(0, gap, 0, 0) }
        col.addView(smooth)
        col.addView(button("⚡ Allow smooth mode (live video)") { com.xarvis.ai.hands.ScreenShareService.ask(this) })
        col.addView(TextView(this).apply {
            text = "Only your paired phone can, over XARVIS's encrypted link. While it does, a red \"… is controlling " +
                "this phone · STOP\" banner shows here and the screen stays on; STOP ends it at once. Needs XARVIS Hands on. " +
                "On the other phone: ☰ → Control linked phone, or say \"control my S22\"."
            textSize = 13f; setTextColor(Color.parseColor("#666666"))
        })

        col.addView(header("TEST — call a contact in an app"))
        val name = EditText(this).apply { hint = "contact name, e.g. baarish"; textSize = 16f }
        col.addView(name)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun call(app: String) {
            val n = name.text.toString().trim()
            val hands = HandsBridge.service
            when {
                n.isEmpty() -> Toast.makeText(this, "Type a contact name first.", Toast.LENGTH_SHORT).show()
                hands == null -> Toast.makeText(this, "Switch XARVIS Hands on first (button above).", Toast.LENGTH_LONG).show()
                else -> hands.startCallInApp(app, n, false)
            }
        }
        row.addView(button("Call in imo") { call("imo") }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button("Call in WhatsApp") { call("whatsapp") }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row)
        col.addView(button("Stop") { HandsBridge.service?.cancelTask() })

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

    private fun render() {
        val on = HandsBridge.isRunning()
        status.text = when {
            !HandsBridge.builtIn -> "Not built in on this phone"
            on -> "✓ XARVIS Hands is on"
            HandsBridge.switchedOn(this) -> "… Switched on, connecting"
            else -> "✗ XARVIS Hands is off"
        }
        status.setTextColor(Color.parseColor(if (on) "#1B8A3A" else "#B00020"))
        if (::smooth.isInitialized) {
            val ready = com.xarvis.ai.hands.ScreenVideo.ready
            smooth.text = if (ready) "⚡ Smooth mode: ready — your linked phone sees this screen as live video."
            else "Smooth mode: not allowed yet (Android asks once each time XARVIS starts). Without it, remote control still works, a few pictures a second."
            smooth.setTextColor(Color.parseColor(if (ready) "#1B8A3A" else "#8A6D00"))
        }
        if (::log.isInitialized) log.text = synchronized(HandsBridge.logLines) {
            if (HandsBridge.logLines.isEmpty()) "(nothing yet)" else HandsBridge.logLines.takeLast(30).joinToString("\n")
        }
    }
}
