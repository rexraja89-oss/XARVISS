package com.xarvis.ai.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.TreeMap
import java.util.TreeSet
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * XARVIS Direct (Rex, Nov 2026: "the same speed as Tailscale, without Tailscale"): remote control and files go
 * straight between the two phones over UDP instead of through the relay — no VPN, no relay data used.
 *
 * How two phones on different networks (home Wi-Fi, mobile data, UAE ↔ India) find a way to each other; the
 * relay only carries the two short lines that set it up (DeviceLink op "direct"):
 *  1. Each opens a UDP socket and lists where it might be reached ([candidates]): its public IPv6 addresses
 *     (most mobile networks in the UAE and India give phones one, with no NAT in the way), its home-Wi-Fi
 *     address, and its public IPv4 address:port as a STUN server sees it (behind the carrier's or router's NAT).
 *  2. The two lists are swapped through the relay, with a random session token.
 *  3. Both phones send small probes to every address of the other at the same time ([connect]). Each outgoing
 *     probe opens the sender's own NAT/firewall for the answer, so within a round trip or two probes get
 *     through both ways ("hole punching"). The first address that answers is the line.
 *  4. [Conn] carries DeviceLink's lines over it reliably and in order, like TCP: numbered packets, acks with
 *     a map of the next 256 packets, re-sending what got lost, and a congestion window that backs off when
 *     packets get lost (and paces sending), so a busy mobile link isn't flooded.
 *
 * It can't always work: when both phones sit behind "symmetric" carrier NATs (some mobile IPv4) no probe gets
 * through, and DeviceLink stays on the relay. Everything carried is already encrypted with the pairing key
 * (AES-GCM frames), so this layer only adds the session token, which keeps strangers' packets out.
 */
class Direct private constructor(private val socket: DatagramSocket) : AutoCloseable {

    private val random = SecureRandom()
    @Volatile private var conn: Conn? = null
    @Volatile private var started = false

    /** The local UDP port (the same one every candidate address uses). */
    val port: Int get() = socket.localPort

    /**
     * Where the other phone may reach this one, as "1.2.3.4:5678" / "[2001:db8::1]:5678": this phone's public
     * IPv6 and home-network addresses, then the public IPv4 address(es) STUN servers saw (skipped when
     * [stunServers] is empty — the tests run without the internet).
     */
    fun candidates(stunServers: List<Pair<String, Int>> = STUN_SERVERS, waitMs: Long = STUN_WAIT_MS): List<String> {
        val out = LinkedHashSet<String>()
        localAddresses().forEach { out += format(InetSocketAddress(it, port)) }
        if (stunServers.isNotEmpty()) runCatching { stun(stunServers, waitMs) }.getOrDefault(emptyList()).forEach { out += format(it) }
        return out.take(MAX_CANDIDATES)
    }

