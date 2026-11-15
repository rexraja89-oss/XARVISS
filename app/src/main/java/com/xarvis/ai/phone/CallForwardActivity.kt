package com.xarvis.ai.phone

import android.app.Activity
import android.app.TimePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** ☰ → 📱 My devices → 📞 Call forwarding: calls to this phone go to another number during a time frame. */
class CallForwardActivity : Activity() {
    private val cyan = Color.rgb(0, 229, 255)
    private val muted = Color.rgb(160, 174, 192)

    private lateinit var number: EditText
    private lateinit var from: Button
    private lateinit var to: Button
    private lateinit var on: Switch
    private lateinit var missed: RadioButton
    private lateinit var status: TextView
    private var sh = 21
    private var sm = 0
    private var eh = 7
    private var em = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = CallForward.plan(this)
        sh = p.startHour; sm = p.startMinute; eh = p.endHour; em = p.endMinute
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(32)) }
        col.addView(text("📞 Call forwarding", 24f, cyan))
        col.addView(text("Calls to this phone ring on your other number during the hours you choose. XARVIS switches it on at the start and off at the end, every day.", 14f, muted))
        if (!CallForward.allowed(this)) {
            if (canAsk()) {
                // The S22 build has the permission, but Android hasn't been asked for it yet.
                col.addView(text("XARVIS needs Android's \"Phone\" permission to switch forwarding.", 16f, Color.WHITE).pad(16))
                col.addView(Button(this).apply {
                    text = "✅ Allow XARVIS to make calls"; isAllCaps = false
                    setOnClickListener { requestPermissions(arrayOf(android.Manifest.permission.CALL_PHONE), 1) }
                })
            } else {
                col.addView(text("This phone's XARVIS can't place calls, so it can't switch forwarding. Set it up on the S22.", 16f, Color.WHITE).pad(16))
            }
        }
        col.addView(text("Forward to (your benco number, with the country code)", 13f, cyan).pad(20))
        number = EditText(this).apply {
            setText(p.number); hint = "+971 5X XXX XXXX"; inputType = InputType.TYPE_CLASS_PHONE
            setTextColor(Color.WHITE); setHintTextColor(muted)
        }
        col.addView(number)
        col.addView(text("Time frame", 13f, cyan).pad(20))
        from = Button(this).apply { isAllCaps = false; setOnClickListener { pick(true) } }
        to = Button(this).apply { isAllCaps = false; setOnClickListener { pick(false) } }
        col.addView(LinearLayout(this).apply { addView(from); addView(to) })
        showTimes()
        col.addView(text("Which calls", 13f, cyan).pad(20))
        val all = RadioButton(this).apply { text = "Every call (this phone doesn't ring)"; setTextColor(Color.WHITE); id = 1 }
        missed = RadioButton(this).apply { text = "Only calls I don't answer, or when I'm busy or have no signal"; setTextColor(Color.WHITE); id = 2 }
        col.addView(RadioGroup(this).apply { addView(all); addView(missed); check(if (p.missedOnly) 2 else 1) })
        on = Switch(this).apply {
            text = "Forward on this schedule"; textSize = 16f; setTextColor(Color.WHITE); isChecked = p.enabled
            setPadding(0, dp(16), 0, dp(8))
        }
        col.addView(on)
        col.addView(Button(this).apply { text = "💾 Save"; isAllCaps = false; setOnClickListener { save() } })
        col.addView(LinearLayout(this).apply {
            addView(Button(this@CallForwardActivity).apply { text = "Forward now"; isAllCaps = false; setOnClickListener { save(quiet = true); say(CallForward.now(this@CallForwardActivity, true)) } })
            addView(Button(this@CallForwardActivity).apply { text = "Stop now"; isAllCaps = false; setOnClickListener { save(quiet = true); say(CallForward.now(this@CallForwardActivity, false)) } })
        })
        status = text("", 14f, Color.WHITE).pad(12)
        col.addView(status)
        col.addView(text(
            "• Your carrier does the forwarding; XARVIS only switches it, with the same codes you can dial yourself (**21*number# on, ##21# off). " +
                "A short call-like screen and a carrier message may appear each time.\n" +
                "• Your carrier may charge for forwarded calls.\n" +
                "• With two SIMs, the phone's default calling SIM is switched.",
            13f, muted,
        ).pad(16))
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(col) })
        showStatus()
    }

    /** This build declares CALL_PHONE (the S22's; the benco's drops it). */
    private fun canAsk(): Boolean = runCatching {
        packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(android.Manifest.permission.CALL_PHONE) == true
    }.getOrDefault(false)

    @Deprecated("A plain Activity result")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (CallForward.allowed(this)) {
            save()
            recreate()
        } else {
            say("Without it XARVIS can't switch forwarding. Settings → Apps → XARVIS → Permissions → Phone → Allow.")
        }
    }

    private fun pick(start: Boolean) {
        TimePickerDialog(this, { _, h, m ->
            if (start) { sh = h; sm = m } else { eh = h; em = m }
            showTimes()
        }, if (start) sh else eh, if (start) sm else em, false).show()
    }

    private fun showTimes() {
        from.text = "From ${CallForward.label(sh, sm)}"
        to.text = "To ${CallForward.label(eh, em)}"
    }

    private fun save(quiet: Boolean = false) {
        val plan = CallForward.Plan(on.isChecked, number.text.toString(), sh, sm, eh, em, missed.isChecked)
        if (plan.enabled && plan.number.count { it.isDigit() } < 7) {
            say("Type the full number to forward to.")
            return
        }
        CallForward.save(this, plan)
        if (!quiet) say(if (plan.enabled) "Saved: calls go to ${plan.number} from ${CallForward.label(sh, sm)} to ${CallForward.label(eh, em)}." else "Saved: no schedule.")
        showStatus()
    }

    private fun showStatus() {
        val p = CallForward.plan(this)
        status.text = when {
            !p.enabled -> "No schedule."
            CallForward.forwarding(this) == true -> "Now: forwarding to ${p.number} until ${CallForward.label(p.endHour, p.endMinute)}."
            else -> "Now: calls ring here. Forwarding starts at ${CallForward.label(p.startHour, p.startMinute)}."
        }
    }

    private fun say(t: String) {
        Toast.makeText(this, t, Toast.LENGTH_LONG).show()
        status.postDelayed({ showStatus() }, 1500)
    }

    private fun text(t: String, size: Float, color: Int) = TextView(this).apply { text = t; textSize = size; setTextColor(color) }
    private fun TextView.pad(top: Int) = apply { setPadding(0, dp(top), 0, 0) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
