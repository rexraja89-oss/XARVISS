package com.xarvis.ai.task

import com.xarvis.ai.agent.PhoneAgent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TaskTextTest {
    @Test fun actionsAreReadInOrderAndATurnEndsAtDone() {
        val a = TaskText.parseActions("Sure!\nTAP 3\n**TYPE 5: Mars facts**\nDONE typed it\nTAP 9")
        assertEquals(listOf(TaskAction.Tap(3), TaskAction.Type(5, "Mars facts"), TaskAction.Done("typed it")), a)
    }

    @Test fun typeForms() {
        assertEquals(TaskAction.Type(7, "hello world"), TaskText.parseActions("TYPE 7 \"hello world\"")[0])
        assertEquals(TaskAction.Type(null, "a cat in space"), TaskText.parseActions("TYPE: a cat in space")[0])
        assertEquals(TaskAction.Type(2, "hi"), TaskText.parseActions("TYPE [2] hi")[0])
    }

    @Test fun saveTakesTheLinesUntilEndSave() {
        val a = TaskText.parseActions("SAVE script\nLine one.\nLine two.\nEND SAVE\nDONE saved")
        assertEquals(TaskAction.Save("script.txt", "Line one.\nLine two."), a[0])
        assertEquals(TaskAction.Done("saved"), a[1])
        assertEquals(TaskAction.Save("title.txt", "Mars in 60 seconds"), TaskText.parseActions("SAVE title.txt: Mars in 60 seconds")[0])
    }

    @Test fun waitsAreCappedAndPointsStayOnScreen() {
        assertEquals(TaskAction.Wait(600, "video generating"), TaskText.parseActions("WAIT 9999 video generating")[0])
        assertEquals(TaskAction.TapXY(1000, 250), TaskText.parseActions("TAPXY 1200, 250")[0])
        assertEquals(TaskAction.Swipe("left"), TaskText.parseActions("SWIPE LEFT")[0])
    }

    @Test fun planIsReadFromAFencedAnswer() {
        val raw = "Here's the plan:\n```json\n{\"title\":\"Mars Short\",\"steps\":[{\"title\":\"Write script\",\"app\":\"XARVIS\",\"how\":\"60 s, hook first\",\"done\":\"script.txt saved\"},{\"title\":\"Edit {clips}\",\"app\":\"CapCut\",\"how\":\"x\",\"done\":\"y\"}]}\n```"
        val plan = Plan.parse(raw)
        assertNotNull(plan)
        assertEquals("Mars Short", plan!!.title)
        assertEquals(2, plan.steps.size)
        assertEquals("CapCut", plan.steps[1].app)
        assertEquals("Edit {clips}", plan.steps[1].title)
        assertNull(Plan.parse("no plan here"))
    }

    @Test fun lessonsFromTheLookBack() {
        val l = TaskText.parseLessons("LESSON CapCut: Export is the arrow at the top right\n- LESSON general: save prompts first\nnonsense")
        assertEquals(listOf("CapCut" to "Export is the arrow at the top right", "general" to "save prompts first"), l)
    }
}

class TaskRunnerTest {
    private fun el(n: Int, label: String, edit: Boolean = false) = PhoneAgent.Element(n, label, true, edit, false, 100 * n, 200)

    /** A pretend phone: shows [screens] by app, records what XARVIS did, and a brain that answers from a script. */
    private class Fake(val run: MutableList<String>, val replan: String = "", val reflect: String = "") : TaskRunner.Env {
        var app = "Launcher"
        val screens = HashMap<String, List<PhoneAgent.Element>>()
        val did = ArrayList<String>()
        val saved = HashMap<String, String>()
        val asked = ArrayList<String>()
        var answers = ArrayDeque<String>()
        var stop = false
        var clock = 0L
        var busyTimes = 0
        val progress = ArrayList<String>()

