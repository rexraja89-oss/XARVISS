package com.xarvis.wake

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Hears "Xarvis" in 16 kHz microphone audio, fully offline: audio -> mel spectrogram -> speech
 * embedding (openWakeWord's two models, on TensorFlow Lite) -> [WakeModel]'s score. The steps and
 * numbers match openWakeWord's own streaming code, which the detector was trained with.
 */
class WakeDetector(context: Context) : Closeable {

    private val mel = Interpreter(asset(context, "melspectrogram.tflite"))
    private val embedding = Interpreter(asset(context, "embedding_model.tflite"))
    private val model = context.assets.open("$ASSETS/xarvis.bin").use { WakeModel.read(it) }

    private val raw = FloatArray(WINDOW)
    private var rawFilled = 0
    private val melFrames = ArrayDeque<FloatArray>()
    private val features = ArrayDeque<FloatArray>()

    /** What a quiet room looks like to the models: the two seconds of history to start from after a pause. */
    private val quietMel: List<FloatArray>
    private val quietFeatures: List<FloatArray>

    init {
        mel.resizeInput(0, intArrayOf(1, WINDOW))
        mel.allocateTensors()
        repeat(MEL_FRAMES) { melFrames.addLast(FloatArray(MEL_BINS) { 1f }) } // openWakeWord starts with ones
        val random = java.util.Random(7)
        val hush = ShortArray(CHUNK)
        repeat(FEATURES + 12) {
            for (i in hush.indices) hush[i] = (random.nextInt(41) - 20).toShort()
            step(hush)
        }
        quietMel = melFrames.map { it.copyOf() }
        quietFeatures = features.toList().takeLast(FEATURES)
        reset()
    }

    /** Forgets what it heard (after a pause, so old sound can't trigger it). */
    fun reset() {
        raw.fill(0f)
        rawFilled = 0
        melFrames.clear()
        quietMel.forEach { melFrames.addLast(it) }
        features.clear()
        quietFeatures.forEach { features.addLast(it) }
    }

    /** Takes the next [CHUNK] samples; returns how sure it is (0..1) that "Xarvis" was just said. */
    fun process(chunk: ShortArray): Float {
        require(chunk.size == CHUNK)
        if (!step(chunk) || features.size < FEATURES) return 0f
        return model.score(features)
    }

    /** Adds one chunk; true when it produced a new embedding. */
    private fun step(chunk: ShortArray): Boolean {
        // The window is the new chunk plus 3 hops of the previous audio.
        System.arraycopy(raw, CHUNK, raw, 0, WINDOW - CHUNK)
        for (i in 0 until CHUNK) raw[WINDOW - CHUNK + i] = chunk[i].toFloat()
        rawFilled = minOf(WINDOW, rawFilled + CHUNK)
        if (rawFilled < WINDOW) return false

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
        return true
    }

    override fun close() {
        mel.close()
        embedding.close()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        /** 80 ms of audio per step. */
        const val CHUNK = 1280
        private const val ASSETS = "wake"
        private const val WINDOW = CHUNK + 160 * 3
        private const val MEL_PER_CHUNK = 8
        private const val MEL_BINS = 32
        private const val MEL_FRAMES = 76
        private const val EMBEDDING = 96
        private const val FEATURES = 16

        private fun asset(context: Context, name: String): ByteBuffer {
            val bytes = context.assets.open("$ASSETS/$name").use { it.readBytes() }
            return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                put(bytes)
                rewind()
            }
        }
    }
}
