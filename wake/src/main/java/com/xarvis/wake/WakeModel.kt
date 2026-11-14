package com.xarvis.wake

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * The small networks that answer "was Xarvis just said?" from the last two seconds of speech
 * embeddings (16 of them, 96 numbers each). Each is a few fully connected layers (layer norm and
 * ReLU after each, one output); several were trained separately and their answers are averaged,
 * which is steadier than any one of them (training/wake/README.md). Plain Kotlin, so it runs —
 * and is tested — without a phone.
 */
class WakeModel(private val heads: List<List<Layer>>, val frames: Int, val dim: Int) {

    /** [weights] holds [outputs] rows of [inputs]; [gain]/[shift] are the layer norm (null on a head's last layer). */
    class Layer(val inputs: Int, val outputs: Int, val weights: FloatArray, val bias: FloatArray, val gain: FloatArray?, val shift: FloatArray?)

    private val input = FloatArray(frames * dim)
    private val outputs = heads.map { head -> head.map { FloatArray(it.outputs) } }

    init {
        require(heads.isNotEmpty()) { "not a wake model" }
        for (head in heads) {
            require(head.isNotEmpty() && head.first().inputs == frames * dim && head.last().outputs == 1) { "not a wake model" }
            head.zipWithNext { a, b -> require(a.outputs == b.inputs) { "layers don't fit together" } }
        }
    }

    /** How many networks are averaged. */
    val size: Int get() = heads.size

    /** How sure (0..1) it is that the wake word has just ended, given the last [frames] embeddings, oldest first. */
    @Synchronized
    fun score(features: List<FloatArray>): Float {
        require(features.size == frames) { "needs $frames embeddings, got ${features.size}" }
        for (t in 0 until frames) System.arraycopy(features[t], 0, input, t * dim, dim)
        var sum = 0.0
        for ((h, head) in heads.withIndex()) sum += logit(head, outputs[h])
        return (1.0 / (1.0 + exp(-sum / heads.size))).toFloat()
    }

    /** One network's raw answer (before the sigmoid). */
    private fun logit(head: List<Layer>, buffers: List<FloatArray>): Float {
        var x = input
        for ((n, layer) in head.withIndex()) {
            val y = buffers[n]
            for (o in 0 until layer.outputs) {
                var sum = layer.bias[o]
                val row = o * layer.inputs
                for (i in 0 until layer.inputs) sum += layer.weights[row + i] * x[i]
                y[o] = sum
            }
            val gain = layer.gain
            val shift = layer.shift
            if (gain != null && shift != null) {
                var mean = 0f
                for (v in y) mean += v
                mean /= y.size
                var variance = 0f
                for (v in y) variance += (v - mean) * (v - mean)
                val spread = sqrt(variance / y.size + NORM_EPSILON)
                for (o in y.indices) y[o] = maxOf(0f, (y[o] - mean) / spread * gain[o] + shift[o])
            }
            x = y
        }
        return x[0]
    }

    companion object {
        private const val MAGIC = 0x324B5758 // "XWK2", little-endian
        private const val NORM_EPSILON = 1e-5f

        /**
         * Reads the file training/wake/export.py writes (little-endian): "XWK2", frames, dim, how many networks;
         * per network: its layer count, then per layer: inputs, outputs, has-norm (0/1), weights, bias and, with a
         * norm, its gain and shift.
         */
        fun read(stream: InputStream): WakeModel {
            val b = ByteBuffer.wrap(stream.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            require(b.remaining() >= 16 && b.int == MAGIC) { "not a wake model file" }
            val frames = b.int
            val dim = b.int
            val count = b.int
            require(frames in 1..64 && dim in 1..1024 && count in 1..64) { "damaged wake model file" }
            fun floats(n: Int): FloatArray {
                require(b.remaining() >= 4 * n) { "damaged wake model file" }
                return FloatArray(n).also { b.asFloatBuffer().get(it); b.position(b.position() + 4 * n) }
            }
            val heads = List(count) {
                require(b.remaining() >= 4) { "damaged wake model file" }
                val layers = b.int
                require(layers in 1..16) { "damaged wake model file" }
                List(layers) {
                    require(b.remaining() >= 12) { "damaged wake model file" }
                    val inputs = b.int
                    val outputs = b.int
                    val norm = b.int == 1
                    require(inputs in 1..65536 && outputs in 1..4096) { "damaged wake model file" }
                    Layer(inputs, outputs, floats(inputs * outputs), floats(outputs), if (norm) floats(outputs) else null, if (norm) floats(outputs) else null)
                }
            }
            return WakeModel(heads, frames, dim)
        }
    }
}
