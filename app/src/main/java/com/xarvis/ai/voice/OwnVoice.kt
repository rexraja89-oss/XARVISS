package com.xarvis.ai.voice

import android.util.Log
import com.k2fsa.sherpa.onnx.VersionInfo

/**
 * The engine for Rex's own voice (sherpa-onnx, offline, runs Piper voice models on the phone).
 * For now it only checks the engine loads; the voice trained from Rex's recordings plugs in here.
 */
object OwnVoice {
    /** "ready (1.13.8)" when the engine loads on this phone, else why not. */
    val status: String by lazy {
        try {
            "ready (${VersionInfo.version})"
        } catch (e: Throwable) {
            Log.w("XarvisOwnVoice", "Voice engine didn't load", e)
            "not working (${e.javaClass.simpleName})"
        }
    }
}
