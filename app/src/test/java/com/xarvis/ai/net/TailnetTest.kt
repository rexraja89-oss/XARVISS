package com.xarvis.ai.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetTest {
    @Test fun recognisesTailscaleAddresses() {
        assertTrue(DeviceLink.isTailnet("100.101.102.103"))
        assertTrue(DeviceLink.isTailnet("100.64.0.1"))
        assertFalse(DeviceLink.isTailnet("100.128.0.1"))
        assertFalse(DeviceLink.isTailnet("192.168.1.20"))
        assertFalse(DeviceLink.isTailnet("fe80::1"))
    }
}
