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

        // ---- remote control by the linked phone (ScreenHost) ----
        /** A picture of the whole screen (null if Android refused, e.g. asked again within a third of a second). */
        fun screenshot(onFrame: (android.graphics.Bitmap?) -> Unit)
        /** "tap" / "long" at ([x],[y]), or "swipe" to ([x2],[y2]) over [ms]; screen pixels. */
        fun gesture(kind: String, x: Float, y: Float, x2: Float, y2: Float, ms: Long): Boolean
        /** "back", "home", "recents", "notifications", "quick", "lock". */
        fun globalAction(name: String): Boolean
        /** Types [text] into the field that has the cursor (and presses Enter if [enter]). */
        fun typeText(text: String, enter: Boolean): Boolean
        /** The "being controlled by …" banner on this screen; null hides it. Its STOP calls [onStop]. */
        fun controlBanner(by: String?, onStop: () -> Unit)

        /** A live finger from the linked phone, in screen pixels: down, then moves, then up (scrolls as you drag). */
        fun touchDown(x: Float, y: Float)
        fun touchMove(x: Float, y: Float)
        fun touchUp(x: Float, y: Float)

        // ---- XARVIS acting on this phone for the linked one ("open WhatsApp on my S22 and read…") ----
        /** The text on the screen right now, top to bottom, with the app's name first. */
        fun screenText(): String
        /** Taps the visible thing labelled [label] (never payments, passwords, OTPs…); whether it did. */
        fun tapText(label: String): Boolean
        /** The selected text in the field with the cursor (or all of it); null if none. */
        fun selectedText(): String?
        /**
         * Where a lock pattern's dots / PIN keys are on screen [w]×[h] (LockPad JSON), or null: Android hides those
         * screens from pictures, not from Accessibility, so the viewer can draw them for Rex. Positions only.
         */
        fun lockPad(w: Int, h: Int): String? = null
        /** Opens the app [packageName] (Hands may start apps from the background; XARVIS itself may not). */
        fun openApp(packageName: String): Boolean

        /** Opens a web address in the browser (Task mode); false where it can't. */
        fun openUrl(url: String): Boolean = false

        // ---- XARVIS using the phone by itself (PhoneAgent) ----
        /**
         * What's on the screen, for the brain: JSON {"app","w","h","els":[{"t":label,"c":tappable,"e":editable,
         * "p":password,"x","y" (centre, px)}]}, top to bottom.
         */
        fun screenElements(): String
        /** A yes/no question shown on top of whatever app is open; [onAnswer] gets Rex's tap (no after 60 s). */
        fun ask(question: String, onAnswer: (Boolean) -> Unit)
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
