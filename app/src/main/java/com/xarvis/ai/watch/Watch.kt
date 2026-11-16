package com.xarvis.ai.watch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Watch mode, from either phone (Rex, 16 Nov 2026): the S22 watches the room through its front camera with the screen
 * off and tells the benco when something moves or changes. Silent on the S22 by Rex's choice; auto-stops after the
 * hours asked (8 by default). The camera service exists only in the S22 build (src/full); the benco asks the S22.
 */
object Watch {
    private const val SERVICE = "com.xarvis.ai.watch.WatchService"

    /** This phone can watch: the S22 build, a camera, and the camera permission granted. */
    fun available(c: Context): Boolean =
        runCatching { Class.forName(SERVICE); true }.getOrDefault(false) &&
            c.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)

    private fun allowed(c: Context) = c.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun prefs(c: Context) = c.getSharedPreferences("watch", Context.MODE_PRIVATE)

    fun watching(c: Context): Boolean = prefs(c).getLong("until", 0) > System.currentTimeMillis()

    fun handle(c: Context, cmd: WatchCommands.Cmd): String = when (cmd) {
        is WatchCommands.Cmd.Start -> start(c, cmd.hours)
        is WatchCommands.Cmd.Stop -> stop(c)
        is WatchCommands.Cmd.Snap -> snap(c)
        is WatchCommands.Cmd.Status -> status(c)
    }

    fun handle(c: Context, op: String, hours: Int): String = when (op) {
        "start" -> start(c, hours.takeIf { it > 0 } ?: WatchCommands.DEFAULT_HOURS)
        "stop" -> stop(c)
        "snap" -> snap(c)
        else -> status(c)
    }

    fun start(c: Context, hours: Int): String {
        if (!available(c)) return "Watch mode needs the S22's front camera, sir."
        if (!allowed(c)) return "I need the camera permission first, sir: open XARVIS Eyes once on the S22 and allow the camera."
        val h = hours.coerceIn(1, WatchCommands.MAX_HOURS)
        val until = System.currentTimeMillis() + h * 3_600_000L
        prefs(c).edit().putLong("until", until).apply()
        return runCatching {
            c.startForegroundService(Intent().setClassName(c, SERVICE).putExtra("hours", h))
            "👁 Watching the room for $h hour${if (h == 1) "" else "s"}, sir. Put the S22 on a stand with the front camera facing the room; the screen can go off. I'll stay silent here and tell your other phone, with a photo, the moment anything moves or changes."
        }.getOrElse {
            prefs(c).edit().remove("until").apply()
            "Android didn't let me start the camera from the background, sir. Open XARVIS on the S22 once (or keep XARVIS Hands on) and ask again."
        }
    }

    fun stop(c: Context): String {
        val was = watching(c)
        prefs(c).edit().remove("until").apply()
        if (available(c)) runCatching { c.startService(Intent().setClassName(c, SERVICE).setAction("com.xarvis.ai.watch.STOP")) }
        return if (was) "Watch mode off, sir." else "I wasn't watching, sir."
    }

    fun snap(c: Context): String {
        if (!available(c)) return "Watch mode needs the S22's front camera, sir."
        if (!watching(c)) return "I'm not watching right now, sir. Say \"watch the room\" first."
        runCatching { c.startService(Intent().setClassName(c, SERVICE).setAction("com.xarvis.ai.watch.SNAP")) }
        return "📷 Taking a photo of the room — it's on its way."
    }

    fun status(c: Context): String {
        val until = prefs(c).getLong("until", 0)
        return if (until > System.currentTimeMillis()) {
            "👁 Watching the room until " + java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(until)) + ", sir."
        } else "Not watching, sir."
    }
}
