package com.xarvis.ai.watch

import com.xarvis.ai.watch.WatchCommands.Cmd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchCommandsTest {
    @Test fun commands() {
        assertEquals(Cmd.Start(8, false), WatchCommands.parse("watch the room"))
        assertEquals(Cmd.Start(3, false), WatchCommands.parse("Xarvis, watch the room for 3 hours"))
        assertEquals(Cmd.Start(8, true), WatchCommands.parse("watch the room on my S22"))
        assertEquals(Cmd.Start(5, true), WatchCommands.parse("watch my house on my s22 for 5 hours"))
        assertEquals(Cmd.Start(24, false), WatchCommands.parse("watch mode on for 40 hours"))
        assertEquals(Cmd.Stop(false), WatchCommands.parse("stop watching"))
        assertEquals(Cmd.Stop(true), WatchCommands.parse("watch mode off on my S22"))
        assertEquals(Cmd.Snap(false), WatchCommands.parse("send me a photo of the room"))
        assertEquals(Cmd.Snap(false), WatchCommands.parse("what's happening in the room?"))
        assertEquals(Cmd.Status(false), WatchCommands.parse("are you watching"))
        assertNull(WatchCommands.parse("watch a movie"))
        assertNull(WatchCommands.parse("stop the alarm"))
    }
}
