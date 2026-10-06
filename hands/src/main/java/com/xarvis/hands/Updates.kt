package com.xarvis.hands

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub for a newer XARVIS Hands and opens its APK in Chrome to install — the same
 * one-tap update the main XARVIS app has, so Rex doesn't have to type the download link.
 * Kept dependency-free (no org.json / coroutines): a plain HTTP GET parsed with a regex.
 */
object Updates {
    private const val LATEST = "https://api.github.com/repos/rexraja89-oss/XARVISS/releases/latest"
    const val APK_URL = "https://github.com/rexraja89-oss/XARVISS/releases/latest/download/XARVIS-Hands.apk"

    /** The newest Hands version on GitHub ("1.0.134") if it's newer than [current]; else null/offline. */
    fun newerVersion(current: String): String? = runCatching {
        val c = URL(LATEST).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 10_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "XARVIS-Hands")
        val body = try {
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
        val tag = Regex(""""tag_name"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1) ?: return@runCatching null
        tag.removePrefix("v").takeIf { isNewer(it, current) }
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

    /** Opens the latest Hands APK in Chrome (or the default browser if Chrome is missing). */
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
