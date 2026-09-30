package com.xarvis.ai.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The privacy guard that keeps sensitive messages on the phone even when the cloud is on. */
class CloudRoutingTest {
    @Test fun sensitiveMessagesStayOnDevice() {
        assertTrue(XarvisAgent.isSensitive("what is my bank account number"))
        assertTrue(XarvisAgent.isSensitive("my OTP is 482913"))
        assertTrue(XarvisAgent.isSensitive("remember my passport number"))
        assertTrue(XarvisAgent.isSensitive("my card cvv"))
        assertTrue(XarvisAgent.isSensitive("code 728301"))
    }

    @Test fun ordinaryChatCanUseTheCloud() {
        assertFalse(XarvisAgent.isSensitive("what's the capital of Brazil?"))
        assertFalse(XarvisAgent.isSensitive("explain how a jet engine works"))
        assertFalse(XarvisAgent.isSensitive("write me a poem about the sea"))
    }
}
