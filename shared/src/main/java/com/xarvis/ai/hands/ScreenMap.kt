package com.xarvis.ai.hands

/**
 * Where a touch on the viewer's picture lands on this phone's screen. The viewer sends 0–1 of the picture it shows.
 * Still pictures always show the whole screen as it is now; live video is a fixed-size mirror, so when the phone
 * turns (a game going landscape) the screen sits inside it with black bars — the touch must be mapped through that,
 * using the screen's size *now* (Rex's 4G test, Nov 2026: taps on a landscape game used the size from the start).
 */
object ScreenMap {
    /**
     * The point on the [screenW]×[screenH] screen for ([fx], [fy]) of the picture, or null when it's on the black bars
     * (only when [clamp] is false; moves and lifts are clamped so a finger that slides off still lifts).
     * [videoW]/[videoH]: the live video's size, or 0 for still pictures.
     */
    fun toScreen(fx: Double, fy: Double, screenW: Int, screenH: Int, videoW: Int, videoH: Int, clamp: Boolean = false): Pair<Float, Float>? {
        val px = fx.coerceIn(0.0, 1.0)
        val py = fy.coerceIn(0.0, 1.0)
        if (videoW <= 0 || videoH <= 0) return (px * (screenW - 1)).toFloat() to (py * (screenH - 1)).toFloat()
        // The mirror keeps the screen's shape: scaled to fit, centred.
        val s = minOf(videoW.toDouble() / screenW, videoH.toDouble() / screenH)
        val ox = (videoW - screenW * s) / 2
        val oy = (videoH - screenH * s) / 2
        val x = (px * videoW - ox) / s
        val y = (py * videoH - oy) / s
        // A sliver of tolerance: the video's size is rounded to multiples of 16.
        val mx = screenW * EDGE
        val my = screenH * EDGE
        if (!clamp && (x < -mx || y < -my || x > screenW - 1 + mx || y > screenH - 1 + my)) return null
        return x.coerceIn(0.0, screenW - 1.0).toFloat() to y.coerceIn(0.0, screenH - 1.0).toFloat()
    }

    private const val EDGE = 0.02
}
