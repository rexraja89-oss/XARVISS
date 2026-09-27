package com.xarvis.ai.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceScriptTest {
    @Test fun everySentenceIsReadyToRecord() {
        assertTrue(VoiceScript.english.size >= 100)
        assertTrue(VoiceScript.hindi.size >= 100)
        VoiceScript.english.forEach { assertTrue(it.say, it.say.isNotBlank() && '|' !in it.say) }
        // Hindi is trained from Devanagari, and shown to Rex in English letters.
        VoiceScript.hindi.forEach {
            assertTrue(it.say, it.say.any { c -> c in 'ऀ'..'ॿ' })
            assertTrue(it.show, it.show.none { c -> c in 'ऀ'..'ॿ' })
        }
    }

    @Test fun trimsTheSilenceAroundTheWords() {
        val quiet = ShortArray(VoiceRecorder.RATE)
        val words = ShortArray(VoiceRecorder.RATE) { 5000 }
        val clip = VoiceRecorder.trim(quiet + words + quiet)
        assertEquals(VoiceRecorder.RATE + 2 * (VoiceRecorder.RATE / 4), clip.size)
        assertEquals(0, VoiceRecorder.trim(quiet).size)
    }
}