    /** STUN: asks public servers which address and port this socket's packets come from. */
    private fun stun(servers: List<Pair<String, Int>>, waitMs: Long): List<InetSocketAddress> {
        val tx = ByteArray(12).also(random::nextBytes)
        val request = Stun.request(tx)
        var sent = 0
        for ((host, p) in servers) {
            val addresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
            // One IPv4 and one IPv6 address per server is plenty.
            listOfNotNull(addresses.firstOrNull { it is Inet4Address }, addresses.firstOrNull { it is Inet6Address }).forEach { a ->
                if (runCatching { socket.send(DatagramPacket(request, request.size, InetSocketAddress(a, p))) }.isSuccess) sent++
            }
        }
        val found = LinkedHashSet<InetSocketAddress>()
        if (sent == 0) return emptyList()
        val buf = ByteArray(1500)
        val deadline = System.currentTimeMillis() + waitMs
        var answers = 0
        while (answers < sent) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            socket.soTimeout = left.toInt().coerceAtLeast(1)
            val pkt = DatagramPacket(buf, buf.size)
            try {
                socket.receive(pkt)
            } catch (e: SocketTimeoutException) {
                break
            }
            Stun.mapped(buf, pkt.length, tx)?.let { found += it; answers++ }
        }
        return found.toList()
    }

    /**
     * Punches through to the other phone at [theirs] (its [candidates]). [token]: the session's secret (both sides
     * the same). The asking side ([initiator]) gives up after [timeoutMs] (DeviceLink then uses the relay); the
     * answering side waits that long for the first probe or packet. Null: no way through.
     */
    fun connect(token: ByteArray, theirs: List<String>, initiator: Boolean, timeoutMs: Long): Conn? {
        check(!started) { "one connection per Direct" }
        started = true
        val tok = token.copyOf(TOKEN_BYTES)
        val targets = theirs.mapNotNull { parse(it) }.distinct().take(MAX_CANDIDATES)
        val start = System.currentTimeMillis()
        val deadline = start + timeoutMs
        val buf = ByteArray(2048)
        val sentAt = HashMap<Int, Long>()
        var round = 0
        var nextProbe = 0L
        var heardFrom: SocketAddress? = null
        val early = ArrayList<Pair<ByteArray, SocketAddress>>()
        while (true) {
            val now = System.currentTimeMillis()
            if (now >= deadline) return null
            if (now >= nextProbe) {
                round++
                val probe = Packets.probe(tok, round)
                sentAt[round] = System.nanoTime()
                (targets + listOfNotNull(heardFrom)).distinct().forEach { a ->
                    runCatching { socket.send(DatagramPacket(probe, probe.size, a)) }
                }
                nextProbe = now + PROBE_EVERY_MS
            }
            socket.soTimeout = (minOf(nextProbe, deadline) - System.currentTimeMillis()).toInt().coerceAtLeast(1)
            val pkt = DatagramPacket(buf, buf.size)
            try {
                socket.receive(pkt)
            } catch (e: SocketTimeoutException) {
                continue
            }
            if (!Packets.ours(buf, pkt.length, tok)) continue
            val from = pkt.socketAddress
            when (buf[TOKEN_BYTES].toInt()) {
                PROBE -> {
                    // Their probe got through: answer it (that answer is what confirms the line for them).
                    val ack = Packets.probeAck(tok, Packets.int(buf, TOKEN_BYTES + 1))
                    runCatching { socket.send(DatagramPacket(ack, ack.size, from)) }
                    heardFrom = from
                }
                PROBE_ACK -> {
                    val r = Packets.int(buf, TOKEN_BYTES + 1)
                    val rttNs = sentAt[r]?.let { System.nanoTime() - it } ?: (DEFAULT_RTT_MS * 1_000_000L)
                    return Conn(socket, tok, from, early, rttNs).also { conn = it }
                }
                DATA, ACK, PING -> if (!initiator) {
                    // The asking phone already uses the line: this side is through too.
                    early += buf.copyOf(pkt.length) to from
                    return Conn(socket, tok, from, early, DEFAULT_RTT_MS * 1_000_000L).also { conn = it }
                }
            }
        }
    }

    /** Closes the socket unless a connection now owns it (closing the [Conn] closes it then). */
    override fun close() {
        if (conn == null) runCatching { socket.close() }
    }

    /**
     * One reliable, ordered, two-way conversation over the punched line: DeviceLink's lines in, the same lines out.
     * [sendLine] waits when too much is queued (like a full TCP send buffer), which keeps the delay low.
     */
    class Conn internal constructor(
        private val socket: DatagramSocket,
        private val tok: ByteArray,
        firstRemote: SocketAddress,
        early: List<Pair<ByteArray, SocketAddress>>,
        initialRttNs: Long,
    ) : AutoCloseable {
        /** Where the other phone's packets come from now (followed if its network changes the address). */
        @Volatile var remote: SocketAddress = firstRemote
            private set

        /** How long [receiveLine] waits for the next line. */
        @Volatile var readTimeoutMs: Int = 15_000

        private val lock = Object()

        // ---- sending (guarded by lock) ----
        private class Out(val seq: Int, val data: ByteArray) {
            var firstSentNs = 0L
            var sentNs = 0L
            var sends = 0
        }
        private val queue = ArrayDeque<Out>()
        private val flight = TreeMap<Int, Out>()
        private val lost = TreeSet<Int>()
        private var queuedBytes = 0
        private var nextSeq = 0
        private var peerCum = 0
        /** When the most recently sent packet the other side has acknowledged was sent (finds what got lost). */
        private var ackedSentNs = 0L
        private var cwnd = INITIAL_CWND
        private var ssthresh = MAX_CWND
        private var recoverFrom = 0
        private var srttNs = initialRttNs.coerceIn(MIN_RTT_NS, MAX_RTO_NS)
        private var rttvarNs = srttNs / 2
        /** The shortest round trip seen: the line's own delay, with nothing queued anywhere. */
        private var minRttNs = srttNs
        private var rtoNs = rto()
        private var nextSendNs = 0L
        private var lastSendNs = System.nanoTime()
        private var closing = false
        private var closingSinceNs = 0L
        @Volatile private var closed = false
        @Volatile private var failure: String? = null

        // ---- receiving (reader thread only) ----
        private var recvNext = 0
        private val ooo = TreeMap<Int, ByteArray>()
        private val line = ByteArrayOutputStream()
        private val inbox = LinkedBlockingQueue<Any>()
        @Volatile private var lastHeardNs = System.nanoTime()

        /** Packets sent and re-sent (for the tests and the log). */
        @Volatile var packetsSent = 0L
            private set
        @Volatile var resent = 0L
            private set

        init {
            runCatching {
                socket.receiveBufferSize = 1 shl 20
                socket.sendBufferSize = 1 shl 20
            }
            Thread({ readLoop(early) }, "xarvis-direct-read").apply { isDaemon = true }.start()
            Thread({ sendLoop() }, "xarvis-direct-send").apply { isDaemon = true }.start()
        }

        val isClosed: Boolean get() = closed

        /** One line at a time: a line waiting for queue space keeps its turn (another thread's line can't slip in). */
        private val writer = Object()

        fun sendLine(text: String) {
            val bytes = (text + "\n").toByteArray(Charsets.UTF_8)
            synchronized(writer) { queueLine(bytes) }
        }

        private fun queueLine(bytes: ByteArray) {
            synchronized(lock) {
                var off = 0
                while (off < bytes.size) {
                    while (queuedBytes >= MAX_QUEUED_BYTES && !closed && !closing) lock.wait(100)
                    if (closed || closing) throw IOException(failure ?: "connection closed")
                    val n = minOf(MAX_DATA, bytes.size - off)
                    queue.addLast(Out(nextSeq++, bytes.copyOfRange(off, off + n)))
                    queuedBytes += n
                    off += n
                }
                lock.notifyAll()
            }
        }

        fun receiveLine(): String {
            val x = inbox.poll(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
                ?: throw SocketTimeoutException("no answer over XARVIS Direct")
            if (x === END) {
                inbox.put(END)
                throw IOException(failure ?: "connection closed")
            }
            return x as String
        }

        /** Ends the conversation once everything sent has arrived (or after a few seconds), then tells the other side. */
        override fun close() {
            synchronized(lock) {
                if (closed || closing) return
                closing = true
                closingSinceNs = System.nanoTime()
                lock.notifyAll()
            }
        }

        // ---- the reader ----

        private fun readLoop(early: List<Pair<ByteArray, SocketAddress>>) {
            early.forEach { (b, from) -> handle(b, b.size, from) }
            val buf = ByteArray(2048)
            val pkt = DatagramPacket(buf, buf.size)
            runCatching { socket.soTimeout = 250 }
            while (!closed) {
                try {
                    pkt.setData(buf)
                    socket.receive(pkt)
                } catch (e: SocketTimeoutException) {
                    checkQuiet()
                    continue
                } catch (e: Exception) {
                    if (!closed) fail("the direct line broke (${e.message ?: e.javaClass.simpleName})")
                    break
                }
                if (Packets.ours(buf, pkt.length, tok)) handle(buf, pkt.length, pkt.socketAddress)
                checkQuiet()
            }
        }

        private fun handle(b: ByteArray, len: Int, from: SocketAddress) {
            lastHeardNs = System.nanoTime()
            if (from != remote) remote = from // its network moved it (Wi-Fi ↔ data, NAT rebinding): follow
            when (b[TOKEN_BYTES].toInt()) {
                DATA -> onData(b, len)
                ACK -> onAck(b, len)
                PROBE -> send(Packets.probeAck(tok, Packets.int(b, TOKEN_BYTES + 1))) // a late probe: answer it
                CLOSE -> {
                    failure = failure ?: "connection closed"
                    finish()
                }
            }
        }

        private fun checkQuiet() {
            if (!closed && System.nanoTime() - lastHeardNs > QUIET_NS) fail("the direct line went quiet")
        }

        private fun onData(b: ByteArray, len: Int) {
            if (len < DATA_HEADER) return
            val seq = Packets.int(b, TOKEN_BYTES + 1)
            if (seq >= recvNext && seq < recvNext + MAX_REORDER) {
                if (seq == recvNext) {
                    deliver(b, DATA_HEADER, len - DATA_HEADER)
                    recvNext++
                    while (true) {
                        val next = ooo.remove(recvNext) ?: break
                        deliver(next, 0, next.size)
                        recvNext++
                    }
                } else if (!ooo.containsKey(seq)) {
                    ooo[seq] = b.copyOfRange(DATA_HEADER, len)
                }
            }
            send(Packets.ack(tok, recvNext, ooo))
        }

        /** The byte stream, split back into lines. */
        private fun deliver(b: ByteArray, off: Int, len: Int) {
            var start = off
            val end = off + len
            for (i in off until end) {
                if (b[i] == NEWLINE) {
                    line.write(b, start, i - start)
                    inbox.put(String(line.toByteArray(), Charsets.UTF_8))
                    line.reset()
                    start = i + 1
                }
            }
            if (start < end) line.write(b, start, end - start)
        }

        private fun onAck(b: ByteArray, len: Int) {
            if (len < ACK_SIZE) return
            val cum = Packets.int(b, TOKEN_BYTES + 1)
            val now = System.nanoTime()
            synchronized(lock) {
                var acked = 0
                var sample = -1L
                var sampleSeq = -1
                fun take(o: Out) {
                    acked++
                    lost.remove(o.seq)
                    if (o.sentNs > ackedSentNs) ackedSentNs = o.sentNs
                    if (o.sends == 1 && o.seq > sampleSeq) {
                        sampleSeq = o.seq
                        sample = now - o.firstSentNs
                    }
                }
                while (flight.isNotEmpty() && flight.firstKey() < cum) take(flight.pollFirstEntry()!!.value)
                if (cum > peerCum) peerCum = cum
                for (i in 0 until WINDOW) {
                    if (b[TOKEN_BYTES + 5 + i / 8].toInt() and (1 shl (i % 8)) == 0) continue
                    flight.remove(cum + 1 + i)?.let { take(it) }
                }
                if (sample > 0) {
                    if (sample < minRttNs) minRttNs = sample
                    val err = Math.abs(srttNs - sample)
                    rttvarNs = (3 * rttvarNs + err) / 4
                    srttNs = (7 * srttNs + sample) / 8
                    rtoNs = rto()
                }
                repeat(acked) { cwnd += if (cwnd < ssthresh) 1.0 else GROWTH / cwnd }
                if (cwnd > MAX_CWND) cwnd = MAX_CWND
                if (acked > 0) lock.notifyAll()
            }
        }

        private fun rto() = (srttNs + 4 * rttvarNs).coerceIn(MIN_RTO_NS, MAX_RTO_NS)

        // ---- the sender ----

        private fun sendLoop() {
            while (true) {
                synchronized(lock) {
                    if (closed) return
                    val now = System.nanoTime()
                    findLost(now)
                    var sentAny = false
                    while (flight.size - lost.size < cwnd.toInt() && now >= nextSendNs) {
                        val o = lost.pollFirst()?.let { flight[it] } ?: queue.firstOrNull()?.takeIf { it.seq < peerCum + WINDOW }?.also {
                            queue.removeFirst()
                            queuedBytes -= it.data.size
                            flight[it.seq] = it
                            it.firstSentNs = now
                        } ?: break
                        if (o.sends > 0) resent++
                        o.sends++
                        o.sentNs = now
                        send(Packets.data(tok, o.seq, o.data))
                        sentAny = true
                        val gap = (srttNs / (cwnd * PACE_GAIN)).toLong()
                        nextSendNs = maxOf(nextSendNs, now - BURST * gap) + gap
                    }
                    if (sentAny) lock.notifyAll() // room in the queue for a waiting sendLine
                    if (now - lastSendNs > KEEPALIVE_NS) send(Packets.ping(tok))
                    if (closing && (queue.isEmpty() && flight.isEmpty() || now - closingSinceNs > LINGER_NS)) {
                        repeat(2) { send(Packets.close(tok)) }
                        finish()
                        return
                    }
                    // Sleep until the pacing allows the next packet, or something to check (acks, re-sends) is due.
                    val busy = queue.isNotEmpty() || lost.isNotEmpty()
                    val waitMs = when {
                        busy && flight.size - lost.size < cwnd.toInt() -> ((nextSendNs - now) / 1_000_000).coerceIn(1, 20)
                        flight.isNotEmpty() -> 5L
                        else -> 200L
                    }
                    lock.wait(waitMs)
                }
            }
        }

        /**
         * Marks packets lost: one sent clearly later (a quarter round trip, for packets that merely overtook each
         * other) has already arrived, or there's been no ack for too long (waiting longer after each re-send).
         */
        private fun findLost(now: Long) {
            val reorder = maxOf(srttNs / 4, MIN_RTT_NS)
            for (o in flight.values) {
                if (o.seq in lost) continue
                val byOrder = ackedSentNs - o.sentNs > reorder
                val byTime = now - o.sentNs > rtoNs shl minOf(o.sends - 1, 4)
                if (!byOrder && !byTime) continue
                lost += o.seq
                if (o.seq >= recoverFrom) {
                    // Once per round trip, slow down: a lost packet is how a full link shows itself. But mobile links
                    // also lose packets at random; when the round trip hasn't grown (nothing queuing up), it was
                    // probably one of those, so ease off only a little.
                    val queued = srttNs - minRttNs > maxOf(minRttNs / 4, 5_000_000L)
                    cwnd = maxOf(MIN_CWND, cwnd * if (queued) BETA else BETA_RANDOM)
                    ssthresh = cwnd
                    recoverFrom = flight.lastKey() + 1 // what was already on its way when this loss showed
                }
            }
        }

        private fun send(packet: ByteArray) {
            try {
                socket.send(DatagramPacket(packet, packet.size, remote))
                packetsSent++
                lastSendNs = System.nanoTime()
            } catch (e: Exception) {
                // A send can fail for a moment while the phone switches networks; the re-sends cover it.
            }
        }

        private fun fail(why: String) {
            if (failure == null) failure = why
            finish()
        }

        private fun finish() {
            synchronized(lock) {
                if (closed) return
                closed = true
                lock.notifyAll()
            }
            inbox.put(END)
            runCatching { socket.close() }
        }

        private companion object {
            val END = Any()
            const val NEWLINE = '\n'.code.toByte()
        }
    }

    /** STUN (RFC 5389) binding requests and the address in the answer. */
    object Stun {
        private const val COOKIE = 0x2112A442

        fun request(tx: ByteArray): ByteArray {
            val b = ByteArray(20)
            b[1] = 0x01 // binding request, no attributes
            Packets.putInt(b, 4, COOKIE)
            System.arraycopy(tx, 0, b, 8, 12)
            return b
        }

        /** The public address in a binding answer for transaction [tx] (XOR-MAPPED-ADDRESS, else MAPPED-ADDRESS). */
        fun mapped(b: ByteArray, len: Int, tx: ByteArray): InetSocketAddress? {
            if (len < 20 || u16(b, 0) != 0x0101 || Packets.int(b, 4) != COOKIE) return null
            for (i in 0 until 12) if (b[8 + i] != tx[i]) return null
            val end = minOf(len, 20 + u16(b, 2))
            var p = 20
            var plain: InetSocketAddress? = null
            while (p + 4 <= end) {
                val type = u16(b, p)
                val size = u16(b, p + 2)
                val v = p + 4
                if (v + size > end) break
                if ((type == 0x0020 || type == 0x0001) && size >= 8) {
                    val ipLen = when (b[v + 1].toInt()) { 1 -> 4; 2 -> 16; else -> 0 }
                    if (ipLen > 0 && size >= 4 + ipLen) {
                        val ip = b.copyOfRange(v + 4, v + 4 + ipLen)
                        var port = u16(b, v + 2)
                        if (type == 0x0020) {
                            port = port xor (COOKIE ushr 16)
                            val key = ByteArray(16)
                            Packets.putInt(key, 0, COOKIE)
                            System.arraycopy(tx, 0, key, 4, 12)
                            for (k in ip.indices) ip[k] = (ip[k].toInt() xor key[k].toInt()).toByte()
                            return InetSocketAddress(InetAddress.getByAddress(ip), port)
                        }
                        plain = InetSocketAddress(InetAddress.getByAddress(ip), port)
                    }
                }
                p = v + ((size + 3) and 3.inv())
            }
            return plain
        }

        private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
    }

    /** The packets: [token 8][type 1][...]. DATA: seq + bytes. ACK: next expected seq + a 256-bit map of later ones. */
    internal object Packets {
        fun ours(b: ByteArray, len: Int, tok: ByteArray): Boolean {
            if (len < TOKEN_BYTES + 1) return false
            for (i in 0 until TOKEN_BYTES) if (b[i] != tok[i]) return false
            return true
        }

        private fun head(tok: ByteArray, type: Int, size: Int): ByteArray {
            val b = ByteArray(size)
            System.arraycopy(tok, 0, b, 0, TOKEN_BYTES)
            b[TOKEN_BYTES] = type.toByte()
            return b
        }

        fun probe(tok: ByteArray, round: Int) = head(tok, PROBE, TOKEN_BYTES + 5).also { putInt(it, TOKEN_BYTES + 1, round) }
        fun probeAck(tok: ByteArray, round: Int) = head(tok, PROBE_ACK, TOKEN_BYTES + 5).also { putInt(it, TOKEN_BYTES + 1, round) }
        fun ping(tok: ByteArray) = head(tok, PING, TOKEN_BYTES + 1)
        fun close(tok: ByteArray) = head(tok, CLOSE, TOKEN_BYTES + 1)

        fun data(tok: ByteArray, seq: Int, data: ByteArray): ByteArray {
            val b = head(tok, DATA, DATA_HEADER + data.size)
            putInt(b, TOKEN_BYTES + 1, seq)
            System.arraycopy(data, 0, b, DATA_HEADER, data.size)
            return b
        }

        fun ack(tok: ByteArray, next: Int, waiting: TreeMap<Int, ByteArray>): ByteArray {
            val b = head(tok, ACK, ACK_SIZE)
            putInt(b, TOKEN_BYTES + 1, next)
            for (s in waiting.subMap(next + 1, next + 1 + WINDOW).keys) {
                val i = s - next - 1
                val at = TOKEN_BYTES + 5 + i / 8
                b[at] = (b[at].toInt() or (1 shl (i % 8))).toByte()
            }
            return b
        }

        fun putInt(b: ByteArray, at: Int, v: Int) {
            b[at] = (v ushr 24).toByte(); b[at + 1] = (v ushr 16).toByte(); b[at + 2] = (v ushr 8).toByte(); b[at + 3] = v.toByte()
        }

        fun int(b: ByteArray, at: Int): Int =
            ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
                ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
    }

    companion object {
        /** Free public STUN servers (they only echo back the address they see). */
        val STUN_SERVERS = listOf("stun.l.google.com" to 19302, "stun1.l.google.com" to 19302, "stun.cloudflare.com" to 3478)
        private const val STUN_WAIT_MS = 900L

        internal const val TOKEN_BYTES = 8
        internal const val DATA = 1
        internal const val ACK = 2
        internal const val PROBE = 3
        internal const val PROBE_ACK = 4
        internal const val PING = 5
        internal const val CLOSE = 6
        internal const val DATA_HEADER = TOKEN_BYTES + 5
        internal const val WINDOW = 256
        internal const val ACK_SIZE = TOKEN_BYTES + 5 + WINDOW / 8
        /** Bytes per packet: under 1,280 (IPv6's smallest allowed) with the IP and UDP headers, so nothing splits. */
        internal const val MAX_DATA = 1_180
        private const val MAX_CANDIDATES = 10
        private const val PROBE_EVERY_MS = 150L
        private const val DEFAULT_RTT_MS = 150L
        private const val MAX_REORDER = 4_096
        /** At most this much waits to be sent (more makes live video lag; [sendLine] waits instead). */
        private const val MAX_QUEUED_BYTES = 96 * 1024
        private const val INITIAL_CWND = 16.0
        private const val MIN_CWND = 4.0
        private const val MAX_CWND = WINDOW.toDouble()
        private const val BETA = 0.7
        private const val BETA_RANDOM = 0.9
        /** Packets the window grows by each round trip once past slow start. */
        private const val GROWTH = 2.0
        private const val PACE_GAIN = 1.25
        private const val BURST = 6
        private const val MIN_RTT_NS = 2_000_000L
        private const val MIN_RTO_NS = 150_000_000L
        private const val MAX_RTO_NS = 3_000_000_000L
        private const val KEEPALIVE_NS = 3_000_000_000L
        private const val QUIET_NS = 15_000_000_000L
        private const val LINGER_NS = 5_000_000_000L

        /** A UDP socket for one Direct line: bound to "::", which takes IPv6 and IPv4 alike (else IPv4 only). */
        fun open(): Direct = Direct(
            runCatching { DatagramSocket(InetSocketAddress(InetAddress.getByName("::"), 0)) }.getOrElse { DatagramSocket(InetSocketAddress(0)) },
        )

        /**
         * This phone's own addresses worth offering: public IPv6 (2000::/3) and private IPv4 (same Wi-Fi), on
         * real interfaces — not VPN tunnels (Tailscale, Proton) and not loopback.
         */
        fun localAddresses(): List<InetAddress> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) && !it.name.startsWith("tun") }
                .flatMap { it.inetAddresses.toList() }
                .filter { a ->
                    when (a) {
                        is Inet6Address -> (a.address[0].toInt() and 0xE0) == 0x20 // global unicast (not fe80::, not fd…)
                        is Inet4Address -> a.isSiteLocalAddress // 10.x, 172.16–31.x, 192.168.x
                        else -> false
                    }
                }
                .map { if (it is Inet6Address) InetAddress.getByAddress(it.address) else it } // drop the %scope
                .distinct()
        }.getOrDefault(emptyList())

        fun format(a: InetSocketAddress): String {
            val ip = a.address
            return if (ip is Inet6Address) "[${InetAddress.getByAddress(ip.address).hostAddress}]:${a.port}" else "${ip.hostAddress}:${a.port}"
        }

        /** "1.2.3.4:5678" or "[2001:db8::1]:5678" → an address; only IP literals (never a name to look up). */
        fun parse(text: String): InetSocketAddress? = runCatching {
            val t = text.trim()
            val (host, port) = if (t.startsWith("[")) {
                t.substring(1, t.indexOf(']')) to t.substringAfterLast("]:").toInt()
            } else {
                t.substringBeforeLast(':') to t.substringAfterLast(':').toInt()
            }
            if (port !in 1..65_535) return null
            val literal = if (':' in host) host.all { it.isLetterOrDigit() || it == ':' || it == '.' }
            else host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
            if (!literal || host.isEmpty()) return null
            InetSocketAddress(InetAddress.getByName(host), port)
        }.getOrNull()
    }
}
