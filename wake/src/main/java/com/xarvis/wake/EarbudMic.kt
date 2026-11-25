package com.xarvis.wake

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Listening through Bluetooth earbuds (Rex, 15 Nov 2026: in the washroom with his earbud in, the phone in another
 * room with its alarm ringing — "Xarvis" went unheard). Android only lends an earbud's microphone in "call mode"
 * (Bluetooth SCO), which also makes music in the earbuds sound like a phone call. So the earbud mic is used only
 * while earbuds with a microphone are connected and nothing is playing; music or a video starting hands it back.
 */
object EarbudMic {

    @Volatile var active = false
        private set

    private fun audio(c: Context) = c.getSystemService(AudioManager::class.java)

    /** Earbuds with a microphone are connected. */
    fun connected(c: Context): Boolean = runCatching {
        audio(c)!!.getDevices(AudioManager.GET_DEVICES_INPUTS).any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    }.getOrDefault(false)

    /** Music, a video or a game is playing (an alarm or ringtone isn't: the earbud mic is exactly what helps then). */
    fun mediaPlaying(c: Context): Boolean = runCatching {
        audio(c)!!.activePlaybackConfigurations.any {
            val u = it.audioAttributes.usage
            u == AudioAttributes.USAGE_MEDIA || u == AudioAttributes.USAGE_GAME
        }
    }.getOrDefault(false)

    /**
     * A phone call (or a WhatsApp/imo call) is ringing or going on: the earbuds belong to it. Our own earbud link
     * doesn't count — it can put the phone in "communication" mode itself, which used to make XARVIS let go and grab
     * again every few seconds (Rex, 15 Nov 2026: the call kept connecting and disconnecting on his Bluetooth).
     */
    fun inCall(c: Context): Boolean = runCatching {
        when (audio(c)!!.mode) {
            AudioManager.MODE_NORMAL -> false
            AudioManager.MODE_IN_COMMUNICATION -> !active
            else -> true
        }
    }.getOrDefault(false)

    /** Something loud of the phone's own is playing that drowns a voice: an alarm or a ringtone. */
    fun alarmOrRinging(c: Context): Boolean = runCatching {
        val a = audio(c)!!
        a.mode == AudioManager.MODE_RINGTONE || a.activePlaybackConfigurations.any {
            val u = it.audioAttributes.usage
            u == AudioAttributes.USAGE_ALARM || u == AudioAttributes.USAGE_NOTIFICATION_RINGTONE
        }
    }.getOrDefault(false)

    /** Not for a while after a call: going back and forth on the earbuds is what disturbs a call. */
    @Volatile private var quietUntil = 0L

    /**
     * The phone is being used (screen on). Then the earbuds stay normal earbuds (Rex, 26 Nov 2026: they were
     * "malfunctioning and getting disturbance" — call mode came and went while he used the phone); the earbud mic is
     * for when the phone lies somewhere else with its screen off.
     */
    fun screenOn(c: Context): Boolean = runCatching {
        c.getSystemService(android.os.PowerManager::class.java)!!.isInteractive
    }.getOrDefault(true)

    fun wanted(c: Context, allowed: Boolean): Boolean {
        if (!allowed || !connected(c) || screenOn(c) || mediaPlaying(c)) return false
        if (inCall(c)) {
            quietUntil = System.currentTimeMillis() + AFTER_CALL_MS
            return false
        }
        return System.currentTimeMillis() >= quietUntil
    }

    /** Switches the earbud mic on; true once it's ready (≤ 2 s). */
    @Suppress("DEPRECATION")
    fun on(c: Context): Boolean {
        val a = audio(c) ?: return false
        if (active && a.isBluetoothScoOn) return true
        // Someone else (a call) already has the earbuds' call link: never take it over.
        if (a.mode != AudioManager.MODE_NORMAL || a.isBluetoothScoOn) return false
        return runCatching {
            a.startBluetoothSco()
            a.isBluetoothScoOn = true
            var waited = 0
            while (!a.isBluetoothScoOn && waited < 2000) { Thread.sleep(100); waited += 100 }
            active = a.isBluetoothScoOn
            active
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    fun off(c: Context) {
        if (!active) return
        active = false
        runCatching {
            val a = audio(c)!!
            // Only drop OUR request. Switching the whole phone's call link off would also pull a real call off
            // the earbuds — that's what made Rex's call connect and disconnect.
            a.stopBluetoothSco()
            if (a.mode == AudioManager.MODE_NORMAL || a.mode == AudioManager.MODE_IN_COMMUNICATION) a.isBluetoothScoOn = false
        }
    }

    private const val AFTER_CALL_MS = 60_000L
}
