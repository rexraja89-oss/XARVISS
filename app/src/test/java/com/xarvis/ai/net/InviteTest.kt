package com.xarvis.ai.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class InviteTest {
    private fun code(payload: JSONObject) =
        DeviceLink.INVITE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().toByteArray())

    private val payload = JSONObject().put("v", 1).put("r", "ab12.ala.asia-southeast1.emqxsl.com:8883").put("u", "xarvis")
        .put("p", "pw_with-odd/chars+").put("d", "device-1").put("n", "Galaxy S22 Ultra").put("i", "abcd1234").put("s", "c2VjcmV0c2VjcmV0")

    @Test fun inviteIsFoundInsideAWholeWhatsAppMessage() {
        val message = "Hi! Install XARVIS Helper so I can help you:\n\n1) Download: https://example.com/x.apk\n\n" +
            code(payload) + "\n\n(This invite works once, for 7 days.)"
        val inv = DeviceLink.parseInvite(message)
        assertNotNull(inv)
        inv!!
        assertEquals("ab12.ala.asia-southeast1.emqxsl.com:8883", inv.getString("r"))
        assertEquals("pw_with-odd/chars+", inv.getString("p"))
        assertEquals("device-1", inv.getString("d"))
    }

    @Test fun notAnInvite() {
        assertNull(DeviceLink.parseInvite("hello"))
        assertNull(DeviceLink.parseInvite(DeviceLink.INVITE_PREFIX + "not-base64-json"))
        // A cut-off invite (missing fields) isn't accepted.
        assertNull(DeviceLink.parseInvite(code(JSONObject().put("r", "x:1"))))
    }
}