        override suspend fun screen() = PhoneAgent.Screen(app, screens[app].orEmpty(), 1080, 2400)
        override suspend fun screenshot(screen: PhoneAgent.Screen): String? = "jpegbase64"
        override suspend fun tap(x: Int, y: Int, long: Boolean) { did += "tap $x,$y" }
        override suspend fun type(text: String): Boolean { did += "type $text"; return true }
        override suspend fun swipe(direction: String) { did += "swipe $direction" }
        override suspend fun key(name: String) { did += "key $name" }
        override suspend fun open(app: String): String { this.app = app; did += "open $app"; return "opened $app" }
        override suspend fun openUrl(url: String): String { did += "url $url"; return "opened" }
        override suspend fun save(name: String, text: String): String { saved[name] = text; return "Downloads/XARVIS/Projects/$name" }
        override suspend fun think(system: String, prompt: String, imageB64: String?): String {
            if (busyTimes > 0) { busyTimes--; throw TaskRunner.BrainBusy("free limit") }
            return when (system) {
                TaskPrompts.REPLAN_SYSTEM -> replan
                TaskPrompts.REFLECT_SYSTEM -> reflect
                else -> if (run.isEmpty()) "STUCK script ran out" else run.removeAt(0)
            }
        }
        override suspend fun ask(question: String, yesNo: Boolean): String? { asked += question; return answers.removeFirstOrNull() }
        override fun progress(line: String) { progress += line }
        override fun stopped() = stop
        override suspend fun pause(ms: Long) { clock += ms }
        override fun now() = clock
    }

    private fun lessons() = LessonBook(File.createTempFile("lessons", ".json").apply { delete() })

    private val twoSteps = Plan(
        "Mars Short",
        listOf(PlanStep("Write the script", "XARVIS", "60 s", "script.txt saved"), PlanStep("Export the edit", "CapCut", "tap Export", "exported")),
    )

    @Test fun runsTheWholePlanAndLearns() = runBlocking {
        val fake = Fake(
            mutableListOf("SAVE script.txt\nMars is red.\nEND SAVE\nDONE script saved", "OPEN CapCut", "TAP 1", "DONE exported to the gallery"),
            reflect = "LESSON CapCut: Export is the arrow at the top right",
        )
        fake.screens["CapCut"] = listOf(el(1, "Export"))
        val book = lessons()
        val out = TaskRunner(fake, book).run("make a Mars short", twoSteps)
        assertTrue(out.summary, out.finished)
        assertEquals("Mars is red.", fake.saved["script.txt"])
        assertEquals(listOf("open CapCut", "tap 100,200"), fake.did)
        assertEquals(listOf("Export is the arrow at the top right"), book.forApp("CapCut"))
    }

    @Test fun moneyIsNeverTouchedAndSigningInNeedsNoQuestion() = runBlocking {
        val fake = Fake(mutableListOf("TAP 1", "TAP 2", "DONE signed in"))
        fake.app = "AI Video"
        fake.screens["AI Video"] = listOf(el(1, "Upgrade to Pro"), el(2, "Sign in"))
        val out = TaskRunner(fake, lessons()).run("make a clip", Plan("Clip", listOf(PlanStep("Sign in", "AI Video", "", ""))))
        assertTrue(out.finished)
        assertEquals(listOf("tap 200,200"), fake.did) // Sign in, never Upgrade
        assertTrue(fake.asked.isEmpty()) // the task was the permission
        assertEquals(0, out.asked)
    }

    @Test fun publishingOnlyWhenTheTaskSaysSo() = runBlocking {
        val asked = Fake(mutableListOf("TAP 1", "DONE uploaded"))
        asked.app = "YT Studio"
        asked.screens["YT Studio"] = listOf(el(1, "Publish"))
        TaskRunner(asked, lessons()).run("upload my Mars video to YouTube as private", Plan("Up", listOf(PlanStep("Upload", "YT Studio", "", ""))))
        assertEquals(listOf("tap 100,200"), asked.did)

        val notAsked = Fake(mutableListOf("TAP 1", "DONE left it"))
        notAsked.app = "YT Studio"
        notAsked.screens["YT Studio"] = listOf(el(1, "Publish"))
        TaskRunner(notAsked, lessons()).run("check my channel's views", Plan("Views", listOf(PlanStep("Look", "YT Studio", "", ""))))
        assertTrue(notAsked.did.isEmpty()) // skipped, and nobody was asked
        assertTrue(notAsked.asked.isEmpty())
    }

