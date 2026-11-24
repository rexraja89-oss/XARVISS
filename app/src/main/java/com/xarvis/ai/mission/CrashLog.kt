package com.xarvis.ai.mission

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps XARVIS's last few crashes (what broke, where) in its own folder, so a mission can send them to Claude instead
 * of Rex describing "it closed by itself". Only XARVIS's own errors; nothing personal is written.
 */
object CrashLog {
    private const val KEEP = 5

    fun install(context: Context) {
        val dir = File(context.filesDir, "crashes")
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                dir.mkdirs()
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                File(dir, "crash-${System.currentTimeMillis()}.txt").writeText(
                    "XARVIS ${com.xarvis.ai.BuildConfig.VERSION_NAME} (${com.xarvis.ai.BuildConfig.FLAVOR}) · $stamp · thread ${thread.name}\n${trace.take(20_000)}",
                )
                dir.listFiles()?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
            }
            before?.uncaughtException(thread, error)
        }
    }

    /** The saved crashes, newest first; "" when there are none. */
    fun read(context: Context): String =
        File(context.filesDir, "crashes").listFiles()?.sortedByDescending { it.name }
            ?.joinToString("\n\n---\n\n") { runCatching { it.readText() }.getOrDefault("") } ?: ""
}
