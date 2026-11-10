package com.xarvis.ai.net

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class DirectTest {
    private val pool = Executors.newCachedThreadPool()
    private val closeLater = ArrayList<AutoCloseable>()

    @After fun tearDown() {
        closeLater.forEach { runCatching { it.close() } }
        pool.shutdownNow()
    }

    @Test fun stunAnswerIsRead() {
        val tx = ByteArray(12) { (it * 7 + 1).toByte() }
        // A binding success answer with XOR-MAPPED-ADDRESS 203.0.113.5:40000.
        val ip = byteArrayOf(203.toByte(), 0, 113, 5)
        val cookie = byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42)
        val port = 40000 xor 0x2112
        val attr = byteArrayOf(0x00, 0x20, 0x00, 0x08, 0x00, 0x01, (port shr 8).toByte(), port.toByte()) +
            ByteArray(4) { (ip[it].toInt() xor cookie[it].toInt()).toByte() }
        val msg = byteArrayOf(0x01, 0x01, 0x00, attr.size.toByte()) + cookie + tx + attr
        val a = Direct.Stun.mapped(msg, msg.size, tx)
        assertEquals(InetSocketAddress(InetAddress.getByName("203.0.113.5"), 40000), a)
        // Someone else's transaction: ignored.
        assertNull(Direct.Stun.mapped(msg, msg.size, ByteArray(12)))
        // The request we send is a 20-byte binding request with the magic cookie.
        val req = Direct.Stun.request(tx)
        assertEquals(20, req.size)
        assertEquals(0x01, req[1].toInt())
        assertEquals(0x2112A442, java.nio.ByteBuffer.wrap(req, 4, 4).int)
    }

    @Test fun stunIpv6AnswerIsRead() {
        val tx = ByteArray(12) { (40 + it).toByte() }
        val ip = InetAddress.getByName("2001:db8::1234").address
        val key = byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42) + tx
        val port = 5555 xor 0x2112
        val attr = byteArrayOf(0x00, 0x20, 0x00, 20, 0x00, 0x02, (port shr 8).toByte(), port.toByte()) +
            ByteArray(16) { (ip[it].toInt() xor key[it].toInt()).toByte() }
        val msg = byteArrayOf(0x01, 0x01, 0x00, attr.size.toByte()) + key.copyOf(4) + tx + attr
        assertEquals(InetSocketAddress(InetAddress.getByName("2001:db8::1234"), 5555), Direct.Stun.mapped(msg, msg.size, tx))
    }

    @Test fun addressesAreWrittenAndReadBack() {
        val v4 = InetSocketAddress(InetAddress.getByName("94.200.1.2"), 4321)
        val v6 = InetSocketAddress(InetAddress.getByName("2405:201:abcd::7"), 50000)
        assertEquals("94.200.1.2:4321", Direct.format(v4))
        assertEquals(v4, Direct.parse(Direct.format(v4)))
        assertEquals(v6, Direct.parse(Direct.format(v6)))
        // Only IP literals: a name would mean a DNS lookup chosen by someone else.
        assertNull(Direct.parse("evil.example.com:80"))
        assertNull(Direct.parse("1.2.3.4:0"))
        assertNull(Direct.parse("garbage"))
    }

    @Test fun linesTravelBothWaysInOrder() {
        val (a, b) = connected(lossy = false)
        val sent = (1..300).map { "line $it " + "x".repeat(it * 7) }
        pool.submit { sent.forEach { a.sendLine(it) } }
        pool.submit { sent.forEach { b.sendLine("back: $it") } }
        sent.forEach { assertEquals(it, b.receiveLine()) }
        sent.forEach { assertEquals("back: $it", a.receiveLine()) }
    }

    @Test fun survivesLossDelayAndReordering() {
        val (a, b) = connected(lossy = true)
        // ~1.2 MB: a few big lines (like screen pictures) and many small ones (like touches).
        val big = (1..8).map { i -> "big$i:" + String(CharArray(120_000) { 'a' + (it + i) % 26 }) }
        val small = (1..400).map { "touch $it" }
        val lines = big + small
        pool.submit { lines.forEach { a.sendLine(it) } }
        val start = System.currentTimeMillis()
        lines.forEach { assertEquals(it, b.receiveLine()) }
        val ms = System.currentTimeMillis() - start
        assertTrue("took $ms ms", ms < 25_000)
        assertTrue("lost packets were re-sent", a.resent > 0)
    }

    @Test fun linesFromSeveralSendersNeverMix() {
        // Remote control sends video frames, pong replies and lock info from different threads at once; a big
        // frame waiting for queue space must not let another thread's line slip into its middle (v1.0.237 bug:
        // "illegal base64 character 2e" on the viewer).
        val (a, b) = connected(lossy = true)
        val senders = 4
        val perSender = 40
        val done = (1..senders).map { t ->
            pool.submit {
                repeat(perSender) { i ->
                    val size = if (t == 1) 60_000 else 200 // one thread sends big "frames", the others small lines
                    a.sendLine("t$t-$i:" + String(CharArray(size) { 'a' + (it + i + t) % 26 }) + ":end")
                }
            }
        }
        val got = HashMap<Int, Int>()
        repeat(senders * perSender) {
            val line = b.receiveLine()
            val m = Regex("""^t(\d+)-(\d+):([a-z]+):end$""").matchEntire(line)
            assertTrue("a mixed-up line: ${line.take(60)}…${line.takeLast(20)}", m != null)
            val t = m!!.groupValues[1].toInt()
            val i = m.groupValues[2].toInt()
            // Each sender's own lines stay in order.
            assertEquals(got[t] ?: 0, i)
            got[t] = i + 1
            val expect = String(CharArray(m.groupValues[3].length) { 'a' + (it + i + t) % 26 })
            assertEquals(expect, m.groupValues[3])
        }
        done.forEach { it.get(30, TimeUnit.SECONDS) }
    }

    @Test fun lastAnswerArrivesEvenWhenClosedRightAway() {
        val (a, b) = connected(lossy = true)
        // Like DeviceLink's server: send the answer, close at once. The answer must still arrive.
        pool.submit {
            b.sendLine("answer " + "z".repeat(50_000))
            b.close()
        }
        assertTrue(a.receiveLine().startsWith("answer zzz"))
        val ended = runCatching { a.receiveLine() }
        assertTrue(ended.exceptionOrNull()?.message.orEmpty().contains("closed"))
    }

    @Test fun nobodyThereGivesUp() {
        val d = Direct.open().also { closeLater += it }
        val silent = DatagramSocket(0).also { closeLater += it }
        val start = System.currentTimeMillis()
        val c = d.connect(ByteArray(16) { 1 }, listOf("127.0.0.1:${silent.localPort}"), initiator = true, timeoutMs = 600)
        assertNull(c)
        assertTrue(System.currentTimeMillis() - start in 500..3_000)
    }

    @Test fun strangersPacketsAreIgnored() {
        val (a, b) = connected(lossy = false)
        // Junk without the session token, aimed at b's port, changes nothing.
        DatagramSocket(0).use { junk ->
            val bytes = ByteArray(100) { 7 }
            junk.send(DatagramPacket(bytes, bytes.size, a.remote))
        }
        a.sendLine("hello")
        assertEquals("hello", b.receiveLine())
    }

    /** Two connected ends, punched through a local UDP "network" (lossy: 4% loss, 15–45 ms delay, reordering). */
    private fun connected(lossy: Boolean): Pair<Direct.Conn, Direct.Conn> {
        val da = Direct.open().also { closeLater += it }
        val db = Direct.open().also { closeLater += it }
        val net = Network(
            InetSocketAddress(InetAddress.getLoopbackAddress(), da.port),
            InetSocketAddress(InetAddress.getLoopbackAddress(), db.port),
            if (lossy) 0.04 else 0.0, if (lossy) 15 else 0, if (lossy) 30 else 0,
        ).also { closeLater += it }
        val token = ByteArray(16) { (it * 3).toByte() }
        val target = listOf("127.0.0.1:${net.port}")
        val fa = pool.submit(Callable { da.connect(token, target, initiator = true, timeoutMs = 5_000) })
        val fb = pool.submit(Callable { db.connect(token, target, initiator = false, timeoutMs = 8_000) })
        val a = fa.get(10, TimeUnit.SECONDS) ?: throw AssertionError("not connected")
        // The answering side is through once the asking side starts talking.
        pool.submit { a.sendLine("hi") }
        val b = fb.get(10, TimeUnit.SECONDS) ?: throw AssertionError("the answering side never connected")
        assertEquals("hi", b.receiveLine())
        a.readTimeoutMs = 20_000
        b.readTimeoutMs = 20_000
        closeLater += a
        closeLater += b
        return a to b
    }

    /** A tiny network between [a] and [b] through one socket: drops, delays and reorders packets. */
    private class Network(
        val a: SocketAddress, val b: SocketAddress, val loss: Double, val delayMs: Int, val jitterMs: Int,
    ) : AutoCloseable {
        private val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
        private val timer = Executors.newSingleThreadScheduledExecutor()
        private val random = Random(42)
        val port: Int get() = socket.localPort

        init {
            Thread {
                val buf = ByteArray(4096)
                while (!socket.isClosed) {
                    val p = DatagramPacket(buf, buf.size)
                    try { socket.receive(p) } catch (e: Exception) { break }
                    val to = if (p.socketAddress == a) b else a
                    if (random.nextDouble() < loss) continue
                    val copy = p.data.copyOf(p.length)
                    val wait = delayMs + if (jitterMs > 0) random.nextInt(jitterMs) else 0
                    val send = Runnable { runCatching { socket.send(DatagramPacket(copy, copy.size, to)) } }
                    if (wait == 0) send.run() else timer.schedule(send, wait.toLong(), TimeUnit.MILLISECONDS)
                }
            }.apply { isDaemon = true }.start()
        }

        override fun close() {
            timer.shutdownNow()
            socket.close()
        }
    }
}
