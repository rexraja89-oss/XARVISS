package com.xarvis.wake

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * The wake word screen: shows what the ears are doing, lets Rex practise ("Xarvis" only beeps and
 * counts while this is open) and choose how easily it wakes. XARVIS opens it as it is; XARVIS Ears
 * adds its own rows on top ([addRows]).
 */
open class WakeScreen : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var heard: TextView
    private lateinit var meter: ProgressBar
    private lateinit var sound: TextView
    private var count = 0
    private var best = 0f
    private var bestAt = 0L
    private var watching: WakeEars? = null

    private val refresh = object : Runnable {
        override fun run() {
            attach()
            status.text = statusLine()
            main.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = WakeSettings(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        column.addView(text("Say \"Xarvis\"", 24f, CYAN, bold = true))
        column.addView(text("XARVIS listens for its name, screen on or off, and talks with you.", 14f, MUTED).pad(top = 4))
        status = text("", 16f, Color.WHITE, bold = true).pad(top = 16)
        column.addView(status)
        addRows(column)

        column.addView(heading("Practice"))
        column.addView(text("While this screen is open, saying \"Xarvis\" only beeps and counts — try it from where you usually are.", 14f, MUTED))
        heard = text("Not heard yet", 20f, Color.WHITE, bold = true).pad(top = 12)
        column.addView(heard)
        meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        column.addView(meter, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(14)).apply { topMargin = dp(8) })
        sound = text("", 12f, MUTED).pad(top = 4)
        column.addView(sound)

        column.addView(heading("How easily it wakes"))
        val group = RadioGroup(this)
        Sensitivity.entries.forEach { level ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = level.label
                setTextColor(Color.WHITE)
                textSize = 15f
                isChecked = settings.sensitivity == level
                setOnClickListener { settings.sensitivity = level }
            })
        }
        column.addView(group)

        column.addView(heading("Talking"))
        column.addView(switch("Keep listening after each answer", settings.followUp) { settings.followUp = it })
        column.addView(text("So you can go on talking without saying \"Xarvis\" again. It stops when you say nothing, or \"that's all\".", 13f, MUTED))
        column.addView(switch("\"Xarvis\" interrupts it while it talks", settings.bargeIn) { settings.bargeIn = it }.pad(top = 10))

        column.addView(heading("Good to know"))
        column.addView(text(
            "• Say \"Hey Xarvis\" — the most reliable — or just \"Xarvis\" (Zarvis or Jarvis both work), wait for \"Yes, sir?\" and the beep, then speak. " +
                "\"Xarvis, what time is it?\" in one breath wakes it too, but it only hears what you say after the beep.\n" +
                "• Android shows the green microphone dot the whole time. Nothing is recorded or sent anywhere: the wake word is recognised on this phone.\n" +
                "• Anyone who says \"Xarvis\" can talk to it, also while the phone is locked.\n" +
                "• Listening all day uses some battery.",
            14f, MUTED,
        ))
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(column)
        })
    }

    /** XARVIS Ears puts its own rows (on/off, permissions) here, under the status line. */
    protected open fun addRows(column: LinearLayout) {}

    /** The line under the title. */
    protected open fun statusLine(): String = WakeEars.current?.status ?: "Not listening — turn it on in XARVIS's ☰ menu."

    override fun onResume() {
        super.onResume()
        main.post(refresh)
    }

    override fun onPause() {
        main.removeCallbacks(refresh)
        watching?.let {
            it.practice = false
            it.onScore = null
            it.onHit = null
        }
        watching = null
        super.onPause()
    }

    /** Follows the ears running in this app (they may start after this screen opens). */
    private fun attach() {
        val ears = WakeEars.current
        if (ears === watching) return
        watching?.let {
            it.practice = false
            it.onScore = null
            it.onHit = null
        }
        watching = ears
        ears ?: return
        ears.practice = true
        ears.onHit = { score ->
            main.post {
                count++
                heard.text = "✓ Heard you — $count time${if (count == 1) "" else "s"} (${percent(score)})"
                heard.setTextColor(GREEN)
            }
        }
        ears.onScore = { score, level, awake ->
            // The bar holds the best score of the last second, so a short word is visible.
            val now = System.currentTimeMillis()
            if (score >= best || now - bestAt > 1000) {
                best = score
                bestAt = now
                main.post {
                    meter.progress = (score * 100).toInt()
                    sound.text = "Sound level ${level.toInt()}" + if (awake) "" else " · quiet, resting"
                }
            }
        }
    }

    private fun percent(score: Float) = "${(score * 100).toInt()}%"

    protected fun heading(label: String): TextView = text(label, 13f, CYAN, bold = true).pad(top = 26, bottom = 6)

    protected fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.START
    }

    protected fun switch(label: String, on: Boolean, changed: (Boolean) -> Unit): Switch = Switch(this).apply {
        text = label
        textSize = 15f
        setTextColor(Color.WHITE)
        isChecked = on
        setOnCheckedChangeListener { _: CompoundButton, value: Boolean -> changed(value) }
    }

    protected fun <T : View> T.pad(top: Int = 0, bottom: Int = 0): T = apply { setPadding(paddingLeft, dp(top), paddingRight, dp(bottom)) }

    protected fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        val CYAN = Color.rgb(0, 229, 255)
        val MUTED = Color.rgb(160, 174, 192)
        val GREEN = Color.rgb(74, 222, 128)
    }
}
