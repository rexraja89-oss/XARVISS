package com.xarvis.ai.ui

import kotlin.math.exp
import kotlin.math.sin

/**
 * The Sentinel face's story, as plain numbers (tested in SentinelTimelineTest). [t] = seconds since the new chat
 * opened: the hooded figure made of 0s and 1s fades in with its head lowered, lightning strikes, it rises, turns
 * aside, faces you, and its left eye ignites red. Then it stays, watching; lightning now and then.
 */
object SentinelTimeline {
    const val POSES = 48
    private const val POSE_FROM = 0.7f
    private const val POSE_TO = 6.4f
    const val EYE_ON = 6.6f

    private fun ease(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }

    /** Fade-in of the whole scene. */
    fun fade(t: Float): Float = ease(t / 1.2f)

    /** Which pose to show (0 = head lowered … 47 = facing you), fractional = blend of two. */
    fun pose(t: Float): Float = ((t - POSE_FROM) / (POSE_TO - POSE_FROM)).coerceIn(0f, 1f) * (POSES - 1)

    /** The eye: dark, then flickers on like a failing circuit, then a steady glow (pulsing while XARVIS thinks). */
    fun eye(t: Float, thinking: Boolean, typing: Boolean): Float {
        if (t < EYE_ON) return 0f
        if (t < EYE_ON + 0.7f) {
            val k = (t - EYE_ON) / 0.7f
            return k * (if (sin(t * 61f) > -0.2f) 1f else 0.5f)
        }
        return when {
            thinking -> 1.05f + 0.25f * sin(t * 9f)
            typing -> 1.1f
            else -> 0.92f + 0.08f * sin(t * 5f)
        }
    }

    /** Code rain speed: faster while XARVIS is thinking. */
    fun rainSpeed(thinking: Boolean): Float = if (thinking) 2.2f else 1f

    /**
     * Lightning brightness at [t]: a strike is a burst of irregular strobes. The first strike comes with the opening;
     * later ones every 9–20 s ([seed] varies them).
     */
    fun flash(t: Float, seed: Int = 7): Float {
        var v = 0f
        for (s in strikes(t, seed)) for (p in s.pulses) {
            val x = t - s.at - p.offset
            if (x >= 0f && x < p.decay * 3) v = maxOf(v, p.amp * exp(-x / p.decay))
        }
        return v
    }

    class Pulse(val offset: Float, val amp: Float, val decay: Float)
    class Strike(val at: Float, val pulses: List<Pulse>)

    /** The strikes that can still be lighting the sky at [t]. */
    fun strikes(t: Float, seed: Int = 7): List<Strike> {
        val out = ArrayList<Strike>()
        var at = 0.85f
        var i = 0
        while (at <= t + 0.1f && i < 10_000) {
            if (t - at < 2f) out += strike(at, if (i == 0) 1f else 0.3f + 0.5f * rnd(seed, i, 1), seed, i)
            at += 9f + 11f * rnd(seed, i, 2)
            i++
        }
        return out
    }

    private fun strike(at: Float, amp: Float, seed: Int, i: Int): Strike {
        val n = 3 + (rnd(seed, i, 3) * 2).toInt()
        val offs = (0 until n).map { if (it == 0) 0f else 0.55f * rnd(seed, i, 10 + it) }.sorted()
        return Strike(at, offs.mapIndexed { k, o ->
            Pulse(o, amp * (if (k == 0) 1f else 0.35f + 0.6f * rnd(seed, i, 20 + k)), 0.05f + 0.07f * rnd(seed, i, 30 + k))
        })
    }

    /** A repeatable 0..1 number for (seed, i, k). */
    internal fun rnd(seed: Int, i: Int, k: Int): Float {
        val x = sin((seed * 12.9898 + i * 78.233 + k * 37.719) * 1.0) * 43758.5453
        return (x - kotlin.math.floor(x)).toFloat()
    }

    // What the Sentinel is doing at the door (sentinel/SentinelGate).
    const val CALM = 0
    const val LOOKING = 1
    const val OWNER = 2
    const val STRANGER = 3

    /**
     * The eye at the door: scanning while the camera looks (a sweeping flicker, lit even before the story's 6.6 s),
     * a steady warm glow for Rex, burning for a stranger. CALM = the story's own [base].
     */
    fun moodEye(mood: Int, t: Float, base: Float): Float = when (mood) {
        LOOKING -> maxOf(base, 0.55f + 0.45f * kotlin.math.abs(sin(t * 6f)))
        OWNER -> maxOf(base, 0.95f)
        STRANGER -> 1.45f + 0.2f * sin(t * 3f)
        else -> base
    }

    /** A strike [since] seconds after the Sentinel decides: a bright one for a stranger, a soft one for Rex. */
    fun moodFlash(mood: Int, since: Float): Float {
        if (since < 0f || since > 1.5f) return 0f
        val amp = when (mood) { STRANGER -> 1f; OWNER -> 0.35f; else -> return 0f }
        var v = 0f
        for ((off, a) in listOf(0f to 1f, 0.18f to 0.6f, 0.42f to 0.45f)) {
            val x = since - off
            if (x >= 0f) v = maxOf(v, amp * a * exp(-x / 0.09f))
        }
        return v
    }

    /** Where the figure sits in an area [w]×[h] (px): left, top, width, height. Its pictures are 400:360. */
    fun box(w: Float, h: Float): FloatArray {
        val bw = minOf(w * 1.1f, h * 0.62f * (400f / 360f))
        val bh = bw * 360f / 400f
        return floatArrayOf((w - bw) / 2f, h * 0.44f - bh / 2f, bw, bh)
    }

}
