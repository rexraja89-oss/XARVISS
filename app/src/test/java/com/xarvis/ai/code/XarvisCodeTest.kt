package com.xarvis.ai.code

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XarvisCodeTest {

    @Test fun flagsACutOffFile() {
        val issues = XarvisCode.issuesIn("<html><body><canvas></canvas><script>let x = 1;")
        assertTrue(issues.toString(), issues.any { it.contains("cut off") })
    }

    @Test fun flagsAnUndefinedButtonFunction() {
        val html = "<html><body><button onclick=\"startGame()\">Play</button>" +
            "<script>function other(){}</script></body></html>"
        val issues = XarvisCode.issuesIn(html)
        assertTrue(issues.toString(), issues.any { it.contains("startGame") })
    }

    @Test fun passesASoundFile() {
        val html = "<html><body><button onclick=\"startGame()\">Play</button>" +
            "<script>function startGame(){}</script></body></html>"
        assertTrue(XarvisCode.issuesIn(html).isEmpty())
    }

    @Test fun passesWhenTheFunctionIsAnArrowConst() {
        val html = "<html><body><button onclick=\"play()\">Play</button>" +
            "<script>const play = () => {};</script></body></html>"
        assertTrue(XarvisCode.issuesIn(html).isEmpty())
    }

    @Test fun parsesRepoListWithOwnerBranchAndPrivacy() {
        val json = """[
            {"name":"XARVISS","full_name":"rexraja89-oss/XARVISS","private":false,"default_branch":"main"},
            {"name":"snake2","full_name":"rexraja89-oss/snake2","private":true,"default_branch":"master"}
        ]"""
        val repos = XarvisCode.parseRepos(json)
        assertTrue(repos.size == 2)
        assertTrue(repos[0].name == "XARVISS" && repos[0].owner == "rexraja89-oss" && repos[0].branch == "main" && !repos[0].private)
        assertTrue(repos[1].branch == "master" && repos[1].private)
    }

    @Test fun parsesTreeBlobsOnly() {
        val json = """{"tree":[
            {"path":"app","type":"tree"},
            {"path":"app/Main.kt","type":"blob"},
            {"path":"README.md","type":"blob"}
        ]}"""
        val paths = XarvisCode.parseTreePaths(json)
        assertTrue(paths == listOf("app/Main.kt", "README.md"))
    }

    @Test fun recognisesXarvisOwnRepo() {
        assertTrue(XarvisCode.isSelfRepo("XARVISS"))
        assertTrue(XarvisCode.isSelfRepo("xarviss"))
        assertTrue(!XarvisCode.isSelfRepo("snake2"))
    }

    @Test fun pullsHtmlOutOfMarkdownFences() {
        val raw = "Sure, here it is:\n```html\n<!doctype html><html><body><canvas></canvas></body></html>\n```"
        val h = XarvisCode.extractHtml(raw)
        assertNotNull(h)
        assertTrue(h!!.contains("<html", ignoreCase = true))
    }
}
