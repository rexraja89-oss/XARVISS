package com.xarvis.wake

import kotlin.math.sqrt

/**
 * Saves battery: the models only run while there is sound. It follows the room's own noise level
 * and opens when a chunk is clearly louder than that, staying open for a moment after. While it
 * is closed the ears keep the last [RING] chunks, so the start of a word is never lost.
 */
class SoundGate {
    private var floor = -1f
    private var quietChunks = 0

    /** Whether the models should run. */
    var open = true
        private set

    fun reset() {
        floor = -1f
        quietChunks = 0
        open = true
    }

    /** Takes one chunk's loudness ([level]); returns whether the gate is open. */
    fun feed(level: Float): Boolean {
        floor = when {
            floor < 0f || level < floor -> level
            else -> minOf(level, floor * RISE + 0.5f) // creeps up to a new steady noise level in a few seconds
        }
        val loud = level > maxOf(floor * LOUDER, floor + MARGIN)
        if (loud) {
            quietChunks = 0
            open = true
        } else if (++quietChunks > HOLD) {
            open = false
        }
        return open
    }

    companion object {
        /** Chunks kept while closed: 1.6 s. */
        const val RING = 20
        private const val HOLD = 19
        private const val LOUDER = 1.6f
        private const val MARGIN = 30f
        private const val RISE = 1.01f

        /** A chunk's loudness: the root mean square of its samples (0..32767). */
        fun level(chunk: ShortArray): Float {
            var sum = 0.0
            for (s in chunk) sum += s.toDouble() * s
            return sqrt(sum / chunk.size).toFloat()
        }
    }
}

/**
 * When scores count as hearing the wake word: [need] of the last [of] steps at or over the
 * threshold (one lucky step isn't enough), then nothing again until [reset].
 */
class HitRule(private val need: Int = 2, private val of: Int = 3) {
    private val recent = ArrayDeque<Boolean>()

    fun reset() = recent.clear()

    fun feed(score: Float, threshold: Float): Boolean {
        recent.addLast(score >= threshold)
        if (recent.size > of) recent.removeFirst()
        return recent.count { it } >= need
    }
}

/** How easily "Xarvis" wakes it: Rex picks one on the wake word screen. */
enum class Sensitivity(val label: String, val threshold: Float) {
    LOW("Low — fewer false wake-ups", 0.7f),
    NORMAL("Normal", 0.5f),
    HIGH("High — hears a quiet or far \"Xarvis\"", 0.35f);

    companion object {
        fun of(index: Int): Sensitivity = entries.getOrElse(index) { NORMAL }
    }
}
