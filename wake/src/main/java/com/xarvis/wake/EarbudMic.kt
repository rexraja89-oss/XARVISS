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

    /** A phone call is ringing or going on (Android owns the earbud then). */
    fun inCall(c: Context): Boolean = runCatching { audio(c)!!.mode != AudioManager.MODE_NORMAL }.getOrDefault(false)

    /** Something loud of the phone's own is playing that drowns a voice: an alarm or a ringtone. */
    fun alarmOrRinging(c: Context): Boolean = runCatching {
        val a = audio(c)!!
        a.mode == AudioManager.MODE_RINGTONE || a.activePlaybackConfigurations.any {
            val u = it.audioAttributes.usage
            u == AudioAttributes.USAGE_ALARM || u == AudioAttributes.USAGE_NOTIFICATION_RINGTONE
        }
    }.getOrDefault(false)

    fun wanted(c: Context, allowed: Boolean): Boolean = allowed && connected(c) && !mediaPlaying(c) && !inCall(c)

    /** Switches the earbud mic on; true once it's ready (≤ 2 s). */
    @Suppress("DEPRECATION")
    fun on(c: Context): Boolean {
        val a = audio(c) ?: return false
        if (active && a.isBluetoothScoOn) return true
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
            a.isBluetoothScoOn = false
            a.stopBluetoothSco()
        }
    }
}
