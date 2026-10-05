package com.xarvis.hands

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * One screen, built in code so the app needs no layout files or Compose. It explains what XARVIS
 * Hands is and has a button that opens Android's Accessibility settings so Rex can turn it on. The
 * status line shows whether the service is currently running.
 *
 * This is the install test: if this app installs on the S22, a separate Accessibility app is the way
 * to give XARVIS hands; then the real tapping is built into [HandsService].
 */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (24 * resources.displayMetrics.density).toInt()
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
            text = "This gives XARVIS hands — it can finish the taps for things you ask for, " +
                "like placing a call inside an app. Turn it on in Accessibility, and it only " +
                "acts when you ask and while you watch."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, pad)
        }
        status = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, pad)
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
        root.addView(openSettings)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        status.text = if (HandsService.isRunning()) {
            "✓ XARVIS Hands is on and ready."
        } else {
            "XARVIS Hands is off. Tap below, then turn on XARVIS Hands."
        }
        status.setTextColor(
            if (HandsService.isRunning()) Color.parseColor("#1B8A3A") else Color.parseColor("#888888")
        )
    }
}
