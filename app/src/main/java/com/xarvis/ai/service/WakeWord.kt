package com.xarvis.ai.service

import android.content.Context
import android.content.Intent

/**
 * The "Say Xarvis" switch (XARVIS listening by itself, on the S22), and a pause for when XARVIS's
 * other microphones are listening. On a phone whose XARVIS may not use the microphone (the benco)
 * the small XARVIS Ears app listens instead and talks to XARVIS through ears/EarsLinkService.
 */
object WakeWord {
    /** Set while the mic button or the voice screen is listening, so they get the mic. */
    @Volatile var paused = false

    /** Set while Guard mode's alarm listens for "It's alright XARVIS" (its "XARVIS" would wake the wake word). */
    @Volatile var guardHold = false

    /**
     * Whether this XARVIS can listen by itself: it needs the microphone permission in its build (the benco's has
     * none), and from Android 14 also FOREGROUND_SERVICE_MICROPHONE, which XARVIS leaves out because the S22
     * refuses to install it together with RECORD_AUDIO.
     */
    fun available(context: Context): Boolean = runCatching {
        val asked = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
        android.Manifest.permission.RECORD_AUDIO in asked &&
            (android.os.Build.VERSION.SDK_INT < 34 || "android.permission.FOREGROUND_SERVICE_MICROPHONE" in asked)
    }.getOrDefault(false)

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("wakeWord", false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("wakeWord", on).apply()
        if (on) WakeWordService.start(context) else WakeWordService.stop(context)
        (context.applicationContext as com.xarvis.ai.XarvisApp).core.wakeWordChanged(on)
    }

    /**
     * Starts this XARVIS's own ears if Rex has them switched on and they can run here (S22: the microphone
     * permission granted). Safe to call from anywhere; see WakeWordService for when Android gives it the microphone.
     */
    fun startIfOn(context: Context) {
        if (isEnabled(context) && available(context) &&
            com.xarvis.ai.tools.PermissionGate.has(context, android.Manifest.permission.RECORD_AUDIO)
        ) WakeWordService.start(context)
    }

    /** The wake word screen (status, practice, sensitivity) of the S22's own ears. */
    fun openScreen(context: Context) {
        runCatching {
            context.startActivity(
                Intent().setClassName(context, "com.xarvis.wake.WakeScreen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
