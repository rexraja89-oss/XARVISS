package com.xarvis.ai.device

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs

data class Capability(val name: String, val available: Boolean, val detail: String = "")

data class InstalledApp(val label: String, val packageName: String)

/** Detects hardware features and installed apps on the device. */
class DeviceCapabilityManager(context: Context) {

    private val appContext = context.applicationContext
    private val pm = appContext.packageManager

    fun capabilities(): List<Capability> = listOf(
        feature("Camera", PackageManager.FEATURE_CAMERA_ANY),
        feature("GPS", PackageManager.FEATURE_LOCATION_GPS),
        feature("Microphone", PackageManager.FEATURE_MICROPHONE),
        feature("Telephony", PackageManager.FEATURE_TELEPHONY),
        feature("Bluetooth", PackageManager.FEATURE_BLUETOOTH),
        feature("NFC", PackageManager.FEATURE_NFC),
        Capability("Battery", true, "${batteryPercent()}%"),
        Capability("Storage", true, "${freeStorageGb()} GB free"),
    )

    fun summary(): String = buildString {
        append("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n")
        capabilities().forEach { c ->
            append(if (c.available) "  [+] " else "  [-] ")
            append(c.name)
            if (c.detail.isNotEmpty()) append(": ${c.detail}")
            append('\n')
        }
    }.trimEnd()

    fun launchableApps(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return resolved
            .map { InstalledApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
    }

    /** The installed app Rex means by [name] ("chat gpt", "YT studio", "files"); see [AppNames]. */
    fun findApp(name: String): InstalledApp? = AppNames.best(name, launchableApps())

    fun launchIntent(app: InstalledApp): Intent? =
        pm.getLaunchIntentForPackage(app.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun feature(name: String, feature: String) =
        Capability(name, pm.hasSystemFeature(feature))

    private fun batteryPercent(): Int {
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun freeStorageGb(): Long {
        val stat = StatFs(Environment.getDataDirectory().path)
        return stat.availableBytes / (1024L * 1024L * 1024L)
    }
}
