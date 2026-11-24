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

    @Test fun clearsReplacesAndDownloads() = runBlocking {
        val form = PhoneAgent.Screen("chrome", listOf(el(1, "TheFreeVersion-r5c", 400, editable = true)), 1080, 2316)
        val set = ArrayList<String>()
        val phone = object : PhoneAgent.Target by Phone(mapOf("home" to form), "home") {
            override suspend fun setText(x: Int, y: Int, text: String): Boolean { set += text; return true }
        }
        val io = object : MissionRunner.Io by Clock() {
            override suspend fun download(path: String) = "saved $path"
        }
        val m = Mission.parse("clear TheFreeVersion\ntype TheFreeVersionTech into TheFreeVersion\ndownload channel/logo.png")
        assertTrue(m.problems.isEmpty())
        val r = MissionRunner(phone, io).run(m)
        assertTrue(r.summary, r.finished)
        assertEquals(listOf("", "TheFreeVersionTech"), set) // emptied, then exactly the new text: nothing added on
        assertTrue(r.lines[2].contains("saved channel/logo.png"))
        assertNull(Mission.step(1, "download ../secret"))
    }

    @Test fun ifStepsLookOnceAndTryTapsWaitLess() = runBlocking {
        val m = Mission.parse("if Skip ad: tap Skip ad\nif \"Connect\": tap Connect\ntry tap Missing\ntap Missing")
        assertTrue(m.problems.isEmpty())
        assertEquals("Skip ad", m.steps[0].onlyIf); assertEquals(Mission.Kind.TAP, m.steps[0].kind); assertTrue(m.steps[0].optional)
        assertEquals("Connect", m.steps[1].onlyIf)
        val screen = PhoneAgent.Screen("vpn", listOf(el(1, "Connect", 300)), 1080, 2316)
        val phone = Phone(mapOf("home" to screen), "home")
        val clock = Clock()
        val r = MissionRunner(phone, clock).run(m)
        assertEquals(listOf("tap Connect"), phone.did) // the absent "Skip ad" was skipped without a tap
        assertTrue(r.lines[0], r.lines[0].contains("skipped"))
        assertTrue(r.lines[2], r.lines[2].contains("waited 3 s"))
        assertTrue(r.lines[3], r.lines[3].contains("waited 8 s"))
        assertFalse(r.finished)
        assertTrue(clock.t < 16_000) // skipped step 1 cost no wait at all
    }

    @Test fun swipesSidewaysAndUploads() = runBlocking {
        val m = Mission.parse("swipe left at 30%\nswipe right\nswipe 80% 50% 20% 50%\nupload recording\nupload newest screenshot\nswipe sideways\nupload my bank app")
        assertEquals(listOf("line 6: I don't understand \"swipe sideways\"", "line 7: I don't understand \"upload my bank app\""), m.problems)
        assertEquals("0.85,0.3", m.steps[0].arg); assertEquals("0.15,0.3", m.steps[0].arg2)
        assertEquals("0.15,0.5", m.steps[1].arg)
        assertEquals("0.8,0.5", m.steps[2].arg); assertEquals("0.2,0.5", m.steps[2].arg2)
        assertEquals("recording", m.steps[3].arg); assertEquals("screenshot", m.steps[4].arg)
        val swiped = ArrayList<String>()
        val phone = object : PhoneAgent.Target by Phone(mapOf("home" to PhoneAgent.Screen("x", emptyList(), 1080, 2316)), "home") {
            override suspend fun swipe(x: Float, y: Float, x2: Float, y2: Float): Boolean { swiped += "$x,$y>$x2,$y2"; return true }
        }
        val io = object : MissionRunner.Io by Clock() {
            override suspend fun upload(what: String) = "uploaded the newest $what"
        }
        val r = MissionRunner(phone, io).run(Mission.parse("swipe left\nupload recording"))
        assertTrue(r.summary, r.finished)
        assertEquals(listOf("0.85,0.5>0.15,0.5"), swiped)
        assertTrue(r.lines[1].contains("uploaded the newest recording"))
    }
}
