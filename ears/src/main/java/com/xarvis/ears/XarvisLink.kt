package com.xarvis.ears

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.xarvis.wake.WakeHost

/**
 * Ears' private line to XARVIS (its ears/EarsLinkService; only Rex's own same-key apps can connect):
 * Ears says "Xarvis was heard" and what Rex said; XARVIS answers aloud and says when to listen
 * again, and keeps Ears told what it's doing so Ears knows when to stay off the microphone.
 * Connecting starts XARVIS if it isn't running. Use it on the main thread.
 */
class XarvisLink(context: Context) : WakeHost {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var out: Messenger? = null
    private var binding = false
    private var number = 0
    private val waiting = HashMap<Int, (Boolean) -> Unit>()

    @Volatile private var state = WakeHost.IDLE

    /** XARVIS answered the connection. */
    @Volatile var connected = false
        private set

    /** This phone's XARVIS listens for "Xarvis" itself (the S22's build with the switch on): Ears isn't needed. */
    @Volatile var xarvisHasEars = false
        private set

    /** Called (main thread) when [connected] or [xarvisHasEars] changes. */
    var onChange: (() -> Unit)? = null

    private val incoming = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_STATE -> {
                    state = msg.arg1
                    val own = msg.arg2 == 1
                    if (own != xarvisHasEars) {
                        xarvisHasEars = own
                        onChange?.invoke()
                    }
                }
                MSG_DONE -> waiting.remove(msg.arg2)?.invoke(msg.arg1 == 1)
            }
        }
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            out = service?.let { Messenger(it) }
            connected = out != null
            send(Message.obtain(null, MSG_HELLO).apply { replyTo = incoming })
            onChange?.invoke()
        }

        override fun onServiceDisconnected(name: ComponentName?) = dropped() // XARVIS stopped or is updating; Android reconnects

        override fun onBindingDied(name: ComponentName?) {
            dropped()
            disconnect()
            main.postDelayed({ connect() }, 3000)
        }
    }

    /** XARVIS went away: nothing it owed us is coming, and nothing says it's busy. */
    private fun dropped() {
        out = null
        connected = false
        state = WakeHost.IDLE
        val owed = waiting.values.toList()
        waiting.clear()
        owed.forEach { it(false) }
        onChange?.invoke()
    }

    /** Connects to XARVIS (starting it if needed); false when XARVIS isn't on this phone. */
    fun connect(): Boolean {
        if (binding) return true
        binding = try {
            appContext.bindService(
                Intent().setComponent(ComponentName(XARVIS, LINK_SERVICE)), connection, Context.BIND_AUTO_CREATE,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't connect to XARVIS", e)
            false
        }
        if (!binding) runCatching { appContext.unbindService(connection) }
        return binding
    }

    fun disconnect() {
        if (binding) runCatching { appContext.unbindService(connection) }
        binding = false
        out = null
        connected = false
        state = WakeHost.IDLE
        waiting.clear()
    }

    private fun send(msg: Message): Boolean {
        val to = out ?: return false
        return try {
            to.send(msg)
            true
        } catch (e: Exception) {
            Log.w(TAG, "XARVIS didn't take the message", e)
            false
        }
    }

    override fun state(): Int = if (connected) state else WakeHost.IDLE

    override fun wake(interrupting: Boolean, done: (Boolean) -> Unit) {
        connect()
        // XARVIS may have been asleep: give it a few seconds to wake up and connect.
        whenConnected(tries = 30) { ok ->
            if (!ok) {
                done(false)
                return@whenConnected
            }
            val n = ++number
            waiting[n] = { done(true) }
            if (!send(Message.obtain(null, MSG_WAKE, if (interrupting) 1 else 0, n))) {
                waiting.remove(n)
                done(false)
            }
        }
    }

    override fun heard(text: String, done: (Boolean) -> Unit) {
        val n = ++number
        waiting[n] = done
        val msg = Message.obtain(null, MSG_HEARD, 0, n).apply { data = Bundle().apply { putString(KEY_TEXT, text) } }
        if (!send(msg)) {
            waiting.remove(n)
            done(false)
        }
    }

    override fun over() {
        send(Message.obtain(null, MSG_OVER))
    }

    private fun whenConnected(tries: Int, then: (Boolean) -> Unit) {
        when {
            connected -> then(true)
            tries <= 0 -> then(false)
            else -> main.postDelayed({ whenConnected(tries - 1, then) }, 300)
        }
    }

    companion object {
        private const val TAG = "XarvisEarsLink"
        const val XARVIS = "com.xarvis.ai"
        private const val LINK_SERVICE = "com.xarvis.ai.ears.EarsLinkService"

        // The same numbers as XARVIS's ears/EarsLinkService.
        private const val MSG_HELLO = 1
        private const val MSG_WAKE = 2
        private const val MSG_HEARD = 3
        private const val MSG_OVER = 4
        private const val MSG_STATE = 10
        private const val MSG_DONE = 11
        private const val KEY_TEXT = "text"

        fun xarvisInstalled(context: Context): Boolean =
            runCatching { context.packageManager.getPackageInfo(XARVIS, 0) }.isSuccess
    }
}
