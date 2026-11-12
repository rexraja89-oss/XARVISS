package com.xarvis.ai.task

import com.xarvis.ai.agent.PhoneAgent
import com.xarvis.ai.task.TaskCommands.Cmd
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.TimeZone

/** Rex showing Ninja VPN once: home → icon → Connect → Android's VPN box → an ad → its ✕ → Connected. */
private object NinjaShow {
    const val L = "com.sec.android.app.launcher"
    const val N = "com.ninja.vpn"
    const val D = "com.android.vpndialogs"
    val events = listOf(
        TeachEvent.Window(1_000, "com.xarvis.ai", "XARVIS"),
        TeachEvent.Click(1_100, "com.xarvis.ai", "XARVIS", "🔴 Teach a new skill", "", 0.5f, 0.5f),
        TeachEvent.Window(1_500, L, "One UI Home"),
        TeachEvent.Click(3_000, L, "One UI Home", "Ninja VPN", "", 0.2f, 0.4f),
        TeachEvent.Window(3_400, N, "Ninja VPN"),
        TeachEvent.Click(6_000, N, "Ninja VPN", "Connect", "connect_btn", 0.5f, 0.6f),
        TeachEvent.Window(6_500, D, "VpnDialogs"),
        TeachEvent.Click(8_000, D, "VpnDialogs", "OK", "button1", 0.8f, 0.7f),
        TeachEvent.Window(8_400, N, "Ninja VPN"),
        // Rex waited for the ad's ✕ (about 15 s).
        TeachEvent.Click(23_000, N, "Ninja VPN", "✕", "", 0.93f, 0.05f),
        TeachEvent.Window(30_000, "com.android.systemui", "System UI"),
        TeachEvent.Click(31_000, "com.android.systemui", "System UI", "✅ Done", "", 0.5f, 0.2f),
    )
    val snaps = listOf(
        TeachSnap(4_600, N, "Ninja VPN", listOf("Ninja VPN", "Connect", "United States", "Free")),
        TeachSnap(7_700, D, "VpnDialogs", listOf("Connection request", "OK", "Cancel")),
        TeachSnap(9_600, N, "Ninja VPN", listOf("Ad", "Install", "Learn more")),
        TeachSnap(20_000, N, "Ninja VPN", listOf("Ad", "Install", "Learn more", "✕")),
        TeachSnap(24_200, N, "Ninja VPN", listOf("Ninja VPN", "Connected", "Disconnect", "United States", "00:00:14")),
    )
}

class TeachTest {
    @Test fun ninjaVpnBecomesASkill() {
        val d = Teach.compile(NinjaShow.events, NinjaShow.snaps)
        assertEquals(
            listOf("Open Ninja VPN", "Tap “Connect” in Ninja VPN", "Tap “OK” in VpnDialogs", "Tap “✕” in Ninja VPN", "Check it shows “Connected” / “Disconnect”"),
            d.steps.map { it.describe() },
        )
        val close = d.steps[3] as SkillStep.Tap
        assertEquals(listOf("Ad", "Install", "Learn more", "✕"), close.before) // what the ad looked like when Rex tapped ✕
        assertEquals(Teach.waitFor(23_000 - 8_400), close.waitMs) // about twice what Rex waited
        assertTrue(d.notes.isEmpty())
    }

    @Test fun typingIsOneStepAndPasswordsAreNeverKept() {
        val ev = listOf(
            TeachEvent.Window(0, NinjaShow.L, "Home"),
            TeachEvent.Window(1_000, "com.android.chrome", "Chrome"),
            TeachEvent.Click(2_000, "com.android.chrome", "Chrome", "Search or type URL", "url_bar", 0.5f, 0.1f),
            TeachEvent.Text(2_500, "com.android.chrome", "Chrome", "Search or type URL", "url_bar", "M", false),
            TeachEvent.Text(2_700, "com.android.chrome", "Chrome", "Search or type URL", "url_bar", "Ma", false),
            TeachEvent.Text(3_500, "com.android.chrome", "Chrome", "Search or type URL", "url_bar", "Mars facts", false),
            TeachEvent.Text(5_000, "com.android.chrome", "Chrome", "Password", "pw", "", true),
            TeachEvent.Text(5_200, "com.android.chrome", "Chrome", "Password", "pw", "", true),
        )
        val d = Teach.compile(ev, emptyList())
        assertEquals(listOf("Open Chrome", "Tap “Search or type URL” in Chrome", "Type “Mars facts”", "Your turn: type your password in Chrome (XARVIS never does)"), d.steps.map { it.describe() })
        assertTrue(d.notes.any { it.contains("password") })
    }

