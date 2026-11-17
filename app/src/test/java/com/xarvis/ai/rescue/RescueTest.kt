package com.xarvis.ai.rescue

import com.xarvis.ai.rescue.RescueCommands.Cmd
import com.xarvis.ai.rescue.RescueLogic.State
import com.xarvis.ai.rescue.RescueLogic.Trick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RescueTest {
    @Test
    fun commands() {
        assertEquals(Cmd.Rescue, RescueCommands.parse("rescue my S22"))
        assertEquals(Cmd.Rescue, RescueCommands.parse("Xarvis, rescue my S22 Ultra please"))
        assertEquals(Cmd.Rescue, RescueCommands.parse("my s22 is stuck"))
        assertEquals(Cmd.Rescue, RescueCommands.parse("get my S22 unstuck"))
        assertEquals(Cmd.Wake, RescueCommands.parse("ask s22 ultra to wake up"))
        assertEquals(Cmd.Wake, RescueCommands.parse("wake up my S22"))
        assertEquals(Cmd.Wake, RescueCommands.parse("wake my galaxy"))
        assertEquals(Cmd.Wake, RescueCommands.parse("turn on my S22's screen"))
        assertEquals(Cmd.Help, RescueCommands.parse("call for help on my S22"))
        assertEquals(Cmd.Help, RescueCommands.parse("ask someone to turn my s22 face up"))
        assertEquals(Cmd.Stop, RescueCommands.parse("stop the rescue"))
        assertEquals(Cmd.Restart, RescueCommands.parse("restart my S22"))
        assertEquals(Cmd.Restart, RescueCommands.parse("Xarvis, reboot my phone"))
        assertEquals(Cmd.RestartYes, RescueCommands.parse("yes, restart my S22"))
        assertEquals(Cmd.RestartYes, RescueCommands.parse("Yes restart my s22 ultra now"))
        assertEquals(Cmd.Restart, RescueCommands.parse("Restart"))
        assertEquals(Cmd.RestartYes, RescueCommands.parse("yes restart"))
        assertNull(RescueCommands.parse("restart the video"))
        assertNull(RescueCommands.parse("wake me up at 7"))
        assertNull(RescueCommands.parse("help me write a letter"))
        assertNull(RescueCommands.parse("rescue the cat from the tree"))
    }

    @Test
    fun readsTheScreen() {
        val guard = "[System UI]\nAccidental touch protection\nYour phone is being protected from accidental touches.\nDraw unlock pattern\nSwipe up to dismiss accidental touch protection."
        assertEquals(State.TOUCH_GUARD, RescueLogic.state(true, true, guard, padFound = true))
        assertEquals(State.PAD, RescueLogic.state(true, true, "[System UI]\nDraw unlock pattern\nEmergency call", false))
        assertEquals(State.PAD, RescueLogic.state(true, true, "", padFound = true))
        assertEquals(State.LOCKED, RescueLogic.state(true, true, "[System UI]\n3:27\nMonday 17 November", false))
        assertEquals(State.UNLOCKED, RescueLogic.state(true, false, "[One UI Home]", false))
        assertEquals(State.ASLEEP, RescueLogic.state(false, true, guard, false))
        assertTrue(RescueLogic.done(State.PAD))
        assertFalse(RescueLogic.done(State.TOUCH_GUARD))
    }

    @Test
    fun tricksAndSensor() {
        assertEquals(Trick.SWIPE_PILL, RescueLogic.tricks(State.TOUCH_GUARD).first())
        assertEquals(Trick.HOME, RescueLogic.tricks(State.LOCKED).first())
        assertTrue(RescueLogic.tricks(State.PAD).isEmpty())
        assertTrue(RescueLogic.near(0f, 5f))
        assertFalse(RescueLogic.near(5f, 5f))
        assertFalse(RescueLogic.near(8f, 8f))
        assertTrue(RescueLogic.describe(State.TOUCH_GUARD, true).contains("covered"))
    }
}
