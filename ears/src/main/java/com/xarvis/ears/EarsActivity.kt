package com.xarvis.ears

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.xarvis.wake.WakeEars
import com.xarvis.wake.WakeScreen

/**
 * XARVIS Ears' screen: switch the ears on or off, give them the microphone, and (from the wake
 * word screen it extends) practise saying "Xarvis" and choose how easily it wakes. Opening this
 * screen is also one of the ways Android lets the ears have the microphone back after a restart.
 */
class EarsActivity : WakeScreen() {

    private lateinit var onOff: Button
    private lateinit var note: TextView
    private lateinit var battery: Button

    override fun addRows(column: LinearLayout) {
        onOff = Button(this).apply {
            textSize = 17f
            setOnClickListener { if (EarsSwitch.isOn(this@EarsActivity)) turnOff() else turnOn() }
        }
        column.addView(onOff, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        note = text("", 14f, MUTED).pad(top = 8)
        column.addView(note)
        battery = Button(this).apply {
            text = "Let it run all day (battery: no limits)"
            setOnClickListener { askBattery() }
        }
        column.addView(battery, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    override fun statusLine(): String {
        refreshRows()
        return when {
            !EarsSwitch.isOn(this) -> "Off"
            !hasMic() -> "Needs the microphone permission"
            else -> WakeEars.current?.status ?: "Starting…"
        }
    }

    override fun onResume() {
        super.onResume()
        // On screen: Android gives the service the microphone when it's started from here.
        if (EarsSwitch.isOn(this) && hasMic()) EarsService.start(this)
    }

    private fun refreshRows() {
        val on = EarsSwitch.isOn(this)
        onOff.text = if (on) "Turn off" else "Turn on: listen for \"Xarvis\""
        note.text = when {
            !XarvisLink.xarvisInstalled(this) -> "XARVIS isn't on this phone. Ears only listens — XARVIS thinks and answers."
            on && EarsService.stoodDown && !EarsService.running ->
                "This phone's XARVIS listens for \"Xarvis\" by itself (its ☰ menu switch), so Ears isn't needed here."
            on -> "Ears hears \"Xarvis\" and what you say next, and hands the words to XARVIS, which answers aloud. " +
                "It has no internet permission: nothing it hears leaves the phone through this app."
            else -> "Turn it on, allow the microphone, and say \"Xarvis\" — also with the screen off."
        }
        note.setTextColor(if (!XarvisLink.xarvisInstalled(this)) Color.rgb(248, 113, 113) else MUTED)
        val power = getSystemService(PowerManager::class.java)
        battery.visibility = if (power.isIgnoringBatteryOptimizations(packageName)) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun turnOn() {
        val wanted = buildList {
            if (!hasMic()) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (wanted.isNotEmpty()) {
            requestPermissions(wanted.toTypedArray(), ASK_PERMISSIONS)
            return
        }
        EarsSwitch.set(this, true)
        EarsService.start(this)
        refreshRows()
    }

    private fun turnOff() {
        EarsService.stop(this)
        EarsSwitch.set(this, false)
        refreshRows()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != ASK_PERMISSIONS) return
        if (hasMic()) {
            EarsSwitch.set(this, true)
            EarsService.start(this)
        } else {
            note.text = "Without the microphone permission Ears can't hear \"Xarvis\". Tap Turn on again and allow it " +
                "(or Settings → Apps → XARVIS Ears → Permissions)."
        }
        refreshRows()
    }

    /** Phones stop background apps overnight to save battery; this asks Android to leave Ears alone. */
    @SuppressLint("BatteryLife") // a personal, sideloaded app whose whole job is to keep listening
    private fun askBattery() {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private companion object {
        const val ASK_PERMISSIONS = 7
    }
}