    @Test fun bankingAppsAreNeverRecorded() {
        val ev = listOf(
            TeachEvent.Window(0, NinjaShow.L, "Home"),
            TeachEvent.Window(1_000, "com.hdfc.bank", "HDFC Bank"),
            TeachEvent.Click(2_000, "com.hdfc.bank", "HDFC Bank", "Accounts", "", 0.5f, 0.5f),
        )
        val d = Teach.compile(ev, listOf(TeachSnap(1_500, "com.hdfc.bank", "HDFC Bank", listOf("Balance 12,000"))))
        assertTrue(d.steps.isEmpty())
        assertTrue(d.notes.single().contains("never recorded"))
    }

    @Test fun savedAndReadBack() {
        val skill = Skill("VPN", Teach.compile(NinjaShow.events, NinjaShow.snaps).steps, 5)
        val back = Skill.from(JSONObject(skill.toJson().toString()))
        assertEquals(skill, back)
        val book = SkillBook(File.createTempFile("skills", ".json").apply { delete() })
        book.save(skill)
        book.save(Skill("Search", listOf(SkillStep.Open("Chrome"), SkillStep.Type(SkillTarget("Search", "", .5f, .1f, "Chrome"), "Mars", "text")), 6))
        assertEquals("VPN", book.find("my VPN!")?.name)
        book.recordRun("vpn", ok = true, clean = true, result = "done", at = 9)
        assertEquals(1, book.find("VPN")?.clean)
        book.delete("my vpn")
        assertNull(book.find("VPN"))
    }
}

class SkillCommandTest {
    private val skills = listOf(
        Skill("Ninja VPN", listOf(SkillStep.Open("Ninja VPN")), 1),
        Skill("search", listOf(SkillStep.Type(SkillTarget("Search", "", .5f, .1f, "Chrome"), "Mars", "text")), 1),
        Skill("chat with mom", listOf(SkillStep.Open("WhatsApp")), 1),
    )

    private fun resolve(message: String) = (TaskCommands.parse(message) as? Cmd.Skill)?.let { Skills.resolve(skills, it) }

    @Test fun doMySkill() {
        assertEquals(Cmd.Skill("VPN", raw = "VPN"), TaskCommands.parse("do my VPN"))
        assertEquals(Cmd.Skill("VPN", null, "S22", "VPN on my S22"), TaskCommands.parse("Hey Jarvis, do my VPN on my S22."))
        assertEquals(Cmd.Skill("search", "dew point tables", null, "search: dew point tables"), TaskCommands.parse("run my search: dew point tables"))
        assertEquals("Ninja VPN", resolve("do my VPN")?.first?.name) // part of the name, only one fits
        assertEquals(mapOf("text" to "dew point tables"), resolve("run my search: dew point tables")?.second)
        assertEquals("chat with mom", resolve("do my chat with mom")?.first?.name) // "with" is part of this name
        assertNull(resolve("do my homework")) // not a skill: the chat answers it
        assertNull(TaskCommands.parse("do this for me: open settings")) // PhoneTask's, not a skill
        assertEquals(Cmd.Skills, TaskCommands.parse("show me my skills"))
        assertEquals(Cmd.Skills, TaskCommands.parse("what skills do you know?"))
    }

    @Test fun nightShiftCommands() {
        assertEquals(Cmd.Night("do my VPN", 2, 0), TaskCommands.parse("night shift: do my VPN at 2 am"))
        assertEquals(Cmd.Night("find 3 Mars facts and save them", 1, 30, "S22"), TaskCommands.parse("Night shift on my S22 at 1:30am - find 3 Mars facts and save them"))
        assertEquals(Cmd.Night("do my search: dew point", 23, 15), TaskCommands.parse("night shift: do my search: dew point at 11.15 pm"))
        assertEquals(Cmd.NightList(), TaskCommands.parse("night shift report"))
        assertEquals(Cmd.NightList(), TaskCommands.parse("what did the night shift do?"))
        assertEquals(Cmd.NightClear("S22"), TaskCommands.parse("cancel the night shift on my S22"))
        assertEquals(0, TaskCommands.hour24(12, "a.m."))
        assertEquals(12, TaskCommands.hour24(12, "pm"))
        assertEquals(14, TaskCommands.hour24(2, "PM"))
        assertNull(TaskCommands.hour24(25, null))
        // Ordinary task commands still work.
        assertEquals(Cmd.Stop, TaskCommands.parse("stop the task"))
    }
}

class NightShiftTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun at(h: Int, m: Int = 0, day: Int = 0) = (day * 24L * 60 + h * 60 + m) * 60_000L + 1_700_000_000_000L / 86_400_000L * 86_400_000L

    @Test fun jobsRunOncePerNightWithinTheirWindow() {
        val vpn = NightShift.Job("a", "do my VPN", 2, 0, lastRun = at(23, day = -1))
        val short = NightShift.Job("b", "make a Mars short draft", 3, 30, lastRun = at(23, day = -1))
        val off = NightShift.Job("c", "x", 2, 0, enabled = false)
        val jobs = listOf(short, vpn, off)
        assertTrue(NightShift.due(jobs, at(1, 59), utc).isEmpty())
        assertEquals(listOf("a"), NightShift.due(jobs, at(2, 1), utc).map { it.id })
        assertEquals(listOf("a", "b"), NightShift.due(jobs, at(4), utc).map { it.id }) // earliest first
        // Already run tonight: not again; too late (over 3 h): skipped till tomorrow.
        assertTrue(NightShift.due(listOf(vpn.copy(lastRun = at(2, 5))), at(4), utc).isEmpty())
        assertTrue(NightShift.due(listOf(vpn), at(5, 1), utc).isEmpty())
        assertEquals(at(2), NightShift.nextAlarm(jobs, at(1), utc))
        assertEquals(at(2, day = 1), NightShift.nextAlarm(listOf(vpn), at(2), utc))
        assertNull(NightShift.nextAlarm(listOf(off), at(1), utc))
    }

    @Test fun theReport() {
        val r = NightShift.report(listOf(NightShift.Job("a", "do my VPN", 2, 0) to "✓ “VPN” done in 0:31.", NightShift.Job("b", "make a draft", 3, 30) to "⚠ couldn't start: Hands is off"))
        assertTrue(r.startsWith("🌙 Night shift report: 1 of 2 done, 1 need you."))
        assertTrue(r.contains("02:00 do my VPN — ✓"))
        assertEquals(listOf(NightShift.Job("a", "x", 2, 5)), NightShift.parse(NightShift.toJson(listOf(NightShift.Job("a", "x", 2, 5)))))
    }
}

/** A pretend phone whose screens change when things are tapped (and as time passes), with a scripted brain. */
private class Phone(var app: String = "One UI Home") : TaskRunner.Env {
    data class Screen(val app: String, val labels: List<String>)
    var screen = Screen(app, listOf("Ninja VPN", "Chrome"))
    /** label tapped → the next screen. */
    val onTap = HashMap<String, Screen>()
    /** at this clock time → this screen (an ad's ✕ appearing). */
    val later = ArrayList<Pair<Long, Screen>>()
    /** label tapped → after this long, this screen. */
    val afterTap = HashMap<String, Pair<Long, Screen>>()
    val apps = HashMap<String, Screen>()
    val did = ArrayList<String>()
    val brain = ArrayDeque<String>()
    var brainCalls = 0
    var clock = 0L
    val progress = ArrayList<String>()

