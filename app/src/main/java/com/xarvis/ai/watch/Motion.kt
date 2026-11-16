package com.xarvis.ai.watch

import kotlin.math.abs

/**
 * Watch mode's eye (Rex, 16 Nov 2026: "look through the front camera of the S22 with the screen off and let me know if
 * there's any motion or any change in the scene"). Each frame's brightness (the camera's Y plane) is averaged into a
 * 32 x 24 grid and compared with a slowly learned picture of the empty scene. Pure, so it's tested without a camera.
 *
 * - Each cell is divided by the frame's own average brightness, so the camera's auto-exposure or a cloud passing
 *   doesn't count as motion.
 * - A change has to show in [CONFIRM] frames in a row (one noisy frame isn't an event).
 * - What stays changed (a chair moved, a bag left) becomes part of the scene after about a minute, so it's reported
 *   once, not for ever.
 */
class Motion(private val cols: Int = 32, private val rows: Int = 24) {

    enum class Kind { MOTION, LIGHTS }

    /** [share] = part of the picture that changed (0..1); [where] = "left" / "middle" / "right" / "everywhere". */
    data class Event(val kind: Kind, val share: Float, val where: String)

    private var scene: FloatArray? = null
    private var sceneLight = 0f
    private var frames = 0
    private var streak = 0

    fun reset() { scene = null; frames = 0; streak = 0 }

    /**
     * One frame's luminance: [y] with [width] x [height] pixels, [rowStride] bytes per row. Returns an event when
     * something has changed, else null.
     */
    fun feed(y: ByteArray, width: Int, height: Int, rowStride: Int = width): Event? {
        val grid = grid(y, width, height, rowStride)
        val light = grid.average().toFloat().coerceAtLeast(1f)
        val norm = FloatArray(grid.size) { grid[it] / light * 128f }
        val old = scene
        frames++
        if (old == null || frames <= WARMUP) {
            scene = old?.let { o -> FloatArray(o.size) { o[it] * 0.5f + norm[it] * 0.5f } } ?: norm
            sceneLight = if (old == null) light else sceneLight * 0.5f + light * 0.5f
            return null
        }
        // A light switched on or off: the whole picture's brightness jumps.
        val lightJump = abs(light - sceneLight) / sceneLight.coerceAtLeast(1f)
        val changed = BooleanArray(norm.size) { abs(norm[it] - old[it]) > CELL_DIFF }
        val share = changed.count { it }.toFloat() / changed.size
        val candidate = lightJump > LIGHT_JUMP || share >= MIN_SHARE
        // Learn the scene: fast while calm, slowly while something's going on (so it settles after ~1 minute).
        val rate = if (candidate) SLOW else FAST
        for (i in norm.indices) old[i] = old[i] * (1 - rate) + norm[i] * rate
        sceneLight = sceneLight * (1 - rate) + light * rate
        streak = if (candidate) streak + 1 else 0
        if (streak != CONFIRM) return null
        val kind = if (lightJump > LIGHT_JUMP) Kind.LIGHTS else Kind.MOTION
        return Event(kind, share, where(changed))
    }

    private fun grid(y: ByteArray, width: Int, height: Int, rowStride: Int): FloatArray {
        val out = FloatArray(cols * rows)
        val cw = width / cols
        val ch = height / rows
        for (r in 0 until rows) for (c in 0 until cols) {
            var sum = 0
            var n = 0
            // Every 2nd pixel each way: plenty for a 20 x 20 cell, and 4x less work.
            var yy = r * ch
            while (yy < (r + 1) * ch) {
                var xx = c * cw
                val base = yy * rowStride
                while (xx < (c + 1) * cw) { sum += y[base + xx].toInt() and 0xFF; n++; xx += 2 }
                yy += 2
            }
            out[r * cols + c] = if (n == 0) 0f else sum.toFloat() / n
        }
        return out
    }

    private fun where(changed: BooleanArray): String {
        val third = cols / 3
        val counts = IntArray(3)
        for (r in 0 until rows) for (c in 0 until cols) if (changed[r * cols + c]) counts[minOf(2, c / third)]++
        val total = counts.sum().coerceAtLeast(1)
        if (counts.count { it * 4 > total } == 3) return "everywhere"
        return listOf("left", "middle", "right")[counts.indices.maxBy { counts[it] }]
    }

    companion object {
        const val WARMUP = 4
        const val CONFIRM = 2
        const val CELL_DIFF = 22f
        const val MIN_SHARE = 0.04f
        const val LIGHT_JUMP = 0.35f
        const val FAST = 0.10f
        const val SLOW = 0.03f
    }
}
