package com.xarvis.ai.agent

/**
 * Reads the emotional tone of what Rex says so XARVIS can answer like a human would — calm and
 * reassuring when he's in trouble, warm when he's down, glad when he's happy. A light heuristic
 * (English + Hinglish + a few emoji), checked most-urgent first. Used to colour both the words
 * (a short note added to the brain's prompt) and the spoken voice (pitch and pace).
 */
object Emotion {
    enum class Tone { DISTRESS, ANXIOUS, SAD, FRUSTRATED, EXCITED, JOY, NEUTRAL }

    private val DISTRESS = Regex("""(?i)\b(help me|i'?m in (?:trouble|danger|a situation)|in a situation|emergency|accident|he'?s hurt|she'?s hurt|i'?m hurt|bleeding|can'?t breathe|not breathing|save me|rescue|sos|robbed|attack(?:ed|ing)?|fire!|drowning|bachao|bacha lo|khatra|madad)\b""")
    private val ANXIOUS = Regex("""(?i)\b(anxious|anxiety|worried|worry|nervous|panic\w*|stressed|overwhelm\w*|can'?t sleep|overthinking|scared|afraid|ghabra\w*|pareshan|dar lag)\b""")
    private val SAD = Regex("""(?i)\b(sad|depress\w*|down|low|lonely|alone|crying|cry|heartbroken|miserable|hopeless|unhappy|udaas|dukhi|rona)\b|😢|😭|💔|😞""")
    private val FRUSTRATED = Regex("""(?i)\b(frustrat\w*|annoyed|annoying|angry|furious|fed up|pissed|irritat\w*|hate this|so done|ugh|argh|gussa|chidh|bakwaas|bekaar)\b|😠|😡|🤬""")
    private val EXCITED = Regex("""(?i)\b(excited|can'?t wait|thrilled|pumped|hyped|let'?s go|lets go|finally|woohoo|yess+)\b|🎉|🔥|🚀|🤩""")
    private val JOY = Regex("""(?i)\b(happy|so happy|great news|good news|awesome|amazing|wonderful|yay+|love (?:it|this)|brilliant|fantastic|khush|mazaa|zabardast|badhiya)\b|😍|😄|😁|😊|🥳""")

    fun detect(message: String): Tone = when {
        DISTRESS.containsMatchIn(message) -> Tone.DISTRESS
        ANXIOUS.containsMatchIn(message) -> Tone.ANXIOUS
        SAD.containsMatchIn(message) -> Tone.SAD
        FRUSTRATED.containsMatchIn(message) -> Tone.FRUSTRATED
        EXCITED.containsMatchIn(message) -> Tone.EXCITED
        JOY.containsMatchIn(message) -> Tone.JOY
        else -> Tone.NEUTRAL
    }

    /** A short note added before the message so the brain answers with the right human warmth. */
    fun promptHint(tone: Tone): String = when (tone) {
        Tone.DISTRESS -> "(Rex sounds distressed or in trouble. Be calm, steady and reassuring, keep it short, and ask what he needs or tell him what you're doing. No jokes.)"
        Tone.ANXIOUS -> "(Rex sounds anxious or worried. Be calm and reassuring; steady him, gently. Go easy on the wit.)"
        Tone.SAD -> "(Rex sounds down or sad. Be warm, gentle and genuinely supportive; don't be chirpy or crack jokes.)"
        Tone.FRUSTRATED -> "(Rex sounds frustrated. Stay calm and grounded, acknowledge it in a line, then actually help — no lectures.)"
        Tone.EXCITED -> "(Rex sounds excited. Match his energy a little; be upbeat and glad with him.)"
        Tone.JOY -> "(Rex sounds happy. Share his warmth; be genuinely pleased for him.)"
        Tone.NEUTRAL -> ""
    }

    /** Voice pitch multiplier for the tone (subtle). */
    fun pitchScale(tone: Tone): Float = when (tone) {
        Tone.DISTRESS, Tone.FRUSTRATED -> 0.97f
        Tone.ANXIOUS -> 0.98f
        Tone.SAD -> 1.0f
        Tone.JOY -> 1.05f
        Tone.EXCITED -> 1.08f
        Tone.NEUTRAL -> 1.0f
    }

    /** Voice speed multiplier for the tone (subtle). */
    fun rateScale(tone: Tone): Float = when (tone) {
        Tone.DISTRESS, Tone.ANXIOUS -> 0.95f
        Tone.SAD -> 0.92f
        Tone.FRUSTRATED -> 0.96f
        Tone.JOY -> 1.04f
        Tone.EXCITED -> 1.08f
        Tone.NEUTRAL -> 1.0f
    }
}
