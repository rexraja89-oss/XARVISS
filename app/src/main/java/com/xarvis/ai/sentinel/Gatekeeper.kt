package com.xarvis.ai.sentinel

import com.xarvis.ai.faces.FaceMath

/**
 * The Sentinel at the door (Rex, 22 Nov 2026: "whenever I open XARVIS the Sentinel should use the camera to see who
 * is operating… if it's me, 'Sir' or 'Rex'; someone else: 'Hello stranger, are you authorised?' and a code goes to my
 * other device, entered within a minute or the phone locks"). Pure rules, tested in GatekeeperTest; the camera,
 * voice and lock live in [SentinelGate].
 */
object Gatekeeper {
    /** Who the front camera saw. */
    enum class Seen { OWNER, UNSURE, STRANGER, NOBODY }

    /** How long a code stays good, and how many wrong ones before the phone locks. */
    const val DEADLINE_MS = 60_000L
    const val MAX_TRIES = 3
    /** Rex seen once is trusted until the phone locks, or this long once XARVIS has been away. */
    const val TRUST_MS = 15 * 60_000L
    /** Switching to another app and straight back doesn't count as opening XARVIS. */
    const val AWAY_MS = 30_000L

    /**
     * [frames] = for each picture, each face's likeness to Rex's taught face (cosine). Rex in any picture wins;
     * no face in any picture = nobody's there (the phone lies on a table, or Rex is in through the remote view);
     * a face like Rex's but not certain (poor light) = UNSURE; else a stranger.
     */
    fun verdict(frames: List<List<Float>>): Seen {
        val all = frames.flatten()
        return when {
            all.isEmpty() -> Seen.NOBODY
            all.any { it >= FaceMath.SURE } -> Seen.OWNER
            all.any { it >= FaceMath.MAYBE } -> Seen.UNSURE
            else -> Seen.STRANGER
        }
    }

    /**
     * Whether opening XARVIS now should look at who's there. [verifiedAt] = when someone was last let in (0 = never
     * since XARVIS started), [lockedSince] = the screen went off since then, [awayMs] = how long XARVIS was out of
     * sight.
     */
    fun needsCheck(now: Long, verifiedAt: Long, lockedSince: Boolean, awayMs: Long): Boolean = when {
        verifiedAt == 0L -> true
        lockedSince -> true
        now - verifiedAt > TRUST_MS && awayMs >= AWAY_MS -> true
        else -> false
    }

    /** A four-digit code, easy to say out loud. */
    fun newCode(random: java.util.Random = java.security.SecureRandom()): String =
        String.format(java.util.Locale.US, "%04d", random.nextInt(10_000))

    private val WORDS = mapOf(
        "zero" to "0", "oh" to "0", "o" to "0", "nil" to "0", "one" to "1", "won" to "1", "two" to "2", "to" to "2",
        "too" to "2", "three" to "3", "tree" to "3", "four" to "4", "for" to "4", "five" to "5", "six" to "6",
        "seven" to "7", "eight" to "8", "ate" to "8", "nine" to "9",
        // Hindi, as a recogniser writes it in English letters
        "ek" to "1", "do" to "2", "teen" to "3", "char" to "4", "chaar" to "4", "paanch" to "5", "panch" to "5",
        "chhe" to "6", "che" to "6", "saat" to "7", "aath" to "8", "nau" to "9", "shunya" to "0",
    )

    /** The digits in what was said or typed: "4 8 2 1", "four eight two one", "double five one two" → digits. */
    fun digitsIn(said: String): String {
        val out = StringBuilder()
        var twice = 0
        for (w in said.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }) {
            when {
                w == "double" -> twice = 2
                w == "triple" -> twice = 3
                w.all { it.isDigit() } -> {
                    out.append(w.first().toString().repeat(if (twice > 0) twice - 1 else 0)).append(w); twice = 0
                }
                WORDS[w] != null -> { out.append(WORDS[w]!!.repeat(maxOf(1, twice))); twice = 0 }
            }
        }
        return out.toString()
    }

    /** Whether [said] gives [code] (the code anywhere in its digits, so "it's 4821" counts). */
    fun matches(said: String, code: String): Boolean = code.isNotEmpty() && digitsIn(said).contains(code)

    /** What the Sentinel says to Rex: by time of day, and a different one each time ([n]). */
    fun greeting(hour: Int, n: Int): String {
        val part = when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> null
        }
        val lines = listOf(
            (part ?: "Up late") + ", sir.",
            "Ah. Rex. I was just keeping the rain company.",
            "Welcome back, sir. Everything is as you left it.",
            "It's you, sir. Stand down, everyone.",
            (part ?: "Still awake") + ", Rex. What are we building?",
            "Recognised. Welcome back, sir.",
            if (hour in 0..4) "Sir. The rest of the world is asleep; I, however, am not." else "At your service, sir.",
        )
        return lines[Math.floorMod(n, lines.size)]
    }

    /** Said the moment a stranger is seen (the code is on its way meanwhile). */
    fun opening(seen: Seen): String =
        (if (seen == Seen.UNSURE) "I can't quite make you out." else "Hello, stranger.") + " Are you authorised to use this device?"

    /** Said once the code went to [other] (null: no other phone could be reached). */
    fun ask(other: String?): String =
        if (other != null) "If so, say the code I've just sent to $other. You have one minute."
        else "I couldn't reach Rex's other phone, so only his fingerprint will do. You have one minute."

    fun wrong(triesLeft: Int): String =
        if (triesLeft > 1) "That isn't it. $triesLeft tries left." else "That isn't it. One last try."

    const val GRANTED = "Code accepted. Welcome — Rex has been told you're here."
    const val OWNER_BY_FINGER = "My apologies, sir. The light did you no favours."
    const val LOCKING = "No code. Locking the phone."
    const val LOCKED_NO_HANDS = "No code. My Hands are switched off, so I can't lock the phone — but I stay closed, and Rex has been told."
}
