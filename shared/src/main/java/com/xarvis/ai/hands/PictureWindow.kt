package com.xarvis.ai.hands

/**
 * Keeps the slow picture stream from piling up somewhere between the two phones. The sending phone only learns that
 * the relay *took* a picture, not that the other phone *got* it, so on a slow line pictures queued on the relay and
 * the view fell a minute behind. Now the viewer answers every picture with [StreamMsg.SHOWN], and the host sends a
 * new one only while fewer than [max] are still on their way: the view stays about one picture behind, never more.
 *
 * Older viewers never answer; until the first SHOWN arrives, pictures go out as before.
 *
 * It also times each picture's trip (sent → SHOWN) and [side] makes the pictures smaller while the line is slow
 * (Rex, 24 Nov 2026: "0 pictures/s · 1896 ms delay" on 4G with full-size pictures) and full size again when it's fast.
 */
class PictureWindow(private val max: Int = 2, private val staleMs: Long = 6_000) {
    private val onTheWay = ArrayDeque<Long>()
    private var answering = false
    /** Smoothed time from sending a picture to the viewer answering, ms; -1 until the first answer. */
    var tripMs = -1L
        private set

    @Synchronized fun sent(now: Long) {
        onTheWay.addLast(now)
    }

    @Synchronized fun shown(now: Long = System.currentTimeMillis()) {
        answering = true
        val sentAt = onTheWay.removeFirstOrNull() ?: return
        val trip = (now - sentAt).coerceAtLeast(0)
        tripMs = if (tripMs < 0) trip else (tripMs * 7 + trip * 3) / 10
    }

    /** How big the next picture may be (long side, px): [full] on a quick line, smaller as the trips get longer. */
    @Synchronized fun side(full: Int): Int = when {
        tripMs < 0 -> full
        tripMs > 1_500 -> minOf(full, 480)
        tripMs > 900 -> minOf(full, 560)
        tripMs > 500 -> minOf(full, 720)
        tripMs > 300 -> minOf(full, 900)
        else -> full
    }

    /** May another picture go now? (A picture unanswered for [staleMs] is taken as lost, so nothing stalls.) */
    @Synchronized fun room(now: Long): Boolean {
        if (!answering) return true
        while (onTheWay.isNotEmpty() && now - onTheWay.first() > staleMs) onTheWay.removeFirst()
        return onTheWay.size < max
    }
}
