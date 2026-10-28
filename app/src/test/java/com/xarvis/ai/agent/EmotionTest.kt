package com.xarvis.ai.agent

import com.xarvis.ai.agent.Emotion.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmotionTest {

    @Test fun readsTheMood() {
        assertEquals(Tone.DISTRESS, Emotion.detect("help me, I'm in a situation!"))
        assertEquals(Tone.ANXIOUS, Emotion.detect("I'm so worried about tomorrow"))
        assertEquals(Tone.SAD, Emotion.detect("I feel so sad and lonely today"))
        assertEquals(Tone.FRUSTRATED, Emotion.detect("this is so frustrating, ugh"))
        assertEquals(Tone.EXCITED, Emotion.detect("I'm so excited, can't wait!"))
        assertEquals(Tone.JOY, Emotion.detect("I'm so happy, great news!"))
        assertEquals(Tone.NEUTRAL, Emotion.detect("what is the capital of France"))
    }

    @Test fun distressBeatsOtherTones() {
        // Safety-ish: a cry for help wins even if other words are present.
        assertEquals(Tone.DISTRESS, Emotion.detect("I'm so angry, bachao!"))
    }

    @Test fun neutralHasNoPromptHint() {
        assertEquals("", Emotion.promptHint(Tone.NEUTRAL))
        assertTrue(Emotion.promptHint(Tone.DISTRESS).isNotEmpty())
    }

    @Test fun voiceScalesAreSane() {
        // Distress = calmer/slower than neutral; excitement = brighter/faster.
        assertTrue(Emotion.rateScale(Tone.DISTRESS) < 1f)
        assertTrue(Emotion.rateScale(Tone.EXCITED) > 1f)
        assertEquals(1f, Emotion.rateScale(Tone.NEUTRAL), 0.001f)
        assertEquals(1f, Emotion.pitchScale(Tone.NEUTRAL), 0.001f)
    }
}
