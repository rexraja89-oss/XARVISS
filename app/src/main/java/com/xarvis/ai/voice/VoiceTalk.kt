package com.xarvis.ai.voice

/**
 * The words of a wake-word conversation ("Xarvis" → "Yes, sir?" → Rex talks → XARVIS answers →
 * Rex talks again…): what XARVIS says, when Rex is ending it, and a spoken yes or no. Free of
 * Android, so it's unit tested.
 */
object VoiceTalk {
    // What XARVIS is doing, for the ears (the same numbers as com.xarvis.wake.WakeHost and XARVIS Ears).
    const val IDLE = 0
    const val THINKING = 1
    const val SPEAKING = 2
    const val HOLD = 3

    const val GREETING = "Yes, sir?"
    const val BUSY = "One moment, sir. I'm still on the last thing."
    const val YES_OR_NO = "Please say yes or no, sir."

    private val NAME = Regex("""\b(?:xarvis|zarvis|jarvis|sir)\b""")

    private fun plain(text: String): String = text.lowercase()
        .replace('’', '\'')
        .replace(Regex("""[^\p{L}\p{N}' ]"""), " ")
        .replace(NAME, " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private val THANKS = setOf(
        "thank you", "thanks", "thank you very much", "thanks a lot", "thank you that's all", "thanks that's all",
        "ok thank you", "okay thank you", "ok thanks", "okay thanks", "shukriya", "dhanyavad", "dhanyavaad", "thank u",
    )
    private val DONE = setOf(
        "that's all", "that is all", "thats all", "that'll be all", "that will be all", "that's it", "thats it", "that is it",
        "nothing", "nothing else", "no thanks", "no thank you", "bye", "goodbye", "good bye", "ok bye", "okay bye", "bye bye",
        "stop listening", "go to sleep", "you can go", "that's all for now", "bas", "bas itna hi", "bas ho gaya", "kuch nahi",
        "kuch nahin", "rehne do", "chalo bye", "alvida",
    )
    private val QUIET = setOf(
        "ok", "okay", "alright", "all right", "fine", "got it", "i see", "good", "great", "nice", "cool", "hmm", "right",
        "theek hai", "thik hai", "accha", "acha", "achha", "never mind", "nevermind", "cancel", "stop", "leave it", "forget it",
        "no", "nope", "nahi", "nahin",
    )

    /**
     * What to say when Rex ends the conversation with [text] — "" to end it without a word (an "okay") — or null
     * when [text] isn't an ending and should be answered like anything else.
     */
    fun closing(text: String): String? = when (plain(text)) {
        "" -> ""
        in THANKS -> "My pleasure, sir."
        in DONE -> "Very good, sir."
        in QUIET -> ""
        else -> null
    }

    private val YES = setOf(
        "yes", "yeah", "yep", "yup", "yes please", "do it", "go ahead", "sure", "ok", "okay", "allow", "allow it", "confirm",
        "please do", "of course", "definitely", "haan", "han", "ha", "ji", "ji haan", "haan ji", "kar do", "kardo", "theek hai",
        "thik hai", "bilkul", "zaroor", "yes do it", "yes go ahead",
    )
    private val NO = setOf(
        "no", "nope", "nah", "don't", "do not", "dont", "cancel", "stop", "never mind", "nevermind", "no thanks", "no thank you",
        "nahi", "nahin", "mat karo", "rehne do", "no don't", "not now", "deny",
    )

    /** A spoken answer to "Shall I…?": true = yes, false = no, null = neither. */
    fun yesNo(text: String): Boolean? {
        val said = plain(text)
        if (said in YES) return true
        if (said in NO) return false
        val first = said.substringBefore(' ')
        return when {
            first in setOf("yes", "yeah", "yep", "haan", "sure") -> true
            first in setOf("no", "nope", "nahi", "nahin", "don't", "dont", "cancel") -> false
            else -> null
        }
    }

    /** How XARVIS asks for a yes or no in a spoken conversation, and on the screen otherwise. */
    fun question(action: String, spokenConversation: Boolean): String {
        val what = action.trim().trimEnd('.', '?').replaceFirstChar { it.lowercase() }
        return if (spokenConversation) "Shall I $what, sir? Say yes or no." else "Shall I $what? Tap allow on the screen."
    }
}
