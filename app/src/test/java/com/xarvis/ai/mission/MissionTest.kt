package com.xarvis.ai.mission

import com.xarvis.ai.agent.PhoneAgent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionTest {
    private fun el(n: Int, label: String, y: Int, editable: Boolean = false, password: Boolean = false, hint: String = "") =
        PhoneAgent.Element(n, label, tappable = !editable, editable = editable, password = password, x = 500, y = y, hint = hint)

    /** A phone whose screen changes as it's tapped: each label leads to the next screen. */
    private class Phone(val screens: Map<String, PhoneAgent.Screen>, start: String) : PhoneAgent.Target {
        override val name = "Test S22"
        var now: PhoneAgent.Screen = screens.getValue(start)
        val did = ArrayList<String>()
        var typed = ""
        override suspend fun begin() {}
        override suspend fun end() {}
        override suspend fun screen() = now
        override suspend fun tap(x: Int, y: Int) {
            val hit = now.elements.first { it.x == x && it.y == y }
            did += "tap ${hit.label.ifBlank { hit.hint }}"
            screens[hit.label]?.let { now = it }
        }
        override suspend fun type(text: String): Boolean { typed = text; did += "type $text"; return true }
        override suspend fun scroll(down: Boolean) { did += "scroll" }
        override suspend fun key(name: String) { did += "key $name"; screens[name]?.let { now = it } }
        override suspend fun open(app: String): String { did += "open $app"; screens[app]?.let { now = it }; return "opened $app" }
        override suspend fun stopped() = false
        override suspend fun look(): ByteArray = byteArrayOf(1, 2, 3)
    }

    private class Clock : MissionRunner.Io {
        var t = 0L
        override suspend fun pause(ms: Long) { t += ms }
        override fun now() = t
        override fun progress(line: String) {}
        override suspend fun crashes() = "java.lang.IllegalStateException: boom"
    }

    @Test fun readsTheLanguage() {
        val m = Mission.parse(
            """
            MISSION: Check Galaxy AI
            PHONE: S22
            # a comment
            open Settings
            tap "Galaxy AI"
            try tap Call assist
            long Photo 1
            tapxy 50% 30
            type hello into Search
            type plain text
            scroll down
            back
            wait 3
            wait for Connected 20
            look Galaxy page
            read
            note done here
            url https://example.com
            crashes
            dance wildly
            """.trimIndent(),
        )
        assertEquals("Check Galaxy AI", m.title)
        assertEquals("S22", m.phone)
        assertEquals(listOf("line 20: I don't understand \"dance wildly\""), m.problems)
        val k = m.steps.map { it.kind }
        assertEquals(
            listOf(
                Mission.Kind.OPEN, Mission.Kind.TAP, Mission.Kind.TAP, Mission.Kind.LONG, Mission.Kind.TAPXY, Mission.Kind.TYPE,
                Mission.Kind.TYPE, Mission.Kind.SCROLL, Mission.Kind.KEY, Mission.Kind.WAIT, Mission.Kind.WAIT_FOR,
                Mission.Kind.LOOK, Mission.Kind.READ, Mission.Kind.NOTE, Mission.Kind.URL, Mission.Kind.CRASHES,
            ),
            k,
        )
        assertEquals("Galaxy AI", m.steps[1].arg)
        assertTrue(m.steps[2].optional)
        assertEquals("0.5", m.steps[4].arg); assertEquals("0.3", m.steps[4].arg2)
        assertEquals("hello", m.steps[5].arg); assertEquals("Search", m.steps[5].arg2)
        assertEquals("Connected", m.steps[10].arg); assertEquals(20, m.steps[10].seconds)
        assertEquals("2026-11-24-galaxy", Mission.missionId("2026-11-24-galaxy.txt"))
        assertNull(Mission.missionId("../evil.sh"))
    }

    @Test fun runsTheStepsAndReports() = runBlocking {
        val settings = PhoneAgent.Screen("com.android.settings", listOf(el(1, "Connections", 200), el(2, "Galaxy AI", 400)), 1080, 2316)
        val galaxy = PhoneAgent.Screen("com.android.settings", listOf(el(1, "Call assist", 300), el(2, "Live translate", 500)), 1080, 2316)
        val phone = Phone(mapOf("Settings" to settings, "Galaxy AI" to galaxy, "home" to settings), "home")
        val m = Mission.parse("MISSION: Galaxy\nopen Settings\ntap galaxy ai\nlook Galaxy page\ntry tap Bixby\ntap Live translate\ncrashes")
        val r = MissionRunner(phone, Clock()).run(m)
        assertTrue(r.summary, r.finished)
        assertEquals(listOf("open Settings", "tap Galaxy AI", "tap Live translate"), phone.did)
        assertEquals(1, r.looks.size)
        assertTrue(r.looks[0].text.contains("Call assist · Live translate"))
        assertTrue(r.lines[3].startsWith("4. ➖")) // the "try" that missed didn't stop it
        val md = MissionRunner.markdown(r, "m1", "Rex's S22", "1.0.371", "2026-11-24 10:00")
        assertTrue(md.contains("![shot 1](shot-1.jpg)"))
        assertTrue(md.contains("boom"))
    }

    @Test fun aMissStopsWithALookAtTheScreen() = runBlocking {
        val home = PhoneAgent.Screen("launcher", listOf(el(1, "Chrome", 300)), 1080, 2316)
        val r = MissionRunner(Phone(mapOf("home" to home), "home"), Clock()).run(Mission.parse("tap Galaxy AI\nnote never reached"))
        assertFalse(r.finished)
        assertTrue(r.summary.contains("isn't on screen"))
        assertEquals("Where it stopped (step 1)", r.looks.single().title)
        assertEquals(1, r.lines.size)
    }

    @Test fun rexsRulesHold() = runBlocking {
        val pay = PhoneAgent.Screen("shop", listOf(el(1, "Pay now", 300), el(2, "Password", 600, editable = true, password = true)), 1080, 2316)
        val phone = Phone(mapOf("home" to pay), "home")
        assertFalse(MissionRunner(phone, Clock()).run(Mission.parse("tap Pay now")).finished)
        assertFalse(MissionRunner(phone, Clock()).run(Mission.parse("type secret into Password")).finished)
        assertFalse(MissionRunner(phone, Clock()).run(Mission.parse("type 4821")).finished)
        val bank = MissionRunner(phone, Clock()).run(Mission.parse("open PhonePe"))
        assertFalse(bank.finished)
        assertTrue(phone.did.isEmpty()) // nothing was pressed, typed or opened
    }

    @Test fun typesIntoTheRightBox() = runBlocking {
        val form = PhoneAgent.Screen(
            "chrome",
            listOf(el(1, "Full name", 300), el(2, "", 360, editable = true), el(3, "", 600, editable = true, hint = "Search")),
            1080, 2316,
        )
        val phone = Phone(mapOf("home" to form), "home")
        val r = MissionRunner(phone, Clock()).run(Mission.parse("type Rex into Full name\ntype jobs into Search"))
        assertTrue(r.summary, r.finished)
        assertEquals(listOf("tap ", "type Rex", "tap Search", "type jobs"), phone.did)
        assertNotNull(MissionRunner.findBox(form, "search"))
    }
}
