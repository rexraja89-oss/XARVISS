package com.xarvis.ai.hands

/**
 * The remote view's line drops for ordinary reasons — the benco hopping between Wi-Fi and mobile data, a phone call
 * pausing mobile data, the S22's router forgetting the direct line (Rex, 11 Nov 2026: "the direct line went quiet",
 * "Software caused connection abort", then "I can't reach your linked phone" and nothing more). So the view tries
 * again by itself — unless the other phone said no on purpose (STOP, a switch turned off), which it respects.
 * Pure, so it's unit-tested.
 */
object Reconnect {
    /** Answers that mean "no" from the other phone or its owner, not a bad line: don't keep knocking. */
    private val REFUSALS = listOf(
        "stop was pressed", "pressed stop", "switched off", "off or missing", "haven't allowed",
        "paused until next month", "only shares its screen", "isn't linked", "not allowed", "doesn't do",
        "check both devices' clocks", "can't be controlled",
    )

    fun isRefusal(why: String): Boolean {
        val w = why.lowercase()
        return REFUSALS.any { it in w }
    }

    /** How long to wait before retry number [n] (1 = the first): quick at first, then every 10 s. */
    fun waitMs(n: Int): Long = when {
        n <= 1 -> 1_000L
        n == 2 -> 2_000L
        n == 3 -> 4_000L
        n == 4 -> 7_000L
        else -> 10_000L
    }

    /** A plain-words hint once it has failed a few times in a row ("" before that). */
    fun hint(n: Int): String = when {
        n >= 6 -> "Check ☰ → 🌐 Always connected on both phones says Connected."
        n >= 3 -> "On a phone call? Some phones pause mobile data until the call ends."
        else -> ""
    }
}
