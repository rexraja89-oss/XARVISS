package com.xarvis.ai.hands

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.xarvis.ai.BuildConfig
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * XARVIS Hands, built in. On the S22 build ("full") XARVIS has its own Accessibility service
 * ([XarvisHandsService] in src/full) that taps inside other apps — the S22 accepted it in the main app
 * in Nov 2026 (it refused in Oct). The service registers itself here; the rest of the app only talks
 * to this bridge. The benco build has no service, so [builtIn] is false and calls go to the separate
 * XARVIS Hands app as before.
 */
object HandsBridge {
    /** What the built-in service can do. */
    interface Hands {
        fun startCallInApp(app: String, contact: String, video: Boolean)
        fun cancelTask()
    }

    /** Whether this build has Hands inside XARVIS (the S22's). */
    val builtIn: Boolean = BuildConfig.FLAVOR == "full"

    /** The connected service, or null while it's switched off. */
    @Volatile var service: Hands? = null

    /** A call (app, contact, video) asked for while Hands was off: it runs when Rex switches it on. */
    @Volatile var pendingCall: Triple<String, String, Boolean>? = null

    fun isRunning(): Boolean = service != null

    /** Whether XARVIS's own Accessibility service is switched on in Settings (any app can read this). */
    fun switchedOn(context: Context): Boolean = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().contains("${context.packageName}/", ignoreCase = true)
    }.getOrDefault(false)

    /** A live log of what Hands did, newest last (shown on ☰ → XARVIS Hands). */
    val logLines: MutableList<String> = Collections.synchronizedList(ArrayList())

    fun log(m: String) {
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + m
        synchronized(logLines) {
            logLines.add(line)
            while (logLines.size > 60) logLines.removeAt(0)
        }
        Log.i("XarvisHands", m)
    }
}
