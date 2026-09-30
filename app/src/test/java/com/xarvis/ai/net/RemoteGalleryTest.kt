package com.xarvis.ai.net

import com.xarvis.ai.agent.ToolCalls
import com.xarvis.ai.workflow.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteGalleryTest {
    @Test fun toolLineParsesTheDevice() {
        assertEquals(listOf(Step.RemoteGallery("S22")), ToolCalls.parse("TOOL: remote gallery S22"))
        assertEquals(listOf(Step.RemoteGallery("benco")), ToolCalls.parse("TOOL: remote photos benco"))
    }

    @Test fun ordinaryPhotoTalkIsNotAGalleryRequest() {
        // "photos of my cat" must not become a remote-gallery request.
        assertEquals(emptyList<Step>(), ToolCalls.parse("photos of my cat").filterIsInstance<Step.RemoteGallery>())
    }
}
