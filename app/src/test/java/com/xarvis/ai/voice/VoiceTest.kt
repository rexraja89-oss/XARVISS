package com.xarvis.ai.voice

import com.xarvis.ai.agent.XarvisAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTest {
    @Test fun readsWordsNotSymbols() {
        assertEquals("No clouds for me, sir.", Voice.speakable("No clouds for me, sir. 😂"))
        assertEquals("Passport Visa", Voice.speakable("• **Passport**\n• Visa"))
    }

    @Test fun promptGivesXarvisAPersonality() {
        val prompt = XarvisAgent.buildPrompt(emptyList())
        assertTrue(prompt.contains("like JARVIS from Iron Man"))
        assertTrue(prompt.contains("never robotic"))
        // Humour never bends the facts (it once called 85% battery "running on fumes").
        assertTrue(prompt.contains("never exaggerate or change facts"))
    }

    @Test fun hearsWhenAReplyIsHindi() {
        assertTrue(Voice.isHindi("Aur, sir, sab theek hai. Main toh aapke liye yahan hoon, har kaam karne ke liye taiyaar."))
        assertTrue(Voice.isHindi("जी सर, बिल्कुल।"))
        assertFalse(Voice.isHindi("Speak regular Hindi like Iron Man? Well, I do my best, sir."))
        assertFalse(Voice.isHindi("You're at 78%, sir. That's good for now."))
    }

    @Test fun saysTheNameAsAWord() {
        assertEquals("Good evening, sir. Zarvis here.", Voice.sayName("Good evening, sir. XARVIS here.", hindi = false))
        assertEquals("main ज़ार्विस hoon", Voice.sayName("main XARVIS hoon", hindi = true))
    }

    @Test fun hindiVoiceReadsHindiInItsOwnScript() {
        assertEquals("जी sir, सब ठीक है. File तैयार है.", HindiScript.forSpeech("Ji sir, sab theek hai. File taiyaar hai."))
        assertEquals("मैं ज़ार्विस हूँ", HindiScript.forSpeech(Voice.sayName("main XARVIS hoon", hindi = true)))
    }
}