    @Test fun ninjaVpnConnectsThroughTheAdsWithoutQuestions() = runBlocking {
        // Rex's first practice job: open, Connect, wait out the ad (its Install is skipped), close it, check, report.
        val fake = Fake(mutableListOf("OPEN Ninja VPN", "TAP 1", "WAIT 10 ad", "TAP 3", "TAP 2", "DONE Ninja VPN is connected"))
        fake.screens["Ninja VPN"] = listOf(el(1, "Connect"), el(2, "Close ad"), el(3, "Install"))
        val out = TaskRunner(fake, lessons()).run(
            "open Ninja VPN and connect, wait for the ads, tell me when it's connected",
            Plan("Ninja VPN", listOf(PlanStep("Connect Ninja VPN", "Ninja VPN", "", "it says Connected"))),
        )
        assertTrue(out.summary, out.finished)
        assertEquals(listOf("open Ninja VPN", "tap 100,200", "tap 200,200"), fake.did) // never the ad's Install
        assertTrue(fake.asked.isEmpty())
        assertTrue(out.summary.contains("Ninja VPN is connected"))
    }

    @Test fun bankingAppsAreNeverOpened() = runBlocking {
        val fake = Fake(mutableListOf("OPEN HDFC Bank", "DONE checked another way"))
        TaskRunner(fake, lessons()).run("note my salary date", Plan("Salary", listOf(PlanStep("Find it", "", "", ""))))
        assertTrue(fake.did.none { it.startsWith("open") })
    }

    @Test fun aStuckStepGetsANewPlan() = runBlocking {
        val fake = Fake(
            mutableListOf("STUCK the site is down", "OPEN Other", "DONE made it elsewhere"),
            replan = "{\"title\":\"x\",\"steps\":[{\"title\":\"Use the other site\",\"app\":\"Other\",\"how\":\"\",\"done\":\"\"}]}",
        )
        val out = TaskRunner(fake, lessons()).run("make images", Plan("Images", listOf(PlanStep("Use site A", "A", "", ""))))
        assertTrue(out.summary, out.finished)
        assertEquals("Use the other site", out.plan.steps.single().title)
        assertTrue(fake.did.contains("open Other"))
    }

    @Test fun outOfIdeasAsksRexWhoCanSkip() = runBlocking {
        val fake = Fake(mutableListOf("STUCK no way"), replan = "not json")
        fake.answers = ArrayDeque(listOf("skip"))
        val out = TaskRunner(fake, lessons(), TaskRunner.Limits(replans = 1)).run("x", Plan("X", listOf(PlanStep("Hard step", "A", "", ""))))
        assertTrue(out.finished)
        assertTrue(fake.asked.single().contains("stuck"))
    }

    @Test fun stopEndsItAtOnce() = runBlocking {
        val fake = Fake(mutableListOf("WAIT 300 generating"))
        fake.stop = true
        val out = TaskRunner(fake, lessons()).run("x", twoSteps)
        assertFalse(out.finished)
        assertTrue(out.summary.contains("Stopped"))
    }

    @Test fun waitsOutTheBrainsFreeLimit() = runBlocking {
        val fake = Fake(mutableListOf("SAVE a.txt: hi\nDONE ok", "OPEN CapCut", "DONE ok"))
        fake.busyTimes = 2
        val out = TaskRunner(fake, lessons()).run("x", twoSteps)
        assertTrue(out.finished)
        assertTrue("waited ${fake.clock} ms", fake.clock >= 90_000) // 30 s + 60 s
        assertTrue(fake.progress.any { it.contains("free limit") })
    }

    @Test fun repeatingTheSameThingEndsAsStuck() = runBlocking {
        val fake = Fake(MutableList(20) { "SWIPE DOWN" }, replan = "nope")
        fake.answers = ArrayDeque(listOf("stop"))
        val out = TaskRunner(fake, lessons(), TaskRunner.Limits(replans = 0)).run("x", Plan("X", listOf(PlanStep("Find it", "A", "", ""))))
        assertFalse(out.finished)
        assertTrue(fake.did.count { it == "swipe down" } <= 7)
    }
}
