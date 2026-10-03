package com.xarvis.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Test

/** Reading Wikipedia's answers (samples shaped like its real API responses). */
class WebLookupTest {

    @Test fun readsSearchTitles() {
        val json = """{"pages":[{"id":1,"key":"One_Ring","title":"One Ring","excerpt":"..."},{"id":2,"key":"The_Lord_of_the_Rings","title":"The Lord of the Rings"}]}"""
        assertEquals(listOf("One Ring", "The Lord of the Rings"), WebLookup.searchTitles(json))
        assertEquals(emptyList<String>(), WebLookup.searchTitles("""{"pages":[]}"""))
    }

    @Test fun readsSummaries() {
        val json = """{"title":"One Ring","extract":"The One Ring is a central plot element in J. R. R. Tolkien's The Lord of the Rings."}"""
        assertEquals("One Ring: The One Ring is a central plot element in J. R. R. Tolkien's The Lord of the Rings.", WebLookup.summaryText(json))
        assertEquals(null, WebLookup.summaryText("""{"title":"Empty","extract":""}"""))
    }

    @Test fun readsTheLeadImageUrl() {
        val withOriginal = """{"title":"Phone","originalimage":{"source":"https://upload.wikimedia.org/a.jpg"},"thumbnail":{"source":"https://upload.wikimedia.org/a-thumb.jpg"}}"""
        assertEquals("https://upload.wikimedia.org/a.jpg", WebLookup.imageIn(withOriginal))
        val thumbOnly = """{"title":"Phone","thumbnail":{"source":"https://upload.wikimedia.org/t.jpg"}}"""
        assertEquals("https://upload.wikimedia.org/t.jpg", WebLookup.imageIn(thumbOnly))
        assertEquals(null, WebLookup.imageIn("""{"title":"NoPic"}"""))
    }
}
