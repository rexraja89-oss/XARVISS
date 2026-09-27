package com.xarvis.ai.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice as TtsVoice
import android.util.Log
import java.util.Locale

/**
 * XARVIS's voice: the phone's text-to-speech with a British male voice, a little deeper and
 * calmer than normal, in the style of JARVIS from Iron Man (not a copy of the actor's voice).
 */
class Voice(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @Volatile private var ready = false

    /**
     * Google's engine when the phone has it: Samsung's own engine only offered Rex a female
     * voice ("sounds like Friday"), while Google's has British male voices.
     */
    private val engine: String? = GOOGLE_TTS.takeIf {
        runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess
    }

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext, { status ->
        if (status == TextToSpeech.SUCCESS) {
            setUp()
            ready = true
        } else {
            Log.w(TAG, "Text-to-speech unavailable ($status)")
        }
    }, engine)

    private var english: TtsVoice? = null
    private var hindi: TtsVoice? = null

    private fun setUp() {
        val voices = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
        // Rex's own pick (the ☰ menu's voice buttons) wins over the guess.
        english = voices.firstOrNull { it.name == prefs.getString("voiceEnglish", null) } ?: pick(voices)
        hindi = voices.firstOrNull { it.name == prefs.getString("voiceHindi", null) } ?: pickHindi(voices)
        english?.let { tts.voice = it } ?: tts.setLanguage(Locale.UK)
        tts.setPitch(PITCH)
        tts.setSpeechRate(RATE)
        Log.i(TAG, "Voice: ${tts.voice?.name}")
    }

    /**
     * A British male voice: Google's (named like "en-gb-x-rjs-local"), one already on the phone
     * first, else the online version of it; else any voice called male; else any British one.
     */
    private fun pick(voices: Set<TtsVoice>): TtsVoice? {
        val british = voices.filter { it.locale.language == "en" && it.locale.country == "GB" }
        fun installed(v: TtsVoice) = !v.isNetworkConnectionRequired &&
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features.orEmpty()
        val male = MALE_BRITISH.flatMap { id -> british.filter { it.name.contains(id, ignoreCase = true) } } +
            british.filter { it.name.contains("male", ignoreCase = true) && !it.name.contains("female", ignoreCase = true) }
        return male.firstOrNull(::installed) ?: male.firstOrNull() ?: british.firstOrNull(::installed) ?: british.firstOrNull()
    }

    /** A Hindi male voice (Google's hi-IN ones), for replies in Hindi or Hinglish. */
    private fun pickHindi(voices: Set<TtsVoice>): TtsVoice? {
        val hi = voices.filter { it.locale.language == "hi" }
        fun installed(v: TtsVoice) = !v.isNetworkConnectionRequired &&
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features.orEmpty()
        val male = MALE_HINDI.flatMap { id -> hi.filter { it.name.contains(id, ignoreCase = true) } }
        return male.firstOrNull(::installed) ?: male.firstOrNull() ?: hi.firstOrNull(::installed) ?: hi.firstOrNull()
    }

    /** Says [text] (replacing anything still being said), in Hindi when the reply is Hindi or Hinglish. */
    fun speak(text: String) {
        if (!ready) return
        val inHindi = isHindi(speakable(text))
        val clean = sayName(speakable(text), inHindi).let { if (inHindi) HindiScript.forSpeech(it) else it }
        if (clean.isBlank()) return
        if (inHindi) {
            hindi?.let { tts.voice = it } ?: tts.setLanguage(Locale("hi", "IN"))
            // The Hindi voice sounds most natural at its own pitch and speed.
            tts.setPitch(HINDI_PITCH)
            tts.setSpeechRate(HINDI_RATE)
        } else {
            english?.let { tts.voice = it } ?: tts.setLanguage(Locale.UK)
            tts.setPitch(PITCH)
            tts.setSpeechRate(RATE)
        }
        // Long replies go in chunks: the engine has a per-call length limit.
        clean.chunked(3500).forEachIndexed { i, part ->
            tts.speak(part, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "xarvis-$i")
        }
    }

    fun stop() {
        if (ready) tts.stop()
    }

    /**
     * The ☰ menu's voice button: switches to the next voice of that language on the phone
     * (Hindi when [hindiVoice], else British English), says a sample, and remembers it.
     * Returns a short label for the button, or null if the phone has none.
     */
    fun nextVoice(hindiVoice: Boolean): String? {
        if (!ready) return null
        val all = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
            .filter { if (hindiVoice) it.locale.language == "hi" else it.locale.language == "en" && it.locale.country == "GB" }
            .sortedBy { it.name }
        if (all.isEmpty()) return null
        val current = if (hindiVoice) hindi else english
        val next = all[(all.indexOfFirst { it.name == current?.name } + 1) % all.size]
        if (hindiVoice) hindi = next else english = next
        prefs.edit().putString(if (hindiVoice) "voiceHindi" else "voiceEnglish", next.name).apply()
        tts.voice = next
        tts.setPitch(if (hindiVoice) HINDI_PITCH else PITCH)
        tts.setSpeechRate(if (hindiVoice) HINDI_RATE else RATE)
        tts.speak(
            if (hindiVoice) HindiScript.forSpeech(sayName("Namaste sir, main XARVIS hoon. Bataiye, kya yeh awaaz aapko theek lagti hai?", true))
            else sayName("Good evening, sir. XARVIS here. Will this voice do?", false),
            TextToSpeech.QUEUE_FLUSH, null, "xarvis-sample",
        )
        return voiceLabel(next, all.indexOf(next) + 1, all.size)
    }

    /** The label for the voice in use ("Voice 2 of 4"), for the menu. */
    fun currentLabel(hindiVoice: Boolean): String {
        val v = (if (hindiVoice) hindi else english) ?: return "phone default"
        val all = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
            .filter { it.locale.language == v.locale.language && it.locale.country == v.locale.country }.sortedBy { it.name }
        return voiceLabel(v, all.indexOfFirst { it.name == v.name } + 1, all.size)
    }

    private fun voiceLabel(v: TtsVoice, n: Int, of: Int) =
        "voice $n of $of" + if (v.isNetworkConnectionRequired) " (online)" else ""

    /** Whether XARVIS is talking right now (the wake word waits, so it doesn't hear itself). */
    val isSpeaking: Boolean get() = ready && runCatching { tts.isSpeaking }.getOrDefault(false)

    companion object {
        private const val TAG = "XarvisVoice"
        private const val GOOGLE_TTS = "com.google.android.tts"
        private const val PITCH = 0.88f
        private const val RATE = 1.0f
        private const val HINDI_PITCH = 1.0f
        private const val HINDI_RATE = 1.0f

        /**
         * "XARVIS" in capitals was read letter by letter ("X. A. R. V. I. S."): say it as a word.
         * The Hindi voice gets it in Devanagari so it doesn't spell it either.
         */
        fun sayName(text: String, hindi: Boolean): String =
            text.replace(Regex("""\bXARVIS\b""", RegexOption.IGNORE_CASE), if (hindi) "ज़ार्विस" else "Zarvis")

        /** Google's Hindi voices that sound male, best first. */
        private val MALE_HINDI = listOf("hic", "hid")

        private val HINGLISH = Regex(
            """\b(?:hai|hain|hoon|hun|kya|kyun|aap|aapka|aapke|aapko|main|mein|mujhe|hum|tum|nahi|nahin|theek|thik|sab|ke|ki|ka|liye|karna|karo|kar|raha|rahi|rahe|bataiye|batao|ji|haan|acha|accha|bilkul|zaroor|abhi|kaise|kaisa|yahan|wahan|toh|bhi)\b""",
            RegexOption.IGNORE_CASE,
        )

        /** Hindi in Devanagari, or Hinglish (Hindi in English letters, like "sab theek hai"). */
        fun isHindi(text: String): Boolean {
            if (text.any { it in '\u0900'..'\u097F' }) return true
            val words = text.split(Regex("""\s+""")).count { it.isNotBlank() }
            val hindiWords = HINGLISH.findAll(text).count()
            return hindiWords >= 3 && hindiWords * 5 >= words
        }

        /** Google's British male voices, best first. */
        private val MALE_BRITISH = listOf("rjs", "gbd", "gbb")

        /** What's worth reading aloud: no emoji, bullets or markdown symbols. */
        fun speakable(text: String): String = text
            .replace(Regex("""[\p{So}\p{Cn}\x{1F000}-\x{1FFFF}\x{2600}-\x{27BF}\x{FE0F}]"""), "")
            .replace(Regex("""[*_#`•>]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
