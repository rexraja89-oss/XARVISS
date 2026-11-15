package com.xarvis.ai.shield

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * ☰ → 📱 My devices → 🛡 XARVIS Shield: the checkup with a fix button per problem, the scam checker (also what
 * Share → "Check with XARVIS Shield" opens), and what the Shield caught lately.
 */
class ShieldActivity : Activity() {
    private val cyan = Color.rgb(0, 229, 255)
    private val muted = Color.rgb(160, 174, 192)
    private lateinit var col: LinearLayout
    private lateinit var verdict: TextView
    private lateinit var box: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(32)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(col) })
        build()
        shared(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        shared(intent)
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun shared(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val text = listOfNotNull(i.getStringExtra(Intent.EXTRA_SUBJECT), i.getStringExtra(Intent.EXTRA_TEXT)).joinToString(" ")
        if (text.isBlank()) return
        box.setText(text)
        verdict.text = ShieldCommands.judge(text)
    }

    private fun build() {
        val keep = if (::box.isInitialized) box.text.toString() else ""
        val kept = if (::verdict.isInitialized) verdict.text else ""
        col.removeAllViews()
        col.addView(text("🛡 XARVIS Shield", 24f, cyan, bold = true))
        col.addView(text("Your bodyguard: it reads incoming messages for scams, watches for dangerous new apps and for your SIM being stolen, and checks this phone for weak spots. Everything stays on your phones.", 14f, muted))
        col.addView(Switch(this).apply {
            text = "Always on alert"; textSize = 16f; setTextColor(Color.WHITE); isChecked = Shield.on
            setPadding(0, dp(12), 0, dp(4))
            setOnCheckedChangeListener { _, v -> Shield.on = v }
        })

        col.addView(heading("Is this a scam?"))
        col.addView(text("Paste a message or link — or in any app: Share → “Check with XARVIS Shield”.", 13f, muted))
        box = EditText(this).apply { setText(keep); hint = "Paste the message or link here"; setTextColor(Color.WHITE); setHintTextColor(muted); minLines = 2 }
        col.addView(box)
        col.addView(button("🔍 Check it") { verdict.text = if (box.text.isBlank()) "Paste something first." else ShieldCommands.judge(box.text.toString()) })
        verdict = text(kept.toString(), 15f, Color.WHITE)
        col.addView(verdict)

        col.addView(heading("Security checkup"))
        for (f in Checkup.run(this)) {
            col.addView(text("${f.risk.icon} ${f.title}", 15f, Color.WHITE, bold = true).apply { setPadding(0, dp(10), 0, 0) })
            col.addView(text(f.detail, 13f, muted))
            f.fix?.let { fix -> col.addView(button("Fix it ›") { runCatching { startActivity(fix) } }) }
        }

        val log = Shield.log()
        col.addView(heading("Caught lately"))
        col.addView(text(if (log.isEmpty()) "Nothing yet — all quiet." else log.take(15).joinToString("\n\n"), 13f, muted))
        col.addView(text(
            "Honest limits: no shield is perfect. Scam messages are read on the S22 (through XARVIS Hands). " +
                "Never share a code, PIN or password with anyone — no bank ever asks.",
            12f, muted,
        ).apply { setPadding(0, dp(20), 0, 0) })
    }

    private fun heading(t: String) = text(t, 13f, cyan, bold = true).apply { setPadding(0, dp(24), 0, dp(4)) }
    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun button(t: String, onClick: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { onClick() } }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
