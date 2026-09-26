package com.xarvis.ai.agent

import com.xarvis.ai.tools.ContactsTool
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpQuestionTest {
    @Test fun recognisesAskingWhatXarvisCanDo() {
        listOf("what help can you do", "what can you do for me", "what are your features", "which commands do you know")
            .forEach { assertTrue(it, XarvisAgent.HELP_QUESTION.containsMatchIn(it)) }
        listOf("how can you help me with my homework", "what is the capital of france")
            .forEach { assertFalse(it, XarvisAgent.HELP_QUESTION.containsMatchIn(it)) }
    }

    @Test fun contactMisspellingsStillCount() {
        listOf("contact", "contacts", "contack", "contect").forEach {
            assertTrue(it, ContactsTool.SAYS_CONTACT.containsMatchIn("my $it atiq"))
        }
        assertFalse(ContactsTool.SAYS_CONTACT.containsMatchIn("number for pizza"))
    }
}
