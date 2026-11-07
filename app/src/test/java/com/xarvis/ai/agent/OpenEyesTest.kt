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

    @Test fun questionsAboutPhotosDont() {
        org.junit.Assert.assertNull(XarvisAgent.photoCommand("how do I take a good photo of the moon"))
        org.junit.Assert.assertNull(XarvisAgent.photoCommand("which phone takes the best photo"))
        org.junit.Assert.assertNull(XarvisAgent.photoCommand("rear camera price for my car"))
    }
}
