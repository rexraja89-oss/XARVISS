package com.xarvis.ai.voice

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Hears "Hey Jarvis" in 16 kHz microphone audio, fully offline, with openWakeWord's three small
 * models (assets/wakeword; the pre-trained models are CC BY-NC-SA, fine for personal use):
 * audio -> mel spectrogram -> speech embedding -> wake-word score. The steps and numbers match
 * openWakeWord's own streaming code, checked against its Python version before shipping.
 */
class WakeWordDetector(context: Context) : Closeable {

    private val mel = Interpreter(asset(context, "melspectrogram.tflite"))
    private val embedding = Interpreter(asset(context, "embedding_model.tflite"))
    private val wakeWord = Interpreter(asset(context, "hey_jarvis_v0.1.tflite"))

    private val raw = FloatArray(WINDOW)
    private var rawFilled = 0
    private val melFrames = ArrayDeque<FloatArray>()
    private val features = ArrayDeque<FloatArray>()

    init {
        mel.resizeInput(0, intArrayOf(1, WINDOW))
        mel.allocateTensors()
        reset()
    }

    /** Forgets what it heard (after a pause, so old sound can't trigger it). */
    fun reset() {
        raw.fill(0f)
        rawFilled = 0
        melFrames.clear()
        repeat(MEL_FRAMES) { melFrames.addLast(FloatArray(MEL_BINS) { 1f }) } // openWakeWord starts with ones
        features.clear()
    }

    /** Takes the next [CHUNK] samples; returns how sure it is (0..1) that "Hey Jarvis" was just said. */
    fun process(chunk: ShortArray): Float {
        require(chunk.size == CHUNK)
        // The window is the new chunk plus 3 hops of the previous audio.
        System.arraycopy(raw, CHUNK, raw, 0, WINDOW - CHUNK)
        for (i in 0 until CHUNK) raw[WINDOW - CHUNK + i] = chunk[i].toFloat()
        rawFilled = minOf(WINDOW, rawFilled + CHUNK)
        if (rawFilled < WINDOW) return 0f

        val melOut = Array(1) { Array(1) { Array(MEL_PER_CHUNK) { FloatArray(MEL_BINS) } } }
        mel.run(arrayOf(raw.copyOf()), melOut)
        for (frame in melOut[0][0]) {
            melFrames.addLast(FloatArray(MEL_BINS) { frame[it] / 10f + 2f })
            if (melFrames.size > MEL_FRAMES) melFrames.removeFirst()
        }

        val embIn = Array(1) { Array(MEL_FRAMES) { i -> Array(MEL_BINS) { j -> floatArrayOf(melFrames[i][j]) } } }
        val embOut = Array(1) { Array(1) { Array(1) { FloatArray(EMBEDDING) } } }
        embedding.run(embIn, embOut)
        features.addLast(embOut[0][0][0])
        if (features.size > FEATURES) features.removeFirst()
        if (features.size < FEATURES) return 0f

        val out = Array(1) { FloatArray(1) }
        wakeWord.run(arrayOf(Array(FEATURES) { features[it] }), out)
        return out[0][0]
    }

    override fun close() {
        mel.close()
        embedding.close()
        wakeWord.close()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        /** 80 ms of audio per step. */
        const val CHUNK = 1280
        const val THRESHOLD = 0.5f
        private const val WINDOW = CHUNK + 160 * 3
        private const val MEL_PER_CHUNK = 8
        private const val MEL_BINS = 32
        private const val MEL_FRAMES = 76
        private const val EMBEDDING = 96
        private const val FEATURES = 16

        private fun asset(context: Context, name: String): ByteBuffer {
            val bytes = context.assets.open("wakeword/$name").use { it.readBytes() }
            return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                put(bytes)
                rewind()
            }
        }
    }
}
