package com.xarvis.ai.task

import com.xarvis.ai.task.TaskCommands.Cmd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskCommandsTest {
    @Test fun startingATask() {
        assertEquals(Cmd.Start("open Ninja VPN and connect", null), TaskCommands.parse("task: open Ninja VPN and connect"))
        assertEquals(Cmd.Start("open Ninja VPN and connect", "S22"), TaskCommands.parse("task on my S22: open Ninja VPN and connect"))
        assertEquals(Cmd.Start("make a comic about a cat", "s22 ultra"), TaskCommands.parse("Hey Jarvis, task on s22 ultra - make a comic about a cat"))
        assertEquals(Cmd.Start("make a YouTube Short about Mars", null), TaskCommands.parse("big task: make a YouTube Short about Mars"))
        assertNull(TaskCommands.parse("task: hi"))
        assertNull(TaskCommands.parse("what's my task for today?"))
    }

    @Test fun answeringStoppingAndAsking() {
        assertEquals(Cmd.Answer("I've signed in"), TaskCommands.parse("answer: I've signed in"))
        assertEquals(Cmd.Answer("skip"), TaskCommands.parse("task answer - skip"))
        assertEquals(Cmd.Stop, TaskCommands.parse("stop the task"))
        assertEquals(Cmd.Stop, TaskCommands.parse("Cancel task on my S22!"))
        assertEquals(Cmd.Status, TaskCommands.parse("task status"))
        assertEquals(Cmd.Status, TaskCommands.parse("how's the task going?"))
        assertNull(TaskCommands.parse("stop the music"))
        assertNull(TaskCommands.parse("the answer is 42"))
    }
}
