package com.xarvis.ai.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenEyesTest {
    @Test fun opensEyes() {
        listOf("look", "Look", "take a look", "open your eyes", "open eyes", "XARVIS Eyes", "hey jarvis, look", "Jarvis look")
            .forEach { assertTrue(it, XarvisAgent.OPEN_EYES.matches(it)) }
    }

    @Test fun ordinarySentencesDont() {
        listOf("look at this code", "what does it look like", "open youtube", "my eyes hurt")
            .forEach { assertFalse(it, XarvisAgent.OPEN_EYES.matches(it)) }
    }
}

class PhotoCommandTest {
    @Test fun plainPhotoCommandsOpenTheCamera() {
        val rear = com.xarvis.ai.workflow.Step.TakePhoto(selfie = false)
        val front = com.xarvis.ai.workflow.Step.TakePhoto(selfie = true)
        org.junit.Assert.assertEquals(rear, XarvisAgent.photoCommand("open rear camera and take a photo"))
        org.junit.Assert.assertEquals(rear, XarvisAgent.photoCommand("take a photo"))
        org.junit.Assert.assertEquals(rear, XarvisAgent.photoCommand("Hey Jarvis, take a picture."))
        org.junit.Assert.assertEquals(front, XarvisAgent.photoCommand("take a selfie"))
        org.junit.Assert.assertEquals(front, XarvisAgent.photoCommand("open front camera and take a photo"))
        org.junit.Assert.assertEquals(front, XarvisAgent.photoCommand("take a photo with the front camera"))
    }

    @Test fun longerRequestsToo() {
        val rear = com.xarvis.ai.workflow.Step.TakePhoto(selfie = false)
        val front = com.xarvis.ai.workflow.Step.TakePhoto(selfie = true)
        // Typed on the benco (it has no camera): this once went to Gemma, which claimed it had taken the photo.
        org.junit.Assert.assertEquals(front, XarvisAgent.photoCommand("hey xarvis take photo with front camera and show me here in benco screen."))
        org.junit.Assert.assertEquals(rear, XarvisAgent.photoCommand("take a picture and send it to me"))
        org.junit.Assert.assertEquals(rear, XarvisAgent.photoCommand("click a photo from the v91s", setOf("v91s", "plus")))
    }

    @Test fun questionsAboutPhotosDont() {
        org.junit.Assert.assertNull(XarvisAgent.photoCommand("how do I take a good photo of the moon"))
        org.junit.Assert.assertNull(XarvisAgent.photoCommand("which phone takes the best photo"))
        org.junit.Assert.assertNull(XarvisAgent.photoCommand("rear camera price for my car"))
        org.junit.Assert.assertNull(XarvisAgent.photoCommand("take a photo of the moon tonight"))
        org.junit.Assert.assertNull(XarvisAgent.photoCommand("remind me to take a photo at 5"))
    }
}

class WhoIsThisTest {
    @Test fun asksWhoItIs() {
        listOf("who is this", "Who's this?", "hey jarvis, who is this", "do you recognise her", "do you recognize this person", "who is in front of me", "who is in this photo", "whose face is this")
            .forEach { org.junit.Assert.assertNotNull(it, XarvisAgent.whoCommand(it)) }
    }

    @Test fun otherQuestionsDont() {
        listOf("who is the president of india", "who is this song by", "do you know python")
            .forEach { org.junit.Assert.assertNull(it, XarvisAgent.whoCommand(it)) }
    }
}

class WhoCommandTest {
    private fun who(m: String) = XarvisAgent.whoCommand(m)

    @Test fun recognisesRexWithTheFrontCamera() {
        val front = com.xarvis.ai.workflow.Step.WhoIsThis(front = true)
        org.junit.Assert.assertEquals(front, who("see who I am by front camera"))
        org.junit.Assert.assertEquals(front, who("who I am check with front camera"))
        org.junit.Assert.assertEquals(front, who("who am I"))
        org.junit.Assert.assertEquals(front, who("do you recognise me?"))
        org.junit.Assert.assertEquals(front, who("hey jarvis, who is this with the front camera"))
    }

    @Test fun otherPeopleWithTheBackCamera() {
        val back = com.xarvis.ai.workflow.Step.WhoIsThis(front = false)
        org.junit.Assert.assertEquals(back, who("who is this"))
        org.junit.Assert.assertEquals(back, who("who is she"))
        org.junit.Assert.assertEquals(back, who("who am i with the back camera"))
        org.junit.Assert.assertEquals(back, who("who is in front of me"))
        // Typed on the benco with a photo attached (Nov 2026): it went to Gemma instead of face recognition.
        org.junit.Assert.assertEquals(back, who("Ask s22 ultra who is in photo"))
        org.junit.Assert.assertEquals(back, who("who is in this picture"))
        org.junit.Assert.assertEquals(back, who("who are in this photo"))
    }

    @Test fun notAWhoQuestion() {
        org.junit.Assert.assertNull(who("take a selfie"))
        org.junit.Assert.assertNull(who("who am i talking to"))
        org.junit.Assert.assertNull(who("who is the president"))
    }
}
