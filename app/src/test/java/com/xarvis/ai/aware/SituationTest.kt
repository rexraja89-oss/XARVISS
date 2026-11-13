package com.xarvis.ai.aware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class SituationTest {
    private val zone = TimeZone.getTimeZone("Asia/Dubai")
    private val now = 1_763_000_000_000L
    private val min = 60_000L

    /** The benco at the plant, with Rex, on 4G. */
    private val benco = Situation.Card(
        id = "b", name = "benco V91s Plus", at = now, app = "1.0.291", battery = 38, net = "mobile",
        screenOn = true, locked = false, usedAt = now - 2 * min, lat = 25.10, lon = 55.20, locAt = now - 10 * min,
    )

    /** The S22 at home, on Wi-Fi, charging, locked. */
    private val s22 = Situation.Card(
        id = "s", name = "Galaxy S22 Ultra", at = now - min, app = "1.0.291", battery = 62, charging = true, tempC = 31.0,
        net = "wifi", screenOn = false, locked = true, usedAt = now - 41 * min, unlockedAt = now - 41 * min,
        lat = 25.20, lon = 55.27, locAt = now - 30 * min, brain = "Gemma ready", doing = listOf("night shift 23:45"), freeGb = 40.0,
        notes = listOf(Situation.Note("WhatsApp", "Mom", "Are you coming home?", now - 20 * min)),
    )

    @Test fun cardsSurviveTheTrip() {
        val back = Situation.Card.parse(s22.toJson().toString())!!
        assertEquals(s22, back)
        assertNull(Situation.Card.parse("not json"))
        assertNull(Situation.Card.parse("{}"))
    }

    @Test fun statusReport() {
        val r = Situation.report(benco, listOf(s22), now, zone)
        assertTrue(r, r.startsWith("🛰 Status report, sir"))
        assertTrue(r, r.contains("📱 Galaxy S22 Ultra · seen 1 min ago"))
        assertTrue(r, r.contains("🔋 62% ⚡ charging · 🌐 Wi-Fi · screen off · last used 41 min ago"))
        assertTrue(r, r.contains("XARVIS v1.0.291 · Gemma ready · night shift 23:45"))
        assertTrue(r, r.contains("📩 1 notification: WhatsApp — Mom"))
        assertTrue(r, r.contains("km from you"))
        assertTrue(r, r.contains("📱 benco V91s Plus (this phone)"))
        assertTrue(r, r.endsWith("All systems normal."))
        // Silence and different versions are called out.
        val old = s22.copy(at = now - 3 * 3_600_000L, app = "1.0.288")
        val r2 = Situation.report(benco, listOf(old), now, zone)
        assertTrue(r2, r2.contains("📡 No word from the Galaxy S22 Ultra for 3 h."))
        assertTrue(r2, r2.contains("⬆ The Galaxy S22 Ultra runs v1.0.288 and this phone v1.0.291."))
    }

    @Test fun theBrainGetsShortFacts() {
        val b = Situation.brief(benco, listOf(s22), now, zone)
        assertTrue(b, b.contains("- Galaxy S22 Ultra: seen 1 min ago, battery 62% charging, on Wi-Fi, screen off · last used 41 min ago"))
        assertTrue(b, b.contains("recent notifications: WhatsApp Mom: \"Are you coming home?\""))
        assertTrue(b, b.contains("- benco V91s Plus (this phone, where Rex is typing): battery 38% not charging, on mobile data"))
        assertTrue(Situation.brief(benco, List(20) { s22.copy(id = "x$it") }, now, zone).length <= 900)
    }

    @Test fun batteryWarningsComeOnceWhenTheyCrossTheLine() {
        val low = s22.copy(battery = 14, charging = false)
        assertEquals(listOf("🔋 Sir, the Galaxy S22 Ultra is at 14% and not charging."), Situation.alerts(benco, s22, low, now, zone))
        assertTrue(Situation.alerts(benco, low, low.copy(battery = 12), now, zone).isEmpty())
        val critical = Situation.alerts(benco, low, low.copy(battery = 5), now, zone)
        assertTrue(critical.single(), critical.single().contains("down to 5%"))
        // Charging: nothing to say. First card from a device: no history, nothing said.
        assertTrue(Situation.alerts(benco, s22, s22.copy(battery = 10), now, zone).isEmpty())
        assertTrue(Situation.alerts(benco, null, low, now, zone).isEmpty())
    }

    @Test fun someoneUnlockingThePhoneRexIsAwayFrom() {
        val unlocked = s22.copy(unlockedAt = now - min, usedAt = now - min, locked = false, screenOn = true)
        val a = Situation.alerts(benco, s22, unlocked, now, zone)
        assertTrue(a.toString(), a.single().startsWith("🔓 Sir, the Galaxy S22 Ultra was just unlocked"))
        assertTrue(a.single(), a.single().contains("km away"))
        assertTrue(a.single(), a.single().endsWith("If that wasn't you through the remote view, someone is using it."))
        // Rex unlocking it himself through XARVIS's remote view: nothing.
        val remote = unlocked.copy(doing = listOf("🖥 ${Situation.CONTROLLED_BY} benco V91s Plus"))
        assertTrue(Situation.alerts(benco, s22, remote, now, zone).isEmpty())
        // Without locations: Rex on 4G, the S22 on Wi-Fi still counts as apart.
        val noLoc = benco.copy(lat = null, lon = null)
        assertTrue(Situation.alerts(noLoc, s22, unlocked, now, zone).single().contains("while you're away from it"))
        // Rex isn't using this phone (he may be at the S22 himself): nothing.
        assertTrue(Situation.alerts(benco.copy(usedAt = now - 3 * 3_600_000L), s22, unlocked, now, zone).isEmpty())
        // Both at home on Wi-Fi, no locations: can't tell, nothing.
        val home = noLoc.copy(net = "wifi")
        assertTrue(Situation.alerts(home, s22, unlocked, now, zone).isEmpty())
    }

    @Test fun missedCallsAreReportedOnce() {
        val call = Situation.Note("Phone", "Mom", "Missed call", now - 2 * min)
        val withCall = s22.copy(at = now, notes = listOf(call) + s22.notes)
        assertEquals(listOf("📞 Missed call on the Galaxy S22 Ultra: Mom (" + Situation.clock(call.at, zone) + ")."), Situation.alerts(benco, s22, withCall, now, zone))
        assertTrue(Situation.alerts(benco, withCall, withCall.copy(at = now + min), now, zone).isEmpty())
        assertTrue(Situation.Note("WhatsApp", "Ali", "Missed voice call", 0).missedCall)
        assertTrue(Situation.Note("WhatsApp", "2 missed calls", "", 0).missedCall)
        assertFalse(Situation.Note("WhatsApp", "Ali", "I missed the bus", 0).missedCall)
    }

    @Test fun hotAndStorage() {
        val hot = Situation.alerts(benco, s22, s22.copy(tempC = 47.5), now, zone)
        assertEquals(listOf("🌡 Sir, the Galaxy S22 Ultra is running hot: 47°C."), hot)
        assertTrue(Situation.alerts(benco, s22, s22.copy(freeGb = 1.2), now, zone).single().contains("1.2 GB"))
    }

    @Test fun notificationsAreKeptShortAndCodesHidden() {
        val otp = Situation.note("Messages", "Bank", "Your OTP is 482913. Do not share it.", now)!!
        assertFalse(otp.text, otp.text.contains("482913"))
        assertTrue(otp.text, otp.text.contains("••••"))
        // Ordinary numbers stay.
        assertEquals("Meet at 7, gate 12", Situation.note("WhatsApp", "Ali", "Meet at 7, gate 12", now)!!.text)
        assertNull(Situation.note("X", " ", " ", now))
        var list = emptyList<Situation.Note>()
        repeat(15) { i -> list = Situation.addNote(list, Situation.note("WhatsApp", "N$i", "hi", now + i)!!) }
        assertEquals(Situation.NOTES_KEPT, list.size)
        assertEquals("N14", list.first().title)
        // The same notification again doesn't pile up; very old ones go.
        list = Situation.addNote(list, Situation.note("WhatsApp", "N14", "hi", now + 20)!!)
        assertEquals(1, list.count { it.title == "N14" })
        assertEquals(1, Situation.addNote(list, Situation.note("A", "late", "x", now + 7 * 3_600_000L)!!).size)
    }

    @Test fun words() {
        assertEquals("just now", Situation.ago(20_000))
        assertEquals("41 min ago", Situation.ago(41 * min))
        assertEquals("2 h 10 min ago", Situation.ago(130 * min))
        assertEquals("3 h ago", Situation.ago(181 * min))
        assertEquals("2 days ago", Situation.ago(50 * 60 * min))
        assertTrue(Situation.lostLine(s22, now + 50 * min).startsWith("📡 I've lost contact with the Galaxy S22 Ultra, sir — last seen 51 min ago at 62%, charging"))
    }

    @Test fun commands() {
        for (s in listOf("status report", "Status", "sitrep", "Jarvis, status report", "give me a status update", "what's going on?",
            "how are my phones", "all systems status", "check my devices", "situation report please")) {
            assertEquals(s, AwareCommands.Cmd.Report, AwareCommands.parse(s))
        }
        assertEquals(AwareCommands.Cmd.Notes("S22"), AwareCommands.parse("what's new on my S22?"))
        assertEquals(AwareCommands.Cmd.Notes("s22"), AwareCommands.parse("show notifications on my s22"))
        assertEquals(AwareCommands.Cmd.Notes(null, callsOnly = true), AwareCommands.parse("any missed calls?"))
        assertEquals(AwareCommands.Cmd.Notes("the S22".removePrefix("the "), callsOnly = true), AwareCommands.parse("missed calls on the S22"))
        for (s in listOf("task status", "job hunter status", "what's going on in Gaza", "status of my job applications", "how are you")) {
            assertNull(s, AwareCommands.parse(s))
        }
        assertTrue(AwareCommands.wantsBoard("is my S22 charging?", listOf("s22")))
        assertTrue(AwareCommands.wantsBoard("where is the benco", listOf("benco")))
        assertFalse(AwareCommands.wantsBoard("tell me a joke", listOf("s22", "benco")))
    }
}
