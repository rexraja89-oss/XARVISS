package com.xarvis.ai.hands

/**
 * Keeps the slow picture stream from piling up somewhere between the two phones. The sending phone only learns that
 * the relay *took* a picture, not that the other phone *got* it, so on a slow line pictures queued on the relay and
 * the view fell a minute behind. Now the viewer answers every picture with [StreamMsg.SHOWN], and the host sends a
 * new one only while fewer than [max] are still on their way: the view stays about one picture behind, never more.
 *
 * Older viewers never answer; until the first SHOWN arrives, pictures go out as before.
 */
class PictureWindow(private val max: Int = 2, private val staleMs: Long = 6_000) {
    private val onTheWay = ArrayDeque<Long>()
    private var answering = false

    @Synchronized fun sent(now: Long) {
        onTheWay.addLast(now)
    }

    @Synchronized fun shown() {
        answering = true
        onTheWay.removeFirstOrNull()
    }

    /** May another picture go now? (A picture unanswered for [staleMs] is taken as lost, so nothing stalls.) */
    @Synchronized fun room(now: Long): Boolean {
        if (!answering) return true
        while (onTheWay.isNotEmpty() && now - onTheWay.first() > staleMs) onTheWay.removeFirst()
        return onTheWay.size < max
    }
}
