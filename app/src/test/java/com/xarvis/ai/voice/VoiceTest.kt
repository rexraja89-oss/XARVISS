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
    }

    @Test fun hearsWhenAReplyIsHindi() {
        assertTrue(Voice.isHindi("Aur, sir, sab theek hai. Main toh aapke liye yahan hoon, har kaam karne ke liye taiyaar."))
        assertTrue(Voice.isHindi("जी सर, बिल्कुल।"))
        assertFalse(Voice.isHindi("Speak regular Hindi like Iron Man? Well, I do my best, sir."))
        assertFalse(Voice.isHindi("You're at 78%, sir. That's good for now."))
    }
}
