package com.xarvis.ai.workflow

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Best-effort handsfree routing for a call XARVIS just placed: send it to a connected Bluetooth
 * headset if there is one, otherwise the loudspeaker. The system ultimately owns a cellular call's
 * audio route, so this can't be guaranteed — a Bluetooth headset is routed to automatically anyway;
 * the speaker is a best effort and Rex may still need one tap. We're honest about that to him.
 */
object CallAudio {

    /** Route the current call handsfree. Returns where to ("your Bluetooth earpiece"/"speaker") or "". */
    fun routeHandsfree(context: Context): String {
        val am = context.getSystemService(AudioManager::class.java) ?: return ""
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = am.availableCommunicationDevices
                val bt = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                val speaker = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                when {
                    bt != null -> { am.setCommunicationDevice(bt); "your Bluetooth earpiece" }
                    speaker != null -> { am.setCommunicationDevice(speaker); "speaker" }
                    else -> ""
                }
            } else {
                @Suppress("DEPRECATION")
                if (am.isBluetoothScoAvailableOffCall) {
                    am.startBluetoothSco(); am.isBluetoothScoOn = true; "your Bluetooth earpiece"
                } else {
                    am.isSpeakerphoneOn = true; "speaker"
                }
            }
        } catch (e: Exception) {
            ""
        }
    }
}
