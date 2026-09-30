package com.xarvis.ai.tools

import com.xarvis.ai.agent.ToolCalls
import com.xarvis.ai.agent.XarvisAgent
import com.xarvis.ai.workflow.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The web-reading tool: clean text out of HTML, tidy URLs, and recognise the command. */
class WebReadTest {

    @Test fun extractsReadableTextAndDropsScriptsStylesAndTags() {
        val html = """
            <html><head><title>My Page</title><style>.x{color:red}</style></head>
            <body><h1>Heading</h1><p>Hello &amp; welcome</p><script>evil()</script>
            <div>Second line</div></body></html>
        """.trimIndent()
        val text = WebRead.extractText(html)
        assertTrue(text.contains("My Page"))
        assertTrue(text.contains("Hello & welcome"))
        assertTrue(text.contains("Second line"))
        assertFalse(text.contains("evil()"))
        assertFalse(text.contains("color:red"))
        assertFalse(text.contains("<"))
    }

    @Test fun normalizeAddsSchemeAndRejectsNonUrls() {
        assertEquals("https://example.com", WebRead.normalize("example.com"))
        assertEquals("https://example.com/news", WebRead.normalize("https://example.com/news"))
        assertNull(WebRead.normalize("just some words"))
        assertNull(WebRead.normalize(""))
    }

    @Test fun parsesWebreadAndReadWithAUrl() {
        assertEquals(Step.WebRead("https://example.com/a"), ToolCalls.parseOne("webread https://example.com/a"))
        assertEquals(Step.WebRead("https://news.site"), ToolCalls.parseOne("read news.site"))
    }

    @Test fun readWithoutAUrlIsNotAWebRead() {
        assertFalse(ToolCalls.parseOne("read me a poem") is Step.WebRead)
    }

    @Test fun detectsWhenRexAsksToReadALink() {
        assertEquals("https://bbc.com/news", XarvisAgent.wantsWebRead("read https://bbc.com/news and summarise it"))
        assertEquals("https://example.com", XarvisAgent.wantsWebRead("can you summarise example.com for me"))
        assertNull(XarvisAgent.wantsWebRead("what's the weather in Dubai"))
    }
}
