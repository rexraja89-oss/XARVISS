package com.xarvis.ai.tools

import com.xarvis.ai.agent.ToolCalls
import com.xarvis.ai.agent.XarvisAgent
import com.xarvis.ai.workflow.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The image-search tool: read DuckDuckGo's token + results, recognise the command, route it. */
class WebImagesTest {

    @Test fun pullsTheVqdTokenFromThePage() {
        assertEquals("4-123abcDEF", WebImages.vqd("""<script>var x=1;vqd="4-123abcDEF";more</script>"""))
    }

    @Test fun parsesImageAndThumbnailPairs() {
        val json = """{"results":[
            {"image":"https://a.com/1.jpg","thumbnail":"https://t.com/1.jpg","title":"P1"},
            {"image":"https://a.com/2.jpg","thumbnail":"https://t.com/2.jpg"}
        ]}"""
        val r = WebImages.parse(json)
        assertEquals(2, r.size)
        assertEquals("https://a.com/1.jpg", r[0].first)
        assertEquals("https://t.com/1.jpg", r[0].second)
    }

    @Test fun parsesTheImagesToolLine() {
        assertEquals(Step.WebImages("iphone 16"), ToolCalls.parseOne("images iphone 16"))
        assertEquals(Step.WebImages("galaxy s24"), ToolCalls.parseOne("pictures of galaxy s24"))
    }

    @Test fun withPicturesAddsAnImageSearchBesideTheWebSearch() {
        val out = XarvisAgent.forUser(
            "show me phones under 30000 with pictures and comparison",
            listOf(Step.Search("phones under 30000")),
        )
        assertTrue(out.contains(Step.WebSearch("phones under 30000")))
        assertTrue(out.contains(Step.WebImages("phones under 30000")))
    }

    @Test fun aLinkedPhoneGalleryRequestDoesNotTriggerAWebImageSearch() {
        // "photos" here means the S22's gallery (LaunchApp), not a web image search.
        val out = XarvisAgent.forUser("show me s22 photos", listOf(Step.LaunchApp("gallery")))
        assertTrue(out.none { it is Step.WebImages })
    }
}
