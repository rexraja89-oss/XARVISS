package com.xarvis.ai.rescue

/**
 * "Rescue my S22" (Rex, 17 Nov 2026, stuck behind Samsung's Accidental touch protection from the plant: "in a situation
 * like this it could help me to get over"). Pure: what the S22's screen is in, and which safe trick to try next.
 * XARVIS never enters the pattern/PIN — it only gets the pad up for Rex to draw.
 */
object RescueLogic {
    enum class State { ASLEEP, TOUCH_GUARD, PAD, LOCKED, UNLOCKED }
    enum class Trick { WAKE, SWIPE_PILL, HOME, BACK, RECYCLE, SWIPE_UP }

    private val GUARD = Regex("accidental touch protection|swipe up to dismiss|protected from accidental touches", RegexOption.IGNORE_CASE)
    private val PAD_WORDS = Regex(
        "draw (your )?(unlock )?pattern|enter (your )?(pin|password)|confirm (your )?(pattern|pin|password)|wrong pattern|incorrect pattern",
        RegexOption.IGNORE_CASE,
    )

    /** [text] = every visible text on screen (all windows); [padFound] = LockPad found the dots / keys. */
    fun state(screenOn: Boolean, locked: Boolean, text: String, padFound: Boolean): State = when {
        !screenOn -> State.ASLEEP
        GUARD.containsMatchIn(text) -> State.TOUCH_GUARD // the pad's own words show through underneath it
        !locked -> State.UNLOCKED
        padFound || PAD_WORDS.containsMatchIn(text) -> State.PAD
        else -> State.LOCKED
    }

    fun done(state: State) = state == State.PAD || state == State.UNLOCKED

    /** The tricks for a state, in the order tried (each at most once per state). */
    fun tricks(state: State): List<Trick> = when (state) {
        State.ASLEEP -> listOf(Trick.WAKE, Trick.WAKE)
        State.TOUCH_GUARD -> listOf(Trick.SWIPE_PILL, Trick.HOME, Trick.BACK, Trick.RECYCLE)
        State.LOCKED -> listOf(Trick.HOME, Trick.SWIPE_UP, Trick.RECYCLE)
        State.PAD, State.UNLOCKED -> emptyList()
    }

    const val MAX_TRIES = 8

    /** A binary proximity sensor reads 0 (near) or its maximum (far); others in cm. */
    fun near(value: Float, max: Float): Boolean = value < max && value < 5f

    fun did(trick: Trick): String = when (trick) {
        Trick.WAKE -> "Woke the screen"
        Trick.SWIPE_PILL -> "Swiped up the pill"
        Trick.HOME -> "Pressed Home"
        Trick.BACK -> "Pressed Back"
        Trick.RECYCLE -> "Turned the screen off and on"
        Trick.SWIPE_UP -> "Swiped up"
    }

    /** One line for Rex. [near] null = no sensor reading. */
    fun describe(state: State, near: Boolean?, app: String? = null): String = when (state) {
        State.ASLEEP -> "My screen is off."
        State.TOUCH_GUARD -> when (near) {
            true -> "Samsung's Accidental touch protection is up because my top sensor is covered (face-down, in a pocket or something over my top edge). It blocks every touch, mine too."
            false -> "Samsung's Accidental touch protection is up, though my top sensor reads clear now."
            null -> "Samsung's Accidental touch protection is up (I couldn't read my top sensor)."
        }
        State.PAD -> "The pattern pad is up — open the remote view and draw your pattern."
        State.LOCKED -> "I'm on the lock screen."
        State.UNLOCKED -> "I'm unlocked" + (app?.takeIf { it.isNotBlank() }?.let { " — $it is on screen." } ?: ".")
    }
}
