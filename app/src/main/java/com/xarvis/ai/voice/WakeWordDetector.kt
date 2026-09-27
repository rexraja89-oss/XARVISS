package com.xarvis.ai.voice

import android.content.Context
import java.io.Closeable

/** TEST build without TensorFlow Lite: never hears the wake word. */
class WakeWordDetector(context: Context) : Closeable {
    fun reset() {}
    fun process(chunk: ShortArray): Float = 0f
    override fun close() {}
    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK = 1280
        const val THRESHOLD = 0.5f
    }
}
