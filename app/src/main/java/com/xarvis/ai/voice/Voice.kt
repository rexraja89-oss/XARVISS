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

    @Volatile private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            setUp()
            ready = true
        } else {
            Log.w(TAG, "Text-to-speech unavailable ($status)")
        }
    }

    private fun setUp() {
        val voices = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
        pick(voices)?.let { tts.voice = it } ?: tts.setLanguage(Locale.UK)
        tts.setPitch(PITCH)
        tts.setSpeechRate(RATE)
        Log.i(TAG, "Voice: ${tts.voice?.name}")
    }

    /** A British male voice if the phone has one (Google's are named like "en-gb-x-rjs-local"). */
    private fun pick(voices: Set<TtsVoice>): TtsVoice? {
        val british = voices.filter { it.locale.language == "en" && it.locale.country == "GB" && !it.isNetworkConnectionRequired }
            .ifEmpty { voices.filter { it.locale.language == "en" && it.locale.country == "GB" } }
        return MALE_BRITISH.firstNotNullOfOrNull { id -> british.firstOrNull { it.name.contains(id, ignoreCase = true) } }
            ?: british.firstOrNull { it.name.contains("male", ignoreCase = true) && !it.name.contains("female", ignoreCase = true) }
            ?: british.firstOrNull()
    }

    /** Says [text] (replacing anything still being said). */
    fun speak(text: String) {
        if (!ready) return
        val clean = speakable(text)
        if (clean.isBlank()) return
        // Long replies go in chunks: the engine has a per-call length limit.
        clean.chunked(3500).forEachIndexed { i, part ->
            tts.speak(part, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "xarvis-$i")
        }
    }

    fun stop() {
        if (ready) tts.stop()
    }

    companion object {
        private const val TAG = "XarvisVoice"
        private const val PITCH = 0.88f
        private const val RATE = 1.0f

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
