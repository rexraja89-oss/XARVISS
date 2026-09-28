package com.xarvis.ai.voice

import android.content.Context
import java.io.Closeable

/**
 * The benco build has no "Hey Jarvis": the benco refuses to install XARVIS with the microphone
 * permission and TensorFlow Lite (the full build's detector is in src/full). Never constructed,
 * since WakeWord.available is false without the permission.
 */
class WakeWordDetector(@Suppress("UNUSED_PARAMETER") context: Context) : Closeable {
    init {
        throw UnsupportedOperationException("\"Hey Jarvis\" isn't in the benco build")
    }

    fun reset() {}

    fun process(@Suppress("UNUSED_PARAMETER") chunk: ShortArray): Float = 0f

    override fun close() {}

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK = 1280
        const val THRESHOLD = 0.5f
    }
}
