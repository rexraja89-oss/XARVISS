package com.xarvis.ai.net

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A small MQTT 3.1.1 client (connect, subscribe, publish QoS 0/1, acks, ping) for XARVIS's relay. Written here
 * instead of adding a library: the phones' installers judge every library added (the benco has refused builds).
 * One instance is one connection; the relay makes a new one after a drop.
 */
class Mqtt(
    private val host: String,
    private val port: Int,
    private val clientId: String,
    private val username: String?,
    private val password: String?,
    private val tls: Boolean = true,
    private val keepAliveSec: Int = 60,
    /** The "last will": published by the server if this client vanishes (topic, payload, retained). */
    private val will: Triple<String, ByteArray, Boolean>? = null,
) {
    interface Listener {
        fun onMessage(topic: String, payload: ByteArray)
        fun onClosed(why: String)
    }

    class MqttException(message: String) : Exception(message)

    private var socket: Socket? = null
    private var output: OutputStream? = null
    private val nextId = AtomicInteger(1)
    /** QoS 1 messages the server hasn't acknowledged yet (re-sent by the relay after a reconnect). */
    val unacked = ConcurrentHashMap<Int, ByteArray>()
    @Volatile var lastHeard = System.currentTimeMillis()
        private set
    @Volatile var closed = false
        private set

    /** Bytes sent and received on this connection (for the relay's monthly data count). */
    val bytesOut = java.util.concurrent.atomic.AtomicLong()
    val bytesIn = java.util.concurrent.atomic.AtomicLong()

    /** Connects and starts reading; returns whether the server still had this client's session. */
    fun connect(listener: Listener, connectTimeoutMs: Int = 10_000): Boolean {
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), connectTimeoutMs)
        val s = if (tls) {
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true) as SSLSocket
            ssl.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
                runCatching { ssl.close() }
                throw MqttException("the relay's certificate isn't for $host")
            }
            ssl
        } else {
            raw
        }
        s.soTimeout = (keepAliveSec * 1_500) // the server answers pings; silence longer than this is a dead line
        s.tcpNoDelay = true
        socket = s
        output = s.getOutputStream()
        val input = s.getInputStream()
        write(Packets.connect(clientId, username, password, keepAliveSec, cleanSession = false, will = will))
        val (type, body) = Packets.read(input)
        if (type != Packets.CONNACK || body.size < 2) throw MqttException("the relay didn't accept the connection")
        val code = body[1].toInt()
        if (code != 0) {
            close()
            throw MqttException(
                when (code) {
                    4, 5 -> "the relay refused the username or password"
                    2 -> "the relay refused this device's id"
                    3 -> "the relay is unavailable right now"
                    else -> "the relay refused the connection (code $code)"
                },
            )
        }
        lastHeard = System.currentTimeMillis()
        val sessionPresent = (body[0].toInt() and 1) == 1
        Thread({ readLoop(input, listener) }, "xarvis-relay-read").apply { isDaemon = true }.start()
        return sessionPresent
    }

    private fun readLoop(input: InputStream, listener: Listener) {
        val why = try {
            while (!closed) {
                val (type, body) = Packets.read(input)
                bytesIn.addAndGet(body.size + 2L)
                lastHeard = System.currentTimeMillis()
                when (type) {
                    Packets.PUBLISH -> {
                        val p = Packets.parsePublish(body, flags = lastFlags)
                        if (p.qos == 1) write(Packets.puback(p.packetId))
                        runCatching { listener.onMessage(p.topic, p.payload) }
                    }
                    Packets.PUBACK -> if (body.size >= 2) unacked.remove(((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF))
                    else -> Unit // SUBACK, PINGRESP
                }
            }
            "closed"
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
        close()
        listener.onClosed(why)
    }

    /** The flags nibble of the packet [Packets.read] just returned (QoS, retain, dup). */
    private val lastFlags: Int get() = Packets.lastFlags.get() ?: 0

    fun subscribe(topics: List<String>, qos: Int = 1) {
        if (topics.isEmpty()) return
        write(Packets.subscribe(newId(), topics, qos))
    }

    /** Sends [payload] to [topic]; QoS 1 is kept until the server acknowledges it. */
    fun publish(topic: String, payload: ByteArray, qos: Int = 1, retain: Boolean = false) {
        val id = if (qos > 0) newId() else 0
        val packet = Packets.publish(topic, payload, qos, retain, id, dup = false)
        if (qos > 0) unacked[id] = Packets.publish(topic, payload, qos, retain, id, dup = true)
        write(packet)
    }

    /** Re-sends what a previous connection never got acknowledged. */
    fun resend(packets: Collection<ByteArray>) = packets.forEach { write(it) }

    fun ping() = write(Packets.PINGREQ_BYTES)

    fun disconnect() {
        runCatching { write(Packets.DISCONNECT_BYTES) }
        close()
    }

    fun close() {
        closed = true
        runCatching { socket?.close() }
    }

    private fun newId(): Int {
        val id = nextId.getAndIncrement()
        if (id >= 65_535) nextId.set(1)
        return id.coerceIn(1, 65_535)
    }

    private fun write(bytes: ByteArray) {
        val out = output ?: throw MqttException("not connected")
        synchronized(out) {
            out.write(bytes)
            out.flush()
        }
        bytesOut.addAndGet(bytes.size.toLong())
    }

    /** MQTT 3.1.1 packets, built and read as plain bytes (tested on the computer, no server needed). */
    object Packets {
        const val CONNECT = 1
        const val CONNACK = 2
        const val PUBLISH = 3
        const val PUBACK = 4
        const val SUBSCRIBE = 8
        const val SUBACK = 9
        const val PINGREQ = 12
        const val PINGRESP = 13
        const val DISCONNECT = 14
        val PINGREQ_BYTES = byteArrayOf(0xC0.toByte(), 0)
        val DISCONNECT_BYTES = byteArrayOf(0xE0.toByte(), 0)

        /** The flags of the last packet read on each reading thread. */
        val lastFlags = ThreadLocal<Int>()

        data class Publish(val topic: String, val payload: ByteArray, val qos: Int, val packetId: Int, val retain: Boolean)

        fun remainingLength(n: Int): ByteArray {
            val out = ByteArrayOutputStream()
            var x = n
            do {
                var digit = x % 128
                x /= 128
                if (x > 0) digit = digit or 0x80
                out.write(digit)
            } while (x > 0)
            return out.toByteArray()
        }

        private fun packet(firstByte: Int, body: ByteArray): ByteArray {
            val len = remainingLength(body.size)
            return byteArrayOf(firstByte.toByte()) + len + body
        }

        private fun str(s: String): ByteArray = bin(s.toByteArray(Charsets.UTF_8))
        private fun bin(b: ByteArray): ByteArray = byteArrayOf((b.size shr 8).toByte(), (b.size and 0xFF).toByte()) + b
        private fun u16(n: Int): ByteArray = byteArrayOf((n shr 8).toByte(), (n and 0xFF).toByte())

        fun connect(
            clientId: String, username: String?, password: String?, keepAliveSec: Int, cleanSession: Boolean,
            will: Triple<String, ByteArray, Boolean>? = null,
        ): ByteArray {
            var flags = 0
            if (cleanSession) flags = flags or 0x02
            if (will != null) {
                flags = flags or 0x04 or (1 shl 3) // will, QoS 1
                if (will.third) flags = flags or 0x20
            }
            if (username != null) flags = flags or 0x80
            if (password != null) flags = flags or 0x40
            val out = ByteArrayOutputStream()
            out.write(str("MQTT"))
            out.write(4) // protocol level 3.1.1
            out.write(flags)
            out.write(u16(keepAliveSec))
            out.write(str(clientId))
            if (will != null) {
                out.write(str(will.first))
                out.write(bin(will.second))
            }
            if (username != null) out.write(str(username))
            if (password != null) out.write(str(password))
            return packet(CONNECT shl 4, out.toByteArray())
        }

        fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean, packetId: Int, dup: Boolean): ByteArray {
            var first = PUBLISH shl 4
            if (dup && qos > 0) first = first or 0x08
            first = first or (qos shl 1)
            if (retain) first = first or 0x01
            val out = ByteArrayOutputStream()
            out.write(str(topic))
            if (qos > 0) out.write(u16(packetId))
            out.write(payload)
            return packet(first, out.toByteArray())
        }

        fun subscribe(packetId: Int, topics: List<String>, qos: Int): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(u16(packetId))
            topics.forEach { out.write(str(it)); out.write(qos) }
            return packet((SUBSCRIBE shl 4) or 0x02, out.toByteArray())
        }

        fun puback(packetId: Int): ByteArray = packet(PUBACK shl 4, u16(packetId))

        /** Reads one packet: its type and body ([lastFlags] gets its flags). */
        fun read(input: InputStream): Pair<Int, ByteArray> {
            val first = input.read()
            if (first < 0) throw EOFException("the relay closed the connection")
            var multiplier = 1
            var length = 0
            var i = 0
            while (true) {
                val b = input.read()
                if (b < 0) throw EOFException("the relay closed the connection")
                length += (b and 0x7F) * multiplier
                if (b and 0x80 == 0) break
                multiplier *= 128
                if (++i > 3) throw MqttException("bad packet length")
            }
            val body = ByteArray(length)
            var off = 0
            while (off < length) {
                val n = input.read(body, off, length - off)
                if (n < 0) throw EOFException("the relay closed the connection")
                off += n
            }
            lastFlags.set(first and 0x0F)
            return (first shr 4) to body
        }

        fun parsePublish(body: ByteArray, flags: Int): Publish {
            val qos = (flags shr 1) and 0x03
            val topicLen = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
            val topic = String(body, 2, topicLen, Charsets.UTF_8)
            var pos = 2 + topicLen
            var id = 0
            if (qos > 0) {
                id = ((body[pos].toInt() and 0xFF) shl 8) or (body[pos + 1].toInt() and 0xFF)
                pos += 2
            }
            return Publish(topic, body.copyOfRange(pos, body.size), qos, id, (flags and 1) == 1)
        }
    }
}
