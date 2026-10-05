package com.xarvis.hands

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One screen, built in code so the app needs no layout files or Compose. It explains what XARVIS
 * Hands is, has a button that opens Android's Accessibility settings so Rex can turn it on, and
 * shows a LIVE diagnostic: whether the service is connected, how many accessibility events it has
 * received, and what the last event was. That diagnostic is the whole point of this build — it
 * proves the service starts and receives events on the S22, with no automation of any kind.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var events: TextView
    private lateinit var last: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val refresh = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val gap = (12 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val title = TextView(this).apply {
            text = "XARVIS Hands"
            textSize = 26f
            setTextColor(Color.parseColor("#0B6E8C"))
            gravity = Gravity.CENTER
        }
        val blurb = TextView(this).apply {
            text = "Diagnostic build. Turn XARVIS Hands on in Accessibility, then come back here " +
                "and move around your phone — the numbers below should change. It only watches; " +
                "it taps nothing."
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, gap, 0, pad)
        }
        status = TextView(this).apply {
            textSize = 17f
            gravity = Gravity.CENTER
        }
        events = TextView(this).apply {
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(0, gap, 0, 0)
        }
        last = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, gap, 0, pad)
        }
        val openSettings = Button(this).apply {
            text = "Open Accessibility settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        root.addView(title)
        root.addView(blurb)
        root.addView(status)
        root.addView(events)
        root.addView(last)
        root.addView(openSettings)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresh) // start the once-a-second live update
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(refresh)
    }

    private fun render() {
        val on = HandsService.isRunning()
        status.text = if (on) "✓ Service connected (it started)" else "✗ Service not connected"
        status.setTextColor(if (on) Color.parseColor("#1B8A3A") else Color.parseColor("#B00020"))

        events.text = "Events received: ${HandsService.eventCount}"
        events.setTextColor(
            if (HandsService.eventCount > 0) Color.parseColor("#1B8A3A") else Color.parseColor("#888888")
        )

        last.text = if (HandsService.lastEventTime > 0) {
            "Last: ${HandsService.lastEventPackage} · ${HandsService.lastEventType} · " +
                timeFmt.format(Date(HandsService.lastEventTime))
        } else {
            "Last: (no events yet)"
        }
    }
}
