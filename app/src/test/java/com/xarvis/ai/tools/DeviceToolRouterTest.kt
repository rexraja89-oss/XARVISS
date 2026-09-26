package com.xarvis.ai.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceToolRouterTest {

    private class Fake(override val label: String, val word: String, val reply: () -> String?) : DeviceTool {
        override fun matches(message: String) = message.contains(word)
        override suspend fun read(message: String) = reply()
    }

    @Test fun onlyMatchingToolsRunAndNullsAreDropped() = runBlocking {
        val router = DeviceToolRouter(
            listOf(
                Fake("battery", "battery") { "Battery: 80%" },
                Fake("location", "where") { "Current location: Lahore" },
                Fake("contacts", "number") { null },
            )
        )
        var progress = ""
        assertEquals(listOf("Battery: 80%"), router.gather("battery and number please") { progress = it })
        assertEquals("Checking the phone's battery and contacts…", progress)
        assertEquals(emptyList<String>(), router.gather("tell me a joke"))
    }

    @Test fun aFailingToolExplainsItselfInsteadOfCrashing() = runBlocking {
        val router = DeviceToolRouter(listOf(Fake("battery", "battery") { error("sensor broke") }))
        assertEquals(listOf("Battery: unavailable (sensor broke)"), router.gather("battery"))
    }

    @Test fun deviceDataGoesBeforeTheMessage() {
        assertEquals(
            "[DEVICE DATA] Battery: 80%\n\nhow much battery?",
            DeviceToolRouter.withDeviceData("how much battery?", listOf("Battery: 80%")),
        )
        assertEquals("hi", DeviceToolRouter.withDeviceData("hi", emptyList()))
    }
}
