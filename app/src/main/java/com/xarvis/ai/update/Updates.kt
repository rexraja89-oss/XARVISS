package com.xarvis.ai.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.xarvis.ai.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Finds out whether GitHub has a newer XARVIS than this one, and opens its download in Chrome.
 * Chrome, not whatever app claims github.com links: the GitHub app and in-app browsers
 * downloaded the APK somewhere Rex couldn't install it from, so every update was downloaded twice.
 */
object Updates {

    private const val LATEST = "https://api.github.com/repos/rexraja89-oss/XARVISS/releases/latest"
    private const val DOWNLOAD = "https://github.com/rexraja89-oss/XARVISS/releases/latest/download/"

    /** This phone's APK: the benco has its own build. */
    val apkUrl: String get() = DOWNLOAD + if (BuildConfig.FLAVOR == "benco") "XARVIS-benco.apk" else "XARVIS.apk"

    /** The newest version on GitHub ("1.0.46") if it's newer than this one; null if not, or offline. */
    suspend fun newerVersion(): String? = withContext(Dispatchers.IO) {
        val tag = runCatching { tagFromApi() }.getOrNull() ?: runCatching { tagFromWebsite() }.getOrNull()
        tag?.removePrefix("v")?.takeIf { isNewer(it, BuildConfig.VERSION_NAME) }
    }

    private fun tagFromApi(): String {
        val c = URL(LATEST).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 10_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        return try {
            JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getString("tag_name")
        } finally {
            c.disconnect()
        }
    }

    /**
     * GitHub's API allows only 60 unsigned requests an hour per internet connection, and both phones
     * (plus the Hands/Eyes apps) share Rex's Wi-Fi — once over, it refuses silently and the UPDATE button
     * never appeared. The website isn't limited that way: /releases/latest redirects to /releases/tag/vX.
     */
    private fun tagFromWebsite(): String? {
        val c = URL(WEB_LATEST).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 8_000
        c.readTimeout = 10_000
        return try {
            c.responseCode
            tagFromRedirect(c.getHeaderField("Location"))
        } finally {
            c.disconnect()
        }
    }

    /** ".../releases/tag/v1.0.189" → "v1.0.189". */
    fun tagFromRedirect(location: String?): String? =
        location?.substringAfter("/releases/tag/", "")?.substringBefore('?')?.takeIf { it.isNotBlank() }

    private const val WEB_LATEST = "https://github.com/rexraja89-oss/XARVISS/releases/latest"

    /** "1.0.46" is newer than "1.0.45"; tags that aren't plain versions ("test-perms") never are. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.').map { it.toIntOrNull() ?: return false }
        val b = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * XARVIS Updater (the small separate app): downloads only what changed — ~0.1–0.5 MB instead of the whole APK
     * (~107 MB on the S22, ~62 MB on the benco). Rex kept tapping UPDATE here, which always fetched the full file
     * (12 Nov 2026), so UPDATE opens the Updater when it's installed and offers it once when it isn't.
     */
    const val UPDATER_PACKAGE = "com.xarvis.updater"
    private const val UPDATER_APK = DOWNLOAD + "XARVIS-Updater.apk"

    fun updaterInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(UPDATER_PACKAGE, 0); true }.getOrDefault(false)

    /**
     * Opens XARVIS Updater (it finds the small update itself); false if it isn't installed. [updateAll]: it updates
     * XARVIS, XARVIS Remote and itself at once as soon as it has looked (13 Nov 2026, Rex: "update all at one time";
     * an Updater from before that just opens).
     */
    fun openUpdater(context: Context, updateAll: Boolean = false): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(UPDATER_PACKAGE) ?: return false
        if (updateAll) {
            launch.putExtra("auto", true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }

    private const val UPDATER_SERVICE = "com.xarvis.updater.UpdateService"
    private const val MSG_UPDATE_ALL = 1
    private const val MSG_LINE = 2
    private const val MSG_DONE = 3

    /**
     * "Update everything" with no screen, by XARVIS Updater's background service (Rex on the other phone: "update my
     * S22"): [onLine] gets its milestones, [onEnd] the last line, both on the main thread. XARVIS is replaced on the
     * way (then this process ends and [onEnd] never comes — XARVIS reports after its restart, see XarvisCore). Returns
     * why it can't start, or null.
     */
    fun updateAllQuietly(context: Context, onLine: (String) -> Unit, onEnd: (String) -> Unit): String? {
        if (!updaterInstalled(context)) return "XARVIS Updater isn't installed on this phone yet"
        val app = context.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        var ended = false
        var conn: android.content.ServiceConnection? = null
        fun end(text: String) {
            if (ended) return
            ended = true
            conn?.let { c -> runCatching { app.unbindService(c) } }
            onEnd(text)
        }
        val replies = android.os.Messenger(object : android.os.Handler(android.os.Looper.getMainLooper()) {
            override fun handleMessage(msg: android.os.Message) {
                val text = msg.data?.getString("text") ?: return
                when (msg.what) {
                    MSG_LINE -> if (!ended) onLine(text)
                    MSG_DONE -> end(text)
                }
            }
        })
        val c = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName, binder: android.os.IBinder) {
                runCatching {
                    android.os.Messenger(binder).send(android.os.Message.obtain(null, MSG_UPDATE_ALL).apply { replyTo = replies })
                }.onFailure { end("⚠ XARVIS Updater didn't answer (${it.message}). Nothing changed.") }
            }

            override fun onServiceDisconnected(name: android.content.ComponentName) {
                // The Updater replacing itself (always its last step) ends its process.
                main.postDelayed({ end("🏁 Update finished — the Updater restarted itself (its own update is the last step). Now: ${versions(app)}.") }, 15_000)
            }

            override fun onBindingDied(name: android.content.ComponentName) = onServiceDisconnected(name)
        }
        conn = c
        val intent = Intent().setComponent(android.content.ComponentName(UPDATER_PACKAGE, UPDATER_SERVICE))
        val flags = Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT or
            (if (android.os.Build.VERSION.SDK_INT >= 29) Context.BIND_INCLUDE_CAPABILITIES else 0)
        val bound = runCatching { app.bindService(intent, c, flags) }.getOrDefault(false)
        if (!bound) {
            runCatching { app.unbindService(c) }
            return "its XARVIS Updater is too old for that — update the Updater there once by hand (open it, tap Update)"
        }
        main.postDelayed({ end("⚠ The update took over 15 minutes, so I stopped waiting. Now: ${versions(app)}.") }, 15 * 60_000L)
        return null
    }

    /** "XARVIS v1.0.282 · Remote v1.0.280 · Updater v1.0.282" — what's installed here. */
    fun versions(context: Context): String = listOf(
        "XARVIS" to "com.xarvis.ai", "Remote" to "com.xarvis.remote", "Updater" to UPDATER_PACKAGE,
    ).mapNotNull { (name, pkg) ->
        runCatching { "$name v" + context.packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull()
    }.joinToString(" · ")

    /** Downloads XARVIS Updater (1.5 MB) in Chrome, to install once. */
    fun getUpdater(context: Context): Boolean = openInBrowser(context, UPDATER_APK)

    /** Opens the latest APK in Chrome (or the default browser if Chrome is missing): the full download. */
    fun openDownload(context: Context): Boolean = openInBrowser(context, apkUrl)

    private fun openInBrowser(context: Context, url: String): Boolean {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        for (pkg in listOf("com.android.chrome", null)) {
            try {
                context.startActivity(if (pkg != null) Intent(view).setPackage(pkg) else view)
                return true
            } catch (e: Exception) {
                // try the next browser
            }
        }
        return false
    }
}
