package com.xarvis.ai.agent

import com.xarvis.ai.agent.PhoneControl.Cmd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneControlTest {
    private fun p(s: String) = PhoneControl.parse(s)

    @Test fun alarmAndCalls() {
        assertEquals(Cmd.AlarmOff, p("off the alarm"))
        assertEquals(Cmd.AlarmOff, p("Xarvis, stop the alarm"))
        assertEquals(Cmd.AlarmOff, p("turn the alarm off please"))
        assertEquals(Cmd.AlarmOff, p("alarm band karo"))
        assertEquals(Cmd.Snooze, p("snooze"))
        assertEquals(Cmd.Answer, p("receive this call"))
        assertEquals(Cmd.Answer, p("answer the call"))
        assertEquals(Cmd.Answer, p("call utha lo"))
        assertEquals(Cmd.HangUp, p("disconnect this call"))
        assertEquals(Cmd.HangUp, p("hang up"))
        assertEquals(Cmd.HangUp, p("reject the call"))
        assertEquals(Cmd.HangUp, p("call kaat do"))
    }

    @Test fun youtube() {
        assertEquals(Cmd.YouTube(""), p("open YouTube"))
        assertEquals(Cmd.YouTube("iron man suit up"), p("open youtube and search for iron man suit up"))
        assertEquals(Cmd.YouTube("lofi music"), p("play lofi music on youtube"))
        assertEquals(Cmd.Scroll(true), p("scroll down"))
        assertEquals(Cmd.Scroll(false), p("scroll up"))
        assertEquals(Cmd.Scroll(true), p("show me more"))
        assertEquals(Cmd.Read, p("read the titles"))
        assertEquals(Cmd.Read, p("what do you see"))
        assertEquals(Cmd.Play(2, ""), p("play the second video"))
        assertEquals(Cmd.Play(1, ""), p("play the first one"))
        assertEquals(Cmd.Play(3, ""), p("play number 3"))
        assertEquals(Cmd.Play(null, "fight in india"), p("play fight in india"))
        assertEquals(Cmd.SkipAd, p("skip the ad"))
        assertEquals(Cmd.Back, p("go back"))
    }

    @Test fun ordinaryChatIsLeftAlone() {
        assertNull(p("what time is my alarm tomorrow"))
        assertNull(p("call Atiq"))
        assertNull(p("play music"))
        assertNull(p("tell me a joke"))
        assertNull(p("open whatsapp"))
    }

    private fun el(label: String, y: Int) = PhoneAgent.Element(0, label, true, false, false, 500, y)

    @Test fun readsYouTubeTitles() {
        val els = listOf(
            el("Search", 100),
            el("Never Get Into a FIGHT IN INDIA - 10 minutes, 28 seconds - Go to channel - Major K", 700),
            el("Iron Man Suit Up Scene 4K - 3 minutes, 2 seconds - Go to channel - Marvel - 9M v", 1300),
            el("Subscriptions", 2200),
        )
        val v = PhoneControl.videos(els, 2400)
        assertEquals(listOf("Never Get Into a FIGHT IN INDIA", "Iron Man Suit Up Scene 4K"), v.map { it.title })
        assertEquals("Major K", v[0].detail)
        assertTrue(PhoneControl.readOut(v).startsWith("1. Never Get Into a FIGHT IN INDIA, by Major K."))
        assertEquals(1300, PhoneControl.pick(v, null, "iron man")!!.y)
        assertEquals(700, PhoneControl.pick(v, 1, "")!!.y)
        assertEquals(1300, PhoneControl.pick(v, -1, "")!!.y)
    }

    @Test fun adControls() {
        val ad = listOf(el("Skip ad", 900), el("Visit advertiser", 1000))
        assertEquals("Skip ad", PhoneControl.skipButton(ad)!!.label)
        assertTrue(PhoneControl.adShowing(ad))
        assertNull(PhoneControl.skipButton(listOf(el("Skip ad in 5", 900))))
        assertTrue(!PhoneControl.adShowing(listOf(el("Subscribe", 900))))
    }
}
