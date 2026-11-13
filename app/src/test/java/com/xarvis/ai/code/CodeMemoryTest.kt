package com.xarvis.ai.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeMemoryTest {
    @Test fun notesStartAndGrow() {
        val first = ProjectMemory.update(null, "friday", "2026-11-13 11:00", "Make FRIDAY, an AI assistant", "published index.html (18000 chars)", "1 core chat ✓ · 2 memory · 3 voice")
        assertTrue(first, first.startsWith("# friday — XARVIS Code notes"))
        assertTrue(first, first.contains("## Plan\n1 core chat ✓ · 2 memory · 3 voice"))
        assertTrue(first, first.contains("- 2026-11-13 11:00 · \"Make FRIDAY, an AI assistant\" → published index.html (18000 chars)"))
        // The next request keeps the log and the plan unless a new one comes.
        val second = ProjectMemory.update(first, "friday", "2026-11-13 11:30", "next stage", "published index.html (21000 chars)")
        assertTrue(second, second.contains("2 memory"))
        assertEquals(2, second.lines().count { it.startsWith("- 2026") })
        val third = ProjectMemory.update(second, "friday", "2026-11-13 12:00", "next stage", "ok", "1 ✓ · 2 ✓ · 3 voice")
        assertTrue(third.contains("## Plan\n1 ✓ · 2 ✓ · 3 voice"))
        assertFalse(third.contains("2 memory\n"))
    }

    @Test fun rexsOwnEditsAndTheLogLimitSurvive() {
        var notes = ProjectMemory.update(null, "p", "t0", "start", "ok")
        notes = notes.replace("## Log", "## Rex says\nUse a female voice.\n\n## Log")
        repeat(50) { notes = ProjectMemory.update(notes, "p", "t${it + 1}", "change $it", "ok") }
        assertTrue(notes, notes.contains("## Rex says\nUse a female voice."))
        assertEquals(40, notes.lines().count { it.startsWith("- t") })
        assertTrue(notes.contains("change 49") && !notes.contains("\"change 5\""))
    }

    @Test fun theBrainGetsThePlanAndTheNewestWork() {
        var notes = ProjectMemory.update(null, "friday", "t0", "Make FRIDAY", "published", "1 core ✓ · 2 memory")
        repeat(100) { notes = ProjectMemory.update(notes, "friday", "t${it + 1}", "a long request number $it " + "x".repeat(120), "published") }
        val p = ProjectMemory.forPrompt(notes)
        assertTrue(p, p.startsWith("PROJECT MEMORY"))
        assertTrue(p, p.contains("Plan: 1 core ✓ · 2 memory"))
        assertTrue(p, p.contains("number 99"))
        assertTrue(p.length <= 4000)
        assertEquals("", ProjectMemory.forPrompt(null))
    }

    @Test fun planAndLessons() {
        val html = "<!-- XARVIS PLAN: 1 core chat ✓ ·\n 2 voice -->\n<!doctype html><html></html>"
        assertEquals("1 core chat ✓ · 2 voice", ProjectMemory.planIn(html))
        assertNull(ProjectMemory.planIn("<html></html>"))
        val done = "Added the mic button.\nLESSON: Chrome needs https for the mic.\n- LESSON: speechSynthesis voices load late — wait for voiceschanged."
        assertEquals(listOf("Chrome needs https for the mic.", "speechSynthesis voices load late — wait for voiceschanged."), ProjectMemory.lessonsIn(done))
        assertEquals("Added the mic button.", ProjectMemory.withoutLessons(done))
    }

    @Test fun theRightGuidesForTheJob() {
        val friday = Playbooks.forTask("make friday, an AI assistant like jarvis")
        assertTrue(friday.contains("PLAYBOOK — BUILDING AN AI ASSISTANT"))
        assertTrue(friday.contains("thinkingBudget"))
        assertTrue(friday.contains("LESSONS FROM BUILDING XARVIS"))
        val game = Playbooks.forTask("make a snake game with neon colours")
        assertFalse(game.contains("PLAYBOOK — BUILDING AN AI ASSISTANT"))
        assertTrue(game.contains("LESSONS FROM BUILDING XARVIS"))
        // The project's notes count too: "next stage" on FRIDAY still gets the AI guide.
        assertTrue(Playbooks.forTask("next stage\n# friday — notes: an AI assistant").contains("GEMINI REST"))
    }

    @Test fun keysAreCaughtNeverPublished() {
        val leak = "<html><script>const KEY='AIzaSyA1234567890abcdefghijklmnopqrstuv';</script></html>"
        val found = XarvisCode.secretsIn(leak)
        assertEquals(1, found.size)
        assertTrue(found[0], found[0].startsWith("a Google/Gemini API key (AIzaSy…"))
        assertFalse(found[0].contains("1234567890abcdef"))
        assertTrue(XarvisCode.issuesIn(leak).any { it.contains("looks like a Google/Gemini API key") })
        assertTrue(XarvisCode.secretsIn("token = \"ghp_" + "a".repeat(36) + "\"").isNotEmpty())
        assertTrue(XarvisCode.secretsIn("Authorization: Bearer sk-proj-" + "b".repeat(30)).isNotEmpty())
        // The right way (the user types the key; it's read from localStorage) is fine.
        val ok = "<html><script>const key = localStorage.getItem('friday.key') || ''; // paste your key in ⚙</script></html>"
        assertTrue(XarvisCode.secretsIn(ok).isEmpty())
        assertTrue(XarvisCode.secretsIn("const API_KEY = 'YOUR_KEY_HERE';").isEmpty())
    }

    @Test fun lessonsAreGeneralAndNotRepeated() {
        assertTrue(CodeLessons.fromIssue("a button calls startGame() but that function is never defined").startsWith("Define every function"))
        assertTrue(CodeLessons.fromIssue("it contains what looks like a Google/Gemini API key (AIzaSy…) — remove it").startsWith("Never put an API key"))
        assertTrue(CodeLessons.similar("Chrome needs https for the microphone", "The microphone needs https in Chrome"))
        assertFalse(CodeLessons.similar("Chrome needs https for the microphone", "Escape model text before showing it"))
    }
}
