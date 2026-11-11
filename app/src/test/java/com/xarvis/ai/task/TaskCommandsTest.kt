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

    @Test fun aJobInPlainWordsIsATaskToo() {
        // Rex's exact words (12 Nov 2026), which only opened the app.
        val rex = "open Ninja VPN and connect it. Wait for any ads to finish and close them. When it shows connected, tell me \"Ninja VPN is connected\"."
        assertEquals(Cmd.Start(rex, null, "Ninja VPN", implicit = true), TaskCommands.parse(rex))
        assertEquals(
            Cmd.Start("open YouTube on my S22 and play Arijit Singh", "S22", "YouTube", implicit = true),
            TaskCommands.parse("Hey Jarvis, please open YouTube on my S22 and play Arijit Singh"),
        )
        assertEquals("Chrome", (TaskCommands.parse("open Chrome, search for Mars facts and open the first result") as Cmd.Start).app)
        assertEquals("settings", (TaskCommands.parse("open settings then turn on dark mode") as Cmd.Start).app)
        // Not jobs for Task mode: just opening, reading, or not about the phone.
        listOf(
            "open WhatsApp", "open whatsapp on my s22 and read my last message", "open the door and let me in",
            "open Ninja VPN", "how do I open the settings and what's there?",
        ).forEach { assertNull(it, TaskCommands.parse(it)) }
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
