package com.xarvis.updater

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** GitHub downloads: plain HTTPS, no sign-in, no API (its 60-an-hour limit stopped XARVIS's update check once). */
object Net {
    private const val REPO = "https://github.com/rexraja89-oss/XARVISS"

    class NotFound(message: String) : IOException(message)

    fun assetUrl(tag: String, name: String) = "$REPO/releases/download/$tag/$name"

    /** The newest release's tag ("v1.0.263"): /releases/latest redirects to /releases/tag/<tag>. */
    fun latestTag(): String {
        val c = open("$REPO/releases/latest")
        c.instanceFollowRedirects = false
        return try {
            c.responseCode
            Release.tagFromRedirect(c.getHeaderField("Location")) ?: throw IOException("GitHub didn't say which release is newest")
        } finally {
            c.disconnect()
        }
    }

    /** A small text file, or null if the release doesn't have it (yet). */
    fun text(url: String): String? {
        val c = open(url)
        return try {
            when (val code = c.responseCode) {
                200 -> c.inputStream.bufferedReader().use { it.readText() }
                404 -> null
                else -> throw IOException("GitHub answered $code")
            }
        } finally {
            c.disconnect()
        }
    }

    /**
     * Downloads [url] into [to], resuming where a broken download stopped (mobile data drops), up to 4 tries.
     * [progress] gets (bytes so far, total or -1).
     */
    fun download(url: String, to: File, progress: (Long, Long) -> Unit): File {
        var last: IOException? = null
        for (attempt in 0 until 4) {
            try {
                fetch(url, to, progress)
                return to
            } catch (e: NotFound) {
                throw e
            } catch (e: IOException) {
                last = e
                Thread.sleep(2_000L * (attempt + 1))
            }
        }
        throw last ?: IOException("download failed")
    }

    private fun fetch(url: String, to: File, progress: (Long, Long) -> Unit) {
        val have = if (to.exists()) to.length() else 0L
        val c = open(url)
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        try {
            val code = c.responseCode
            if (code == 404) throw NotFound("GitHub doesn't have that file")
            if (code == 416) return // already complete
            if (code != 200 && code != 206) throw IOException("GitHub answered $code")
            val append = code == 206
            val start = if (append) have else 0L
            val length = c.contentLengthLong
            val total = if (length >= 0) start + length else -1L
            var done = start
            c.inputStream.use { input ->
                FileOutputStream(to, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport >= 256 * 1024) {
                            lastReport = done
                            progress(done, total)
                        }
                    }
                }
            }
            if (total >= 0 && done != total) throw IOException("the download stopped early")
            progress(done, total)
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "XARVIS-Updater")
    }
}
