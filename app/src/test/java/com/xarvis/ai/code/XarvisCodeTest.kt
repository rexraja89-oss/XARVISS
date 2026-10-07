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

    @Test fun pullsHtmlOutOfMarkdownFences() {
        val raw = "Sure, here it is:\n```html\n<!doctype html><html><body><canvas></canvas></body></html>\n```"
        val h = XarvisCode.extractHtml(raw)
        assertNotNull(h)
        assertTrue(h!!.contains("<html", ignoreCase = true))
    }
}
