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

    @Test fun unknownWhenNoAction() {
        assertEquals(GitHubAgent.Kind.UNKNOWN, GitHubAgent.parse("let me think about this").kind)
    }
}
