package com.xarvis.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Product/shopping questions should fetch live specs, prices and pictures, not answer from memory. */
class ProductResearchTest {

    @Test fun recognisesShoppingQueriesAndStripsFiller() {
        assertEquals("phones under 30000 inr", XarvisAgent.wantsProductResearch("show me phones under 30000 inr with proper pictures and comparison"))
        assertEquals("laptops in mid range", XarvisAgent.wantsProductResearch("show me some good laptops in mid range"))
    }

    @Test fun recognisesABrandNameEvenWithoutShoppingWords() {
        assertEquals("motorola edge fusion 60", XarvisAgent.wantsProductResearch("motorola edge fusion 60"))
    }

    @Test fun ordinaryChatIsNotProductResearch() {
        assertNull(XarvisAgent.wantsProductResearch("what's the weather in Dubai"))
        assertNull(XarvisAgent.wantsProductResearch("remind me to call Atiq at 6pm"))
        assertNull(XarvisAgent.wantsProductResearch("hello xarvis"))
        // The real misfires: photo/camera commands are not shopping.
        assertNull(XarvisAgent.wantsProductResearch("hey xarvis take photo with front camera and show me here in benco screen."))
        assertNull(XarvisAgent.wantsProductResearch("open rear camera and take a photo"))
        assertNull(XarvisAgent.wantsProductResearch("take a photo with my galaxy"))
        // …but a real question still is.
        assertEquals("best camera phones under 40000", XarvisAgent.wantsProductResearch("best camera phones under 40000"))
    }
}
