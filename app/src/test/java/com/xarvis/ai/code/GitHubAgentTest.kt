package com.xarvis.ai.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubAgentTest {

    @Test fun parsesList() {
        assertEquals(GitHubAgent.Kind.LIST, GitHubAgent.parse("LIST").kind)
    }

    @Test fun parsesReadWithColonAndPath() {
        val a = GitHubAgent.parse("READ: app/src/Main.kt")
        assertEquals(GitHubAgent.Kind.READ, a.kind)
        assertEquals("app/src/Main.kt", a.path)
    }

    @Test fun parsesReadWithoutColon() {
        assertEquals("README.md", GitHubAgent.parse("READ README.md").path)
    }

    @Test fun parsesWriteWithCodeBlock() {
        val reply = "WRITE index.html\n```html\n<html></html>\n```"
        val a = GitHubAgent.parse(reply)
        assertEquals(GitHubAgent.Kind.WRITE, a.kind)
        assertEquals("index.html", a.path)
        assertEquals("<html></html>", a.content)
    }

    @Test fun writeWithoutCodeBlockHasNoContent() {
        assertNull(GitHubAgent.parse("WRITE index.html").content)
    }

    @Test fun parsesDone() {
        assertEquals(GitHubAgent.Kind.DONE, GitHubAgent.parse("DONE made the change").kind)
    }

    @Test fun parsesAnswerWithText() {
        val a = GitHubAgent.parse("ANSWER: This repo holds analysis tools, not the game's source.")
        assertEquals(GitHubAgent.Kind.ANSWER, a.kind)
        assertEquals("This repo holds analysis tools, not the game's source.", a.content)
    }

    @Test fun parsesMultilineAnswer() {
        val a = GitHubAgent.parse("ANSWER\nLine one.\nLine two.")
        assertEquals(GitHubAgent.Kind.ANSWER, a.kind)
        assertEquals("Line one.\nLine two.", a.content)
    }

    @Test fun unknownWhenNoAction() {
        assertEquals(GitHubAgent.Kind.UNKNOWN, GitHubAgent.parse("let me think about this").kind)
    }

    @Test fun parsesAskAndPlanAndWeb() {
        assertEquals(GitHubAgent.Kind.ASK, GitHubAgent.parse("ASK Which colour do you want, sir?").kind)
        assertEquals("Which colour do you want, sir?", GitHubAgent.parse("ASK Which colour do you want, sir?").content)
        assertEquals(GitHubAgent.Kind.PLAN, GitHubAgent.parse("PLAN first research, then write the PDF").kind)
        assertEquals(GitHubAgent.Kind.WEB, GitHubAgent.parse("WEB best roguelike mechanics 2026").kind)
        assertEquals("best roguelike mechanics 2026", GitHubAgent.parse("WEB best roguelike mechanics 2026").path)
    }

    @Test fun parsesDocWithFilenameAndBody() {
        val a = GitHubAgent.parse("DOC report.pdf\n```\n# Title\n- point\n```")
        assertEquals(GitHubAgent.Kind.DOC, a.kind)
        assertEquals("report.pdf", a.path)
        assertEquals("# Title\n- point", a.content)
    }

    @Test fun readUrlIsNotConfusedWithRead() {
        assertEquals(GitHubAgent.Kind.READURL, GitHubAgent.parse("READURL https://example.com").kind)
        assertEquals(GitHubAgent.Kind.READ, GitHubAgent.parse("READ src/Main.kt").kind)
    }
}
