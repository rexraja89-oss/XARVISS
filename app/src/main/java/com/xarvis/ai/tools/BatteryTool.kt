package com.xarvis.ai.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager

/** "How much battery do I have?": charge level, whether it's charging, and time to full when known. */
class BatteryTool(context: Context) : DeviceTool {

    private val appContext = context.applicationContext

    override val label = "battery"

    override fun matches(message: String) = isAbout(message)

    override suspend fun read(message: String): String {
        // The sticky battery broadcast holds the latest state; no receiver or permission needed.
        val status = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return "Battery: unavailable, the phone didn't report its battery state."
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
        val charging = when (status.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_FULL -> "fully charged"
            else -> "not charging"
        }
        val plug = when (status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> " from a wall charger"
            BatteryManager.BATTERY_PLUGGED_USB -> " over USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> " wirelessly"
            else -> ""
        }
        val temp = status.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE }?.let { ", temperature ${it / 10.0} °C" } ?: ""

        val parts = mutableListOf(
            (percent?.let { "$it%" } ?: "level unknown") + ", " + charging + (if (charging == "charging") plug else ""),
        )
        if (charging == "charging") {
            val msLeft = appContext.getSystemService(BatteryManager::class.java)?.computeChargeTimeRemaining() ?: -1
            if (msLeft > 0) parts += "about ${minutes(msLeft)} until full"
        }
        if (appContext.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true) parts += "battery saver is on"
        return "Battery: " + parts.joinToString(", ") + temp
    }

    private fun minutes(ms: Long): String {
        val m = (ms / 60_000).coerceAtLeast(1)
        return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
    }

    internal companion object {
        fun isAbout(message: String) = TOPIC.containsMatchIn(message.lowercase())

        val TOPIC = Regex("""\b(battery|batteries|charge|charging|charged|charger|power left|battery life|percent)\b""")
    }
}
