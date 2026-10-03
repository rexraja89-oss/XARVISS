package com.xarvis.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading Brave Search API responses (samples shaped like its real JSON). */
class BraveSearchTest {

    @Test fun readsWebResults() {
        val json = """{"web":{"results":[
            {"title":"Motorola Edge 60 Fusion <b>price</b>","url":"https://x.com/a","description":"₹22,999 with a <strong>Dimensity 7400</strong>."},
            {"title":"Review","url":"https://y.com/b","description":"Good mid-ranger."}
        ]}}"""
        val r = BraveSearch.parseWeb(json)
        assertEquals(2, r.size)
        assertEquals("Motorola Edge 60 Fusion price", r[0].title) // tags stripped
        assertEquals("https://x.com/a", r[0].url)
        assertTrue(r[0].snippet.contains("₹22,999"))
    }

    @Test fun readsImageUrlsPreferringTheFullImage() {
        val json = """{"results":[
            {"title":"P1","properties":{"url":"https://img.com/full1.jpg"},"thumbnail":{"src":"https://img.com/t1.jpg"}},
            {"title":"P2","thumbnail":{"src":"https://img.com/t2.jpg"}}
        ]}"""
        assertEquals(listOf("https://img.com/full1.jpg", "https://img.com/t2.jpg"), BraveSearch.parseImages(json))
    }

    @Test fun emptyOrBadJsonGivesNothing() {
        assertEquals(emptyList<BraveSearch.Result>(), BraveSearch.parseWeb("""{"web":{"results":[]}}"""))
        assertEquals(emptyList<String>(), BraveSearch.parseImages("""{}"""))
    }
}
