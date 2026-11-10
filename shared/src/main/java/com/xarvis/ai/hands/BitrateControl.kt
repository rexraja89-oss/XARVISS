package com.xarvis.ai.hands

/**
 * Live video's bit rate, following what the connection can carry (Rex's first 4G test: a game at the full 4 Mbit/s
 * backed up to 4–5 pictures a second, 0.4–0.7 s behind, then stalled). After each frame the host reports how long
 * sending it had to wait for room on the line; when the line can't keep up the rate drops at once (a softer picture
 * that stays live), and it creeps back up while sending is easy.
 */
class BitrateControl(private val max: Int, private val min: Int = MIN_BPS, start: Int = max) {
    /** The bit rate the encoder should use now. */
    var current: Int = start.coerceIn(min, max)
        private set

    private var windowStart = -1L
    private var waited = 0L
    private var easyWindows = 0

    /** A frame was sent at [nowNs] after waiting [waitNs] for room. Returns a new bit rate when it should change. */
    fun sent(waitNs: Long, nowNs: Long): Int? {
        if (windowStart < 0) windowStart = nowNs
        waited += waitNs
        val span = nowNs - windowStart
        if (span < WINDOW_NS) return null
        val busy = waited.toDouble() / span
        windowStart = nowNs
        waited = 0
        val next = when {
            busy > BUSY -> {
                easyWindows = 0
                (current * DOWN).toInt()
            }
            busy < EASY -> {
                if (++easyWindows >= EASY_WINDOWS) {
                    easyWindows = 0
                    (current * UP).toInt()
                } else {
                    current
                }
            }
            else -> {
                easyWindows = 0
                current
            }
        }.coerceIn(min, max)
        if (next == current) return null
        current = next
        return next
    }

    companion object {
        const val MIN_BPS = 300_000
        private const val WINDOW_NS = 1_000_000_000L
        /** Waiting more than this share of the time: the line is full. */
        private const val BUSY = 0.2
        /** Waiting less than this: there's room. */
        private const val EASY = 0.03
        private const val DOWN = 0.6
        private const val UP = 1.2
        /** Easy seconds in a row before going up again (up slowly, down fast). */
        private const val EASY_WINDOWS = 3

        /** Where a stream starts: full rate at home; away from home (Direct, relay) lower, and up from there. */
        fun startFor(route: String, max: Int): Int = if (route == "wifi" || route == "tailscale") max else minOf(max, 2_000_000)
    }
}
