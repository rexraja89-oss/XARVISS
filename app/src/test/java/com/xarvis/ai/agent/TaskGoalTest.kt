package com.xarvis.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskGoalTest {
    @Test fun aTaskIsSaidWithTask() {
        assertEquals("make a YouTube Short about Mars", XarvisAgent.taskGoal("task: make a YouTube Short about Mars"))
        assertEquals("make a comic about a cat", XarvisAgent.taskGoal("Hey Jarvis, complex task - make a comic about a cat"))
        assertNull(XarvisAgent.taskGoal("what's my task for today?"))
        assertNull(XarvisAgent.taskGoal("task: hi"))
    }
}