    override suspend fun screen(): PhoneAgent.Screen {
        later.filter { it.first <= clock }.maxByOrNull { it.first }?.let { (t, s) -> later.removeAll { it.first <= t }; screen = s }
        return PhoneAgent.Screen(screen.app, screen.labels.mapIndexed { i, l -> PhoneAgent.Element(i + 1, l, true, l == "Search", false, 100, 100 * (i + 1)) }, 1000, 2000)
    }
    override suspend fun screenshot(screen: PhoneAgent.Screen): String? = null
    override suspend fun tap(x: Int, y: Int, long: Boolean) {
        val label = screen.labels.getOrNull(y / 100 - 1) ?: "?"
        did += "tap $label"
        onTap[label]?.let { screen = it }
        afterTap[label]?.let { (d, s) -> later += (clock + d) to s }
    }
    override suspend fun type(text: String): Boolean { did += "type $text"; return true }
    override suspend fun swipe(direction: String) { did += "swipe $direction" }
    override suspend fun key(name: String) { did += "key $name" }
    override suspend fun open(app: String): String {
        val s = apps[app] ?: return "there's no app called \"$app\""
        did += "open $app"
        screen = s
        return "opened $app"
    }
    override suspend fun openUrl(url: String) = "opened"
    override suspend fun save(name: String, text: String) = name
    override suspend fun think(system: String, prompt: String, imageB64: String?): String {
        brainCalls++
        return brain.removeFirstOrNull() ?: "STUCK no more script"
    }
    override suspend fun ask(question: String, yesNo: Boolean): String? = "done"
    override fun progress(line: String) { progress += line }
    override fun stopped() = false
    override suspend fun pause(ms: Long) { clock += ms }
    override fun now() = clock
}

class SkillRunnerTest {
    private fun lessons() = LessonBook(File.createTempFile("lessons", ".json").apply { delete() })
    private val vpn = Skill("VPN", Teach.compile(NinjaShow.events, NinjaShow.snaps).steps, 1)

    private fun ninjaPhone(closeLabel: String = "✕"): Phone = Phone().apply {
        apps["Ninja VPN"] = Phone.Screen("Ninja VPN", listOf("Ninja VPN", "Connect", "United States"))
        onTap["Connect"] = Phone.Screen("VpnDialogs", listOf("Connection request", "OK", "Cancel"))
        onTap["OK"] = Phone.Screen("Ninja VPN", listOf("Ad", "Install", "Learn more"))
        afterTap["OK"] = 6_000L to Phone.Screen("Ninja VPN", listOf("Ad", "Install", "Learn more", closeLabel))
        onTap[closeLabel] = Phone.Screen("Ninja VPN", listOf("Ninja VPN", "Connected", "Disconnect", "United States"))
    }

    @Test fun replaysNinjaVpnWithoutTheBrain() = runBlocking {
        val phone = ninjaPhone()
        val r = SkillRunner(phone, lessons()).run(vpn)
        assertTrue(r.summary, r.ok)
        assertEquals(listOf("open Ninja VPN", "tap Connect", "tap OK", "tap ✕"), phone.did) // never the ad's Install
        assertEquals(0, phone.brainCalls)
        assertEquals(0, r.helped)
        assertTrue("took ${phone.clock} ms", phone.clock < 20_000) // waited for the ✕, not a fixed time
    }

    @Test fun anAdsCloseButtonCanHaveAnotherName() = runBlocking {
        val phone = ninjaPhone(closeLabel = "Close ad")
        assertTrue(SkillRunner(phone, lessons()).run(vpn).ok)
        assertEquals("tap Close ad", phone.did.last())
    }

    @Test fun aStepThatLooksDifferentGoesToTheBrainAlone() = runBlocking {
        val phone = ninjaPhone().apply {
            // This time the button says "Tap to connect" in a new layout, and the screen has no "Connect".
            apps["Ninja VPN"] = Phone.Screen("Ninja VPN", listOf("Ninja VPN", "Power", "United States"))
            onTap["Power"] = Phone.Screen("VpnDialogs", listOf("Connection request", "OK", "Cancel"))
            brain += "TAP 2"
            brain += "DONE connected"
        }
        val r = SkillRunner(phone, lessons()).run(vpn)
        assertTrue(r.summary, r.ok)
        assertEquals(1, r.helped)
        assertTrue(phone.did.contains("tap Power"))
        assertEquals("tap ✕", phone.did.last()) // and then carried on by itself
        val without = SkillRunner(ninjaPhone().apply { apps["Ninja VPN"] = Phone.Screen("Ninja VPN", listOf("Power")) }, lessons(), brainHelp = false).run(vpn)
        assertFalse(without.ok)
    }

