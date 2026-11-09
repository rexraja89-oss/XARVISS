package com.xarvis.ai.net

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class MqttPacketsTest {
    @Test fun remainingLength() {
        assertArrayEquals(byteArrayOf(0), Mqtt.Packets.remainingLength(0))
        assertArrayEquals(byteArrayOf(127), Mqtt.Packets.remainingLength(127))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 1), Mqtt.Packets.remainingLength(128))
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0x7F), Mqtt.Packets.remainingLength(16_383))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x80.toByte(), 1), Mqtt.Packets.remainingLength(16_384))
    }

    @Test fun connectPacket() {
        val p = Mqtt.Packets.connect("xv-1", "rex", "pw", 60, cleanSession = false, will = Triple("xv/a/p", "0".toByteArray(), true))
        assertEquals(0x10, p[0].toInt() and 0xFF)
        // "MQTT", level 4, flags: user + password + will retain + will QoS 1 + will
        val body = p.copyOfRange(2, p.size)
        assertEquals("MQTT", String(body, 2, 4))
        assertEquals(4, body[6].toInt())
        assertEquals(0x80 or 0x40 or 0x20 or 0x08 or 0x04, body[7].toInt() and 0xFF)
        assertEquals(60, body[9].toInt())
    }

    @Test fun publishRoundTrip() {
        val payload = ByteArray(300) { (it % 251).toByte() }
        val bytes = Mqtt.Packets.publish("xv/abc", payload, qos = 1, retain = false, packetId = 513, dup = false)
        val (type, body) = Mqtt.Packets.read(ByteArrayInputStream(bytes))
        assertEquals(Mqtt.Packets.PUBLISH, type)
        val pub = Mqtt.Packets.parsePublish(body, Mqtt.Packets.lastFlags.get())
        assertEquals("xv/abc", pub.topic)
        assertEquals(1, pub.qos)
        assertEquals(513, pub.packetId)
        assertArrayEquals(payload, pub.payload)
    }

    @Test fun subscribeAndAck() {
        val sub = Mqtt.Packets.subscribe(7, listOf("xv/a", "xv/b/p"), 1)
        assertEquals(0x82, sub[0].toInt() and 0xFF)
        val ack = Mqtt.Packets.puback(258)
        assertArrayEquals(byteArrayOf(0x40, 2, 1, 2), ack)
    }
}

class RelayEnvelopeTest {
    @Test fun smallLinesTravelWhole() {
        val parts = Relay.Envelope.split("dev1", "c1", "o", "hello")
        assertEquals(1, parts.size)
        val env = JSONObject(parts[0])
        assertEquals("hello", Relay.Envelope.assemble(env, HashMap()))
        assertEquals("o", env.getString("k"))
    }

    @Test fun bigLinesAreSplitAndJoined() {
        val line = buildString { repeat(Relay.Envelope.PART * 2 + 123) { append(('a' + it % 26)) } }
        val parts = Relay.Envelope.split("dev1", "c1", "d", line)
        assertEquals(3, parts.size)
        val store = HashMap<String, Array<String?>>()
        // Arriving out of order is fine.
        assertNull(Relay.Envelope.assemble(JSONObject(parts[2]), store))
        assertNull(Relay.Envelope.assemble(JSONObject(parts[0]), store))
        assertEquals(line, Relay.Envelope.assemble(JSONObject(parts[1]), store))
        assertTrue(store.isEmpty())
    }

    @Test fun hostAndMailbox() {
        assertEquals("ab12.ala.asia-southeast1.emqxsl.com" to 8883, Relay.splitHost(" ab12.ala.asia-southeast1.emqxsl.com "))
        assertEquals("abc.emqxsl.com" to 8884, Relay.splitHost("mqtts://abc.emqxsl.com:8884/"))
        val m = Relay.mailbox("device-1")
        assertTrue(m.startsWith("xv/") && m.length == 27)
        assertEquals(m, Relay.mailbox("device-1"))
        assertTrue(m != Relay.mailbox("device-2"))
    }

    @Test fun screenLimitLeavesRoomForAlerts() {
        // Both phones count the same stream, so two phones at the limit stay inside the free 1 GB.
        assertTrue(Relay.SCREEN_LIMIT_BYTES * 2 < Relay.FREE_BYTES_MONTH)
        assertTrue(Relay.WARN_BYTES < Relay.SCREEN_LIMIT_BYTES)
        assertTrue(Relay.screenLimitMessage(Relay.SCREEN_LIMIT_BYTES).contains("400 MB"))
    }

    @Test fun relayPartsFitTheBroker() {
        // EMQX Serverless takes at most 1 MB per message; a part plus its envelope must stay under it.
        val line = "x".repeat(Relay.Envelope.PART)
        val part = Relay.Envelope.split("a-long-device-id-0123456789", "cid-0123456789", "d", line + "y")[0]
        assertTrue(part.toByteArray().size < 1_000_000)
    }
}
