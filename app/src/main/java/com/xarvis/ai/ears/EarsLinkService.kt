package com.xarvis.ai.ears

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.xarvis.ai.XarvisApp
import com.xarvis.ai.service.WakeWord

/**
 * The private line between the small XARVIS Ears app and XARVIS (Nov 2026; Rex: "respond anytime,
 * anywhere when I say Xarvis… even when my phone is in sleep mode or screen off"). Ears holds the
 * microphone — the benco refuses to install XARVIS with it — hears "Xarvis" and Rex's words, and
 * hands them over here; XARVIS thinks and answers aloud, and tells Ears when to listen again. Only
 * Rex's own same-key apps can connect (the EYES signature permission in the manifest).
 *
 * Ears → XARVIS: [MSG_HELLO] (with its reply address), [MSG_WAKE] ("Xarvis" was heard; arg1 = 1 when it
 * interrupted XARVIS talking), [MSG_HEARD] (Rex's words in [KEY_TEXT]), [MSG_OVER] (he said nothing more).
 * XARVIS → Ears: [MSG_STATE] (arg1 = what XARVIS is doing, see VoiceTalk; arg2 = 1 when this XARVIS listens by
 * itself, so Ears should stand down) and [MSG_DONE] (the answer to a WAKE or HEARD carrying the same arg2 number:
 * XARVIS has finished speaking; arg1 = 1 to keep listening).
 */
class EarsLinkService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var client: Messenger? = null
    private var lastState = -1

    private val incoming = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val core = (application as XarvisApp).core
            when (msg.what) {
                MSG_HELLO -> {
                    client = msg.replyTo
                    EarsLink.connected = client != null
                    lastState = -1
                    main.removeCallbacks(poll)
                    main.post(poll)
                }
                MSG_WAKE -> {
                    val number = msg.arg2
                    core.voiceWake(msg.arg1 == 1) { send(MSG_DONE, 1, number) }
                }
                MSG_HEARD -> {
                    val number = msg.arg2
                    val text = msg.data?.getString(KEY_TEXT).orEmpty()
                    core.voiceHeard(text) { more -> send(MSG_DONE, if (more) 1 else 0, number) }
                }
                MSG_OVER -> core.voiceOver()
            }
        }
    })

    /** Tells Ears whenever what XARVIS is doing changes (thinking, talking, another microphone in use). */
    private val poll = object : Runnable {
        override fun run() {
            if (client == null) return
            val state = (application as XarvisApp).core.voiceState()
            if (state != lastState) {
                lastState = state
                send(MSG_STATE, state, if (WakeWord.available(this@EarsLinkService) && WakeWord.isEnabled(this@EarsLinkService)) 1 else 0)
            }
            main.postDelayed(this, 250)
        }
    }

    private fun send(what: Int, arg1: Int, arg2: Int) {
        val to = client ?: return
        try {
            to.send(Message.obtain(null, what, arg1, arg2))
        } catch (e: Exception) {
            Log.w(TAG, "XARVIS Ears went away", e)
            client = null
            EarsLink.connected = false
        }
    }

    override fun onBind(intent: Intent?): IBinder = incoming.binder

    override fun onUnbind(intent: Intent?): Boolean {
        client = null
        EarsLink.connected = false
        main.removeCallbacks(poll)
        return false
    }

    companion object {
        private const val TAG = "XarvisEarsLink"
        const val MSG_HELLO = 1
        const val MSG_WAKE = 2
        const val MSG_HEARD = 3
        const val MSG_OVER = 4
        const val MSG_STATE = 10
        const val MSG_DONE = 11
        const val KEY_TEXT = "text"
    }
}

/** XARVIS's side of XARVIS Ears: is it on this phone, is it connected, wake it up, open it. */
object EarsLink {
    const val PACKAGE = "com.xarvis.ears"
    private const val SERVICE = "com.xarvis.ears.EarsService"
    private const val ACTION_KICK = "com.xarvis.ears.KICK"

    /** XARVIS Ears is connected to this XARVIS right now (it's running and listening, or talking with Rex). */
    @Volatile var connected = false

    fun installed(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    /**
     * Asks XARVIS Ears to listen, if it's on this phone and Rex has it switched on. Called when XARVIS comes on
     * screen (and by XARVIS Hands on the S22): Android only gives a background app the microphone when a visible
     * app — or an accessibility app — starts it, so this is what brings the ears back after a phone restart.
     */
    fun kick(context: Context) {
        if (!installed(context)) return
        try {
            val intent = Intent(ACTION_KICK).setComponent(ComponentName(PACKAGE, SERVICE))
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            Log.w("XarvisEarsLink", "Couldn't wake XARVIS Ears", e)
        }
    }

    /** Opens XARVIS Ears' screen; false when it isn't on this phone. */
    fun open(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
        return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }
}
