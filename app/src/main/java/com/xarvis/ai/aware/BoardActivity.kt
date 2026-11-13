package com.xarvis.ai.aware

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.xarvis.ai.XarvisApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** ☰ → 🛰 Situation: what XARVIS knows about all of Rex's devices right now, the watchers' last alerts, two switches. */
class BoardActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var report: TextView
    private lateinit var alerts: TextView
    private lateinit var refresh: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as XarvisApp).core // make sure XARVIS (and its board) is running
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(text("🛰 Situation", 24f, bold = true))
        root.addView(
            text(
                "What XARVIS knows about all your devices, live — battery, network, who's using them, missed calls. " +
                    "Say \"status report\" in any chat, or ask \"is my S22 charging?\".",
                14f, Color.DKGRAY,
            ).apply { setPadding(0, dp(6), 0, dp(12)) },
        )
        refresh = Button(this).apply {
            text = "🔄 Check in with my devices"
            isAllCaps = false
            setOnClickListener { load(fresh = true) }
        }
        root.addView(refresh)
        report = text("", 15f).apply {
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(16))
        }
        root.addView(report)
        root.addView(text("Alerts", 18f, bold = true))
        alerts = text("", 14f, Color.DKGRAY).apply { setPadding(0, dp(4), 0, dp(16)) }
        root.addView(alerts)
        val board = Board.instance
        if (board != null) {
            root.addView(Switch(this).apply {
                text = "Speak up by myself: low battery, unlocks while you're away, missed calls, lost contact"
                isChecked = board.alertsOn
                setOnCheckedChangeListener { _, on -> board.alertsOn = on }
                setPadding(0, dp(8), 0, dp(8))
            })
            root.addView(Switch(this).apply {
                text = "Share this phone's notifications with my other phones (codes hidden, banking apps never)"
                isChecked = board.shareNotes
                setOnCheckedChangeListener { _, on -> board.shareNotes = on }
                setPadding(0, dp(8), 0, dp(8))
            })
        }
        root.addView(
            text(
                "Your phones swap a small status card every 15 minutes and whenever something changes, end-to-end " +
                    "encrypted like everything else between them. Notifications are read on the S22 only (XARVIS Hands).",
                12f, Color.GRAY,
            ).apply { setPadding(0, dp(12), 0, 0) },
        )
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        load(fresh = true)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun load(fresh: Boolean) {
        val board = Board.instance
        if (board == null) {
            report.text = "XARVIS is still starting — try again in a moment."
            return
        }
        report.text = board.report()
        alerts.text = board.recentAlerts().joinToString("\n").ifBlank { "None yet." }
        if (!fresh) return
        refresh.isEnabled = false
        refresh.text = "🔄 Checking in…"
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { board.refresh() } }
            report.text = board.report()
            alerts.text = board.recentAlerts().joinToString("\n").ifBlank { "None yet." }
            refresh.isEnabled = true
            refresh.text = "🔄 Check in with my devices"
        }
    }

    private fun text(s: String, size: Float, color: Int = Color.BLACK, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