    @Test fun connectNeverTapsDisconnectAndADoneJobIsLeftAlone() = runBlocking {
        // Rex's real skill: Open, Connect, a nameless ✕, check Disconnect / Upgrade… / Truly Free…
        val check = SkillStep.Check(listOf("Disconnect", "Upgrade for Just AED29.00/yr", "Truly Free & Unlimited"), 30_000)
        val skill = Skill(
            "Ninja VPN",
            listOf(
                SkillStep.Open("Ninja VPN"),
                SkillStep.Tap(SkillTarget("Connect", "", .5f, .5f, "Ninja VPN"), false, emptyList(), emptyList(), 10_000),
                SkillStep.Tap(SkillTarget("", "", .93f, .05f, "Ninja VPN"), false, listOf("Ad", "Install"), emptyList(), 20_000),
                check,
            ),
            1,
        )
        // Already connected: nothing is tapped.
        val connected = Phone().apply { apps["Ninja VPN"] = Phone.Screen("Ninja VPN", listOf("Disconnect", "Upgrade for Just AED29.00/yr", "Truly Free & Unlimited")) }
        val r = SkillRunner(connected, lessons(), brainHelp = false).run(skill)
        assertTrue(r.summary, r.ok)
        assertTrue(r.summary.contains("already done"))
        assertEquals(listOf("open Ninja VPN"), connected.did)
        // "Connect" is never matched inside "Disconnect".
        val screen = PhoneAgent.Screen("Ninja VPN", listOf(PhoneAgent.Element(1, "Disconnect", true, false, false, 10, 10)), 100, 100)
        assertNull(SkillMatch.find(screen, SkillTarget("Connect", "", .1f, .1f, "Ninja VPN")))
        val now = PhoneAgent.Screen("Ninja VPN", listOf(PhoneAgent.Element(1, "Connect now", true, false, false, 10, 10)), 100, 100)
        assertEquals("Connect now", SkillMatch.find(now, SkillTarget("Connect", "", .1f, .1f, "Ninja VPN"))?.label)
    }

    @Test fun moneyIsNeverTouched() = runBlocking {
        val buy = Skill("buy", listOf(SkillStep.Tap(SkillTarget("Buy now", "", .5f, .1f, "Shop"), false, emptyList(), emptyList(), 5_000)), 1)
        val phone = Phone().apply { screen = Phone.Screen("Shop", listOf("Buy now")) }
        val r = SkillRunner(phone, lessons()).run(buy)
        assertFalse(r.ok)
        assertTrue(phone.did.isEmpty())
        // A banking app on screen: Home, and stop.
        val bank = Phone().apply { screen = Phone.Screen("HDFC Bank", listOf("Accounts")) }
        val r2 = SkillRunner(bank, lessons()).run(Skill("x", listOf(SkillStep.Tap(SkillTarget("Accounts", "", .5f, .1f, "HDFC Bank"), false, emptyList(), emptyList(), 5_000)), 1))
        assertFalse(r2.ok)
        assertEquals(listOf("key home"), bank.did)
    }

    @Test fun theChangingTextIsFilledIn() = runBlocking {
        val search = Skill(
            "search",
            listOf(SkillStep.Open("Chrome"), SkillStep.Type(SkillTarget("Search", "", .5f, .05f, "Chrome"), "Mars facts", "text")),
            1,
        )
        val phone = Phone().apply { apps["Chrome"] = Phone.Screen("Chrome", listOf("Search", "Mars")) }
        assertTrue(SkillRunner(phone, lessons()).run(search, mapOf("text" to "dew point tables")).ok)
        assertEquals(listOf("open Chrome", "tap Search", "type dew point tables"), phone.did)
    }

    @Test fun aPlanStepCanBeASkill() = runBlocking {
        val phone = ninjaPhone()
        val plan = Plan("x", listOf(PlanStep("Connect the VPN", "Ninja VPN", "", "connected", skill = "VPN")))
        val out = TaskRunner(phone, lessons(), skills = { if (SkillBook.key(it) == "vpn") vpn else null }).run("connect the vpn", plan)
        assertTrue(out.summary, out.finished)
        assertEquals(0, phone.brainCalls)
        assertEquals("VPN", Plan.parse("""{"title":"t","steps":[{"title":"a","app":"Ninja VPN","skill":"VPN"}]}""")?.steps?.single()?.skill)
        assertNotNull(vpn.summary())
    }
}
