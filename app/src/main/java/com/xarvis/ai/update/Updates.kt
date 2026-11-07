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

    /** Opens the latest APK in Chrome (or the default browser if Chrome is missing). */
    fun openDownload(context: Context): Boolean {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
