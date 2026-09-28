package com.xarvis.ai.voice

import android.content.Context
import android.net.Uri

/**
 * The benco build has no own-voice engine (sherpa-onnx is left out to keep the package small
 * enough for the benco to accept); XARVIS uses the phone's voices there. The full build's
 * engine is in src/full.
 */
object OwnVoice {
    val status: String = "not in the benco version"

    fun installed(context: Context, hindi: Boolean) = false

    fun enabled(context: Context) = false

    fun setEnabled(context: Context, on: Boolean) {}

    fun use(context: Context, hindi: Boolean) = false

    fun install(context: Context, uri: Uri): Boolean =
        throw IllegalStateException("your own voice works on the S22's XARVIS, not the benco's")

    fun speak(context: Context, text: String, hindi: Boolean) {}

    fun stop() {}

    val isSpeaking: Boolean get() = false
}
