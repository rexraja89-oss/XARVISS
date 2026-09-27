package com.xarvis.ai.voice

import com.xarvis.ai.agent.XarvisAgent
import org.junit.Assert.assertEquals
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
}
