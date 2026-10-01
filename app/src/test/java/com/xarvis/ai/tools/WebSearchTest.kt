package com.xarvis.ai.tools

import com.xarvis.ai.agent.ToolCalls
import com.xarvis.ai.agent.XarvisAgent
import com.xarvis.ai.workflow.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The web-search tool: pull results out of DuckDuckGo's page, recognise the command, route it. */
class WebSearchTest {

    private val liteHtml = """
        <html><body>
        <a rel="nofollow" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa&rut=1" class="result-link">First Result</a>
        <td class="result-snippet">First snippet here.</td>
        <a rel="nofollow" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Ftest.org%2Fb" class="result-link">Second Result</a>
        <td class="result-snippet">Second snippet.</td>
        </body></html>
    """.trimIndent()

    @Test fun parsesTitleSnippetAndRealLinkFromDuckDuckGo() {
        val results = WebSearch.parse(liteHtml)
        assertEquals(2, results.size)
        assertEquals("First Result", results[0].title)
        assertEquals("https://example.com/a", results[0].url) // decoded out of the uddg wrapper
        assertEquals("First snippet here.", results[0].snippet)
        assertEquals("https://test.org/b", results[1].url)
    }

    @Test fun parsesEmptyPageToNothing() {
        assertTrue(WebSearch.parse("<html><body>no results</body></html>").isEmpty())
    }

    @Test fun parsesTheWebsearchToolLine() {
        assertEquals(Step.WebSearch("best budget laptops"), ToolCalls.parseOne("websearch best budget laptops"))
        // A plain "search" is still a (browser) search, not a web-search.
        assertEquals(Step.Search("cats"), ToolCalls.parseOne("search cats"))
    }

    @Test fun showMeSearchBecomesAnInAppWebSearch() {
        // Rex wants the answer in the app, so a plain search reads results and replies here.
        assertEquals(
            listOf(Step.WebSearch("best laptops")),
            XarvisAgent.forUser("show me the best laptops", listOf(Step.Search("best laptops"))),
        )
    }

    @Test fun openGoogleStillOpensTheBrowser() {
        assertEquals(
            listOf(Step.Search("cats")),
            XarvisAgent.forUser("open google and search cats", listOf(Step.Search("cats"))),
        )
    }
}
