package com.xarvis.ai.shield

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * XARVIS Shield, always on (Rex, 15 Nov 2026: "an adamantium shield… always on alert, my personal invisible
 * bodyguard"). Three watchers, all on the phone, nothing sent anywhere:
 * - every notification (S22: through XARVIS Hands) is judged by [Scam] — a scam message raises the alarm;
 * - a new app with dangerous powers, or from outside a store, is reported here and on the other phone;
 * - SIM swap: no mobile signal for 10 minutes while the phone is online on Wi-Fi.
 * [alert] is the Board's (chat + notification), [tellOthers] the other phones.
 */
object Shield {

    @Volatile private var app: Context? = null
    private var alert: (String) -> Unit = {}
    private var tellOthers: (String) -> Unit = {}
    private val recent = HashMap<Int, Long>()

    fun start(context: Context, scope: CoroutineScope, alert: (String) -> Unit, tellOthers: (String) -> Unit) {
        if (app != null) return
        val c = context.applicationContext
        app = c
        this.alert = alert
        this.tellOthers = tellOthers
        runCatching {
            ContextCompat.registerReceiver(
                c, newApps, IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") },
                ContextCompat.RECEIVER_EXPORTED,
            )
        }
        scope.launch { simWatch(c) }
    }

    var on: Boolean
        get() = prefs()?.getBoolean("on", true) ?: true
        set(v) { prefs()?.edit()?.putBoolean("on", v)?.apply() }

    private fun prefs() = app?.getSharedPreferences("shield", Context.MODE_PRIVATE)

    /** The last things the Shield caught, newest first ("time · text"). */
    fun log(): List<String> {
        val a = prefs()?.getString("log", null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it.optLong("at"))) + " · " + it.optString("t")
        }
    }

    private fun raise(text: String, others: Boolean) {
        prefs()?.let { p ->
            val old = p.getString("log", null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
            val log = JSONArray().put(JSONObject().put("at", System.currentTimeMillis()).put("t", text.take(400)))
            for (i in 0 until minOf(old.length(), 29)) log.put(old.get(i))
            p.edit().putString("log", log.toString()).apply()
        }
        if (!on) return
        alert(text)
        if (others) tellOthers(text)
    }

    // ---- scam messages ----

    /** A notification XARVIS Hands saw (title + text), from [appName]. */
    fun onNotification(pkg: String, appName: String, title: String, text: String) {
        if (pkg.startsWith("com.xarvis.") || pkg == "android" || pkg.startsWith("com.android.systemui")) return
        val all = "$title $text".trim()
        if (all.length < 12) return
        val v = Scam.message(all, appName)
        if (v.level == Scam.Level.SAFE || v.codeOnly) return
        val key = all.hashCode()
        val now = System.currentTimeMillis()
        synchronized(recent) {
            if ((recent[key] ?: 0) > now - 10 * 60_000) return
            recent[key] = now
            if (recent.size > 200) recent.clear()
        }
        val head = if (v.level == Scam.Level.DANGER) "🛡⛔ Scam alert, sir" else "🛡⚠ Careful, sir"
        raise("$head — $appName: “${all.take(140)}”\n" + v.reasons.take(3).joinToString("\n") { "• $it" } + "\nDon't tap its link, don't reply, don't call back on numbers in it.", others = false)
    }

    // ---- new apps ----

    private val newApps = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            val pkg = i.data?.schemeSpecificPart ?: return
            if (pkg.startsWith("com.xarvis.")) return
            val text = describeNewApp(c, pkg) ?: return
            raise(text, others = true)
        }
    }

    private const val A11Y = "android.permission.BIND_ACCESSIBILITY_SERVICE"
    private const val ADMIN = "android.permission.BIND_DEVICE_ADMIN"
    private const val LISTENER = "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"

    /** Null when the new app looks ordinary (from a store, no dangerous powers). */
    fun describeNewApp(c: Context, pkg: String): String? {
        val pm = c.packageManager
        @Suppress("DEPRECATION")
        val info = runCatching {
            pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS)
        }.getOrNull()
        val name = Checkup.label(pm, pkg)
        val from = runCatching {
            if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).installingPackageName else @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)
        }.getOrNull()
        val store = from in setOf("com.android.vending", "com.sec.android.app.samsungapps")
        val powers = ArrayList<String>()
        if (info != null) {
            val perms = info.requestedPermissions.orEmpty().toSet()
            if (info.services.orEmpty().any { it.permission == A11Y }) powers += "read your screen and tap for you"
            if (info.services.orEmpty().any { it.permission == LISTENER }) powers += "read all your notifications (OTPs too)"
            if (info.receivers.orEmpty().any { it.permission == ADMIN }) powers += "become device admin (lock/wipe, block removal)"
            if (perms.any { it.endsWith(".READ_SMS") || it.endsWith(".RECEIVE_SMS") }) powers += "read your SMS (bank OTPs)"
            if ("android.permission.REQUEST_INSTALL_PACKAGES" in perms) powers += "install other apps"
            if ("android.permission.SYSTEM_ALERT_WINDOW" in perms) powers += "draw over other apps (fake login screens)"
        }
        if (store && powers.isEmpty()) return null
        val phone = Settings.Global.getString(c.contentResolver, "device_name") ?: Build.MODEL
        val source = when {
            store -> "from the store"
            from == null -> "not from any store"
            else -> "installed by ${Checkup.label(pm, from)}, not a store"
        }
        return "🛡 New app on $phone: $name ($source)" +
            (if (powers.isNotEmpty()) " — it can ${powers.joinToString(", ")}." else ".") +
            " If you didn't install it yourself, remove it now."
    }

    // ---- SIM swap ----

    private suspend fun simWatch(c: Context) {
        var noSignalSince = 0L
        var lastAlarm = 0L
        while (true) {
            delay(2 * 60_000)
            val now = System.currentTimeMillis()
            val lost = runCatching { noMobileSignal(c) }.getOrDefault(false)
            if (!lost) { noSignalSince = 0; continue }
            if (noSignalSince == 0L) noSignalSince = now
            if (now - noSignalSince >= 10 * 60_000 && now - lastAlarm > 6 * 3600_000L) {
                lastAlarm = now
                val phone = Settings.Global.getString(c.contentResolver, "device_name") ?: Build.MODEL
                raise(
                    "🛡📵 $phone has had NO mobile signal for ${(now - noSignalSince) / 60_000} minutes while it's online on Wi-Fi. " +
                        "If it normally has signal where it is, your number may have been moved to someone else's SIM (SIM swap) — they'd get your bank OTPs. " +
                        "Call your carrier from another phone at once and ask if your SIM was changed.",
                    others = true,
                )
            }
        }
    }

    /** SIM in and ready, not in flight mode, online through Wi-Fi — yet no mobile network at all. */
    private fun noMobileSignal(c: Context): Boolean {
        if (Settings.Global.getInt(c.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1) return false
        val tm = c.getSystemService(TelephonyManager::class.java) ?: return false
        if (tm.simState != TelephonyManager.SIM_STATE_READY) return false
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return false
        val online = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        if (!online) return false
        if (Build.VERSION.SDK_INT < 28) return false
        val signal = tm.signalStrength ?: return false
        return signal.cellSignalStrengths.isEmpty()
    }
}
