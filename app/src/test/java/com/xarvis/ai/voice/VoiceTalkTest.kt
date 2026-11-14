package com.xarvis.ai.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceTalkTest {
    @Test fun endingsAreHeard() {
        assertEquals("My pleasure, sir.", VoiceTalk.closing("Thank you, Xarvis."))
        assertEquals("My pleasure, sir.", VoiceTalk.closing("thanks"))
        assertEquals("Very good, sir.", VoiceTalk.closing("That's all."))
        assertEquals("Very good, sir.", VoiceTalk.closing("that’s all, sir"))
        assertEquals("Very good, sir.", VoiceTalk.closing("Bas."))
        assertEquals("Very good, sir.", VoiceTalk.closing("nothing else"))
        // An acknowledgement just ends it.
        assertEquals("", VoiceTalk.closing("Okay."))
        assertEquals("", VoiceTalk.closing("theek hai"))
        assertEquals("", VoiceTalk.closing("never mind"))
        assertEquals("", VoiceTalk.closing("Jarvis"))
    }

    @Test fun realRequestsAreNotEndings() {
        assertNull(VoiceTalk.closing("thank you for calling, now text Atiq"))
        assertNull(VoiceTalk.closing("what time is it"))
        assertNull(VoiceTalk.closing("stop the alarm"))
        assertNull(VoiceTalk.closing("okay, call my wife"))
        assertNull(VoiceTalk.closing("is my S22 charging?"))
        assertNull(VoiceTalk.closing("no, the other one"))
    }

    @Test fun yesAndNo() {
        assertEquals(true, VoiceTalk.yesNo("Yes."))
        assertEquals(true, VoiceTalk.yesNo("yes please, go ahead"))
        assertEquals(true, VoiceTalk.yesNo("haan"))
        assertEquals(true, VoiceTalk.yesNo("Okay"))
        assertEquals(true, VoiceTalk.yesNo("do it"))
        assertEquals(false, VoiceTalk.yesNo("No."))
        assertEquals(false, VoiceTalk.yesNo("no, don't call"))
        assertEquals(false, VoiceTalk.yesNo("nahi"))
        assertEquals(false, VoiceTalk.yesNo("cancel"))
        assertNull(VoiceTalk.yesNo("what did you say?"))
        assertNull(VoiceTalk.yesNo(""))
    }

    @Test fun theQuestionFitsTheWayRexIsTalking() {
        assertEquals("Shall I call Atiq, sir? Say yes or no.", VoiceTalk.question("Call Atiq", spokenConversation = true))
        assertEquals("Shall I call Atiq? Tap allow on the screen.", VoiceTalk.question("Call Atiq.", spokenConversation = false))
    }
}
