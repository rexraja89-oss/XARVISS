package com.xarvis.ai.shield

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * XARVIS Shield's security checkup: what on this phone could be used against Rex. Read-only, nothing new asked of
 * Android (no QUERY_ALL_PACKAGES: it looks at the apps with a home-screen icon, which is what XARVIS can already see).
 */
object Checkup {

    enum class Risk(val icon: String) { HIGH("🔴"), MEDIUM("🟠"), LOW("🟡"), OK("🟢") }

    data class Finding(val risk: Risk, val title: String, val detail: String, val fix: Intent? = null)

    private val STORES = setOf("com.android.vending", "com.sec.android.app.samsungapps", "com.google.android.feedback", "com.huawei.appmarket")
    private val TRUSTED_PREFIX = listOf("com.xarvis.", "com.google.", "com.samsung.", "com.sec.", "com.android.")

    fun run(context: Context): List<Finding> {
        val c = context.applicationContext
        val pm = c.packageManager
        val out = ArrayList<Finding>()
        val settings = { action: String -> Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

        // Screen lock.
        val secure = c.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
        out += if (secure) Finding(Risk.OK, "Screen lock is on", "Good — a stolen phone stays locked.")
        else Finding(Risk.HIGH, "No screen lock", "Anyone who picks up this phone gets into everything, banking included.", settings(Settings.ACTION_SECURITY_SETTINGS))

        // Android security updates.
        runCatching {
            val patch = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(Build.VERSION.SECURITY_PATCH)!!.time
            val months = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - patch) / 30
            out += when {
                months >= 12 -> Finding(Risk.HIGH, "Security updates are $months months old", "Holes found since then are open on this phone. Settings → Software update → Download and install.", settings(Settings.ACTION_DEVICE_INFO_SETTINGS))
                months >= 4 -> Finding(Risk.MEDIUM, "Security updates are $months months old", "Check Settings → Software update. (Older phones stop getting them.)", settings(Settings.ACTION_DEVICE_INFO_SETTINGS))
                else -> Finding(Risk.OK, "Security updates are recent", "From ${Build.VERSION.SECURITY_PATCH}.")
            }
        }

        // Apps that can read the screen and tap (Accessibility) — the #1 tool of banking malware.
        val a11y = (Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .split(':').filter { it.isNotBlank() }.map { it.substringBefore('/') }.distinct()
        val strangeA11y = a11y.filterNot { trusted(it) }
        if (strangeA11y.isNotEmpty()) out += Finding(
            Risk.HIGH, "Apps that can read your screen and tap for you",
            strangeA11y.joinToString(", ") { label(pm, it) } + " — if you don't know exactly why one needs it, switch it off.",
            settings(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        )
        val ownA11y = a11y.filter { it.startsWith("com.xarvis.") }
        if (ownA11y.isNotEmpty()) out += Finding(Risk.LOW, "XARVIS's own screen access is on", ownA11y.joinToString(", ") { label(pm, it) } + ". That's yours — keep your relay login private, since your linked phones can use it.")

        // Device admins (can lock, wipe, block uninstalling).
        val admins = c.getSystemService(DevicePolicyManager::class.java)?.activeAdmins.orEmpty().map { it.packageName }.filterNot { trusted(it) }
        if (admins.isNotEmpty()) out += Finding(
            Risk.HIGH, "Apps with device-admin power", admins.joinToString(", ") { label(pm, it) } + " can lock or wipe the phone and block their own removal.",
            settings(Settings.ACTION_SECURITY_SETTINGS),
        )

        // Notification readers (they see every OTP).
        val listeners = (Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: "")
            .split(':').filter { it.isNotBlank() }.map { it.substringBefore('/') }.distinct().filterNot { trusted(it) }
        if (listeners.isNotEmpty()) out += Finding(
            Risk.MEDIUM, "Apps that read all your notifications", listeners.joinToString(", ") { label(pm, it) } + " see every message and OTP code. Keep only ones you trust (smartwatch apps are normal).",
            settings("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"),
        )

        // USB debugging / developer options.
        if (Settings.Global.getInt(c.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1) out += Finding(
            Risk.MEDIUM, "USB debugging is on", "A computer plugged into this phone could control it. Turn it off unless you're using it.",
            settings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        )

        // Apps with a home-screen icon: who may install apps, and who came from outside a store.
        val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.distinct().filter { it != c.packageName }
        val installers = ArrayList<String>()
        val sideloaded = ArrayList<String>()
        val ops = c.getSystemService(AppOpsManager::class.java)
        for (pkg in launchable) {
            val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: continue
            if (info.flags and ApplicationInfo.FLAG_SYSTEM != 0) continue
            val mode = runCatching { ops?.unsafeCheckOpNoThrow("android:request_install_packages", info.uid, pkg) }.getOrNull()
            if (mode == AppOpsManager.MODE_ALLOWED && !pkg.startsWith("com.xarvis.")) installers += pkg
            val from = runCatching {
                if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).installingPackageName else @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)
            }.getOrNull()
            if (from !in STORES && !pkg.startsWith("com.xarvis.")) sideloaded += pkg
        }
        if (installers.isNotEmpty()) out += Finding(
            Risk.MEDIUM, "Apps allowed to install other apps", installers.joinToString(", ") { label(pm, it) } + ". A browser or WhatsApp with this on lets one wrong tap install malware. Turn it off and allow it only when you need it.",
            settings("android.settings.MANAGE_UNKNOWN_APP_SOURCES"),
        )
        if (sideloaded.isNotEmpty()) out += Finding(
            Risk.LOW, "Apps not from the Play Store or Galaxy Store", sideloaded.take(12).joinToString(", ") { label(pm, it) } + (if (sideloaded.size > 12) " and ${sideloaded.size - 12} more" else "") + ". Fine if you installed them on purpose; remove any you don't recognise.",
        )

        // What only Rex can check (Android doesn't tell apps).
        out += Finding(Risk.LOW, "Things only you can switch on", "• SIM PIN (Settings → Security → Other security settings → Set up SIM card lock) — stops a thief using your SIM for OTPs.\n• Play Protect on (Play Store → profile → Play Protect).\n• Two-step login on Gmail, WhatsApp (Settings → Account → Two-step verification) and your bank.")
        return out.sortedBy { it.risk.ordinal }
    }

    fun report(context: Context): String {
        val f = run(context)
        val bad = f.count { it.risk == Risk.HIGH || it.risk == Risk.MEDIUM }
        val head = if (bad == 0) "🛡 Security checkup: no serious problems on this phone, sir." else "🛡 Security checkup: $bad thing${if (bad == 1) "" else "s"} to fix, sir."
        return (listOf(head) + f.map { "${it.risk.icon} ${it.title} — ${it.detail}" }).joinToString("\n\n")
    }

    private fun trusted(pkg: String) = TRUSTED_PREFIX.any { pkg.startsWith(it) }

    fun label(pm: PackageManager, pkg: String): String =
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
}
