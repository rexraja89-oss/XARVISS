package com.xarvis.remote

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub for a newer XARVIS Remote and opens its APK in Chrome to install — the same
 * one-tap update the main XARVIS app has, so nobody has to find the download link again.
 * Kept dependency-free (no org.json / coroutines): a plain HTTP GET parsed with a regex.
 */
object Updates {
    private const val LATEST = "https://api.github.com/repos/rexraja89-oss/XARVISS/releases/latest"
    const val APK_URL = "https://github.com/rexraja89-oss/XARVISS/releases/latest/download/XARVIS-Remote.apk"

    /**
     * The newest XARVIS Remote version on GitHub ("1.0.134") if it's newer than [current] and something in it changed
     * ([ownSrc] = this build's source fingerprint vs the one in that release's patches.json); else null/offline.
     */
    fun newerVersion(current: String, ownSrc: String? = null): String? = runCatching {
        val c = URL(LATEST).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 10_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "XARVIS-Remote")
        val body = try {
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
        val tag = Regex(""""tag_name"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1) ?: return@runCatching null
        val newer = tag.removePrefix("v").takeIf { isNewer(it, current) } ?: return@runCatching null
        if (!ownSrc.isNullOrBlank() && releasedSrc(tag) == ownSrc) null else newer
    }.getOrNull()

    /** XARVIS-Remote.apk's source fingerprint in [tag]'s patches.json (made ~10 min after a release), or null. */
    private fun releasedSrc(tag: String): String? = runCatching {
        val c = URL("https://github.com/rexraja89-oss/XARVISS/releases/download/$tag/patches.json").openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "XARVIS-Remote")
        val body = try {
            if (c.responseCode != 200) return@runCatching null
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
        Regex(""""XARVIS-Remote\.apk"\s*:\s*\{[^}]*"src"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
    }.getOrNull()

    /** This build's source fingerprint (manifest meta-data "xarvis.src"). */
    fun ownSrc(context: Context): String? = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getApplicationInfo(context.packageName, android.content.pm.PackageManager.GET_META_DATA)
            .metaData?.getString("xarvis.src")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** "1.0.134" is newer than "1.0.133"; tags that aren't plain versions never are. */
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

    /** Opens the latest XARVIS Remote APK in Chrome (or the default browser if Chrome is missing). */
    fun openDownload(context: Context): Boolean {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(APK_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
