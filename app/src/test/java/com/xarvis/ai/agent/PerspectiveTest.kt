package com.xarvis.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class PerspectiveTest {
    @Test fun rewritesFactsToSecondPerson() {
        assertEquals("your name is Rex", XarvisAgent.toSecondPerson("my name is Rex"))
        assertEquals("You are a developer", XarvisAgent.toSecondPerson("I am a developer"))
        assertEquals("you're from Lahore", XarvisAgent.toSecondPerson("i'm from Lahore"))
    }
}
