package com.xarvis.ai.hands

/**
 * Keeps live video from piling up between the two phones (Rex, 26 Nov 2026: "live · 4 fps · 7717 ms delay ·
 * 🌐 Relay" and a shaking picture). The host only learns that the line *took* a frame, not that the viewer *got* it,
 * so on a slow receiving leg frames queued on the relay and arrived seconds late in bursts.
 *
 * Now the viewer answers each video frame with [StreamMsg.VIDEO_SHOWN]. While too many frames are still on their way
 * (more than [maxFrames], or the oldest older than [maxLagMs]) the host stops sending; once the line has caught up it
 * asks the encoder for a full picture ([needKey]) and starts again from that key frame — the viewer's decoder never
 * gets a frame whose predecessor was skipped. The view then stays about a second behind at worst, and shows a short
 * pause instead of a growing delay.
 *
 * Older viewers never answer; until the first answer arrives, every frame goes out as before.
 */
class VideoPacer(
    private val maxFrames: Int = 45,
    private val maxLagMs: Long = 1_200,
    private val staleMs: Long = 6_000,
) {
    private val onTheWay = ArrayDeque<Long>()
    private var answering = false
    private var dropping = false
    private var keyAsked = false

    /** Frames skipped so far (for the log). */
    var dropped = 0
        private set

    /** Should this encoded frame go to the viewer? Call [sent] when it went. */
    @Synchronized fun shouldSend(key: Boolean, now: Long): Boolean {
        if (!answering) return true
        while (onTheWay.isNotEmpty() && now - onTheWay.first() > staleMs) onTheWay.removeFirst()
        if (!dropping) {
            val behind = onTheWay.size >= maxFrames || (onTheWay.isNotEmpty() && now - onTheWay.first() > maxLagMs)
            if (!behind) return true
            dropping = true
            keyAsked = false
        }
        // Behind: send nothing until the line has emptied, then only from a full picture.
        if (key && onTheWay.size <= 1) {
            dropping = false
            return true
        }
        dropped++
        return false
    }

    /** True once while catching up: the line is clear, ask the encoder for a key frame. */
    @Synchronized fun needKey(): Boolean {
        if (!dropping || keyAsked || onTheWay.size > 1) return false
        keyAsked = true
        return true
    }

    @Synchronized fun sent(now: Long) {
        onTheWay.addLast(now)
    }

    @Synchronized fun shown() {
        answering = true
        onTheWay.removeFirstOrNull()
    }
}
