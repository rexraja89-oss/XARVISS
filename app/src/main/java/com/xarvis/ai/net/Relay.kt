package com.xarvis.ai.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * XARVIS's relay (Rex, Nov 2026: "always connected, without Tailscale"): every XARVIS keeps one outgoing
 * connection to Rex's own free MQTT broker (HiveMQ Cloud) and gets a private mailbox there. A linked phone
 * reaches it anywhere — mobile data, another country — by posting to that mailbox. It's not a VPN (no key
 * icon, banking apps don't object, Proton VPN can stay on), and what passes through is the link's own
 * AES-GCM frames, sealed with the pairing key, so the broker sees only scrambled data.
 *
 * Lines are wrapped in a small envelope (sender, connection id, kind o/d/x for open/data/close) and split
 * when bigger than the broker allows. Presence: each phone keeps a retained "1" on "<mailbox>/p", and its
 * last will turns it to "0" when it vanishes, so a linked phone knows at once whether it's online.
 */
class Relay(context: Context, private val deviceId: String) {

    data class Config(val host: String, val port: Int, val user: String, val pass: String)

    enum class State { OFF, CONNECTING, CONNECTED, ERROR }
    data class Status(val state: State, val detail: String = "")

    /** A line for DeviceLink: from [from] on connection [cid]; [kind] "o" open, "d" data, "x" closed. */
    fun interface Receiver { fun onLine(from: String, cid: String, kind: String, line: String) }

    private val app = context.applicationContext
    private val keys = com.xarvis.ai.llm.BrainKeys(app)
    private val prefs = app.getSharedPreferences("relay", Context.MODE_PRIVATE)
    private val _status = MutableStateFlow(Status(State.OFF))
    val status: StateFlow<Status> = _status

    @Volatile private var mqtt: Mqtt? = null
    @Volatile private var receiver: Receiver? = null
    @Volatile private var loop: Job? = null
    @Volatile private var kick: (() -> Unit)? = null
    private val peers = ConcurrentHashMap.newKeySet<String>()
    private val presence = ConcurrentHashMap<String, Boolean>()
    private val seen = object : LinkedHashMap<String, Boolean>(600, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 500
    }
    private val parts = ConcurrentHashMap<String, Array<String?>>()
    private var pendingResend: Collection<ByteArray> = emptyList()

    val myMailbox = mailbox(deviceId)

    // ---- settings ----

    fun config(): Config? {
        val host = keys.get(HOST)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val user = keys.get(USER) ?: return null
        val pass = keys.get(PASS) ?: return null
        val (h, p) = splitHost(host)
        return Config(h, p, user, pass)
    }

    /** Saves (or with null, removes) the relay login and reconnects. */
    fun save(cfg: Config?) {
        if (cfg == null) {
            keys.clear(HOST); keys.clear(USER); keys.clear(PASS)
        } else {
            keys.save(HOST, "${cfg.host}:${cfg.port}"); keys.save(USER, cfg.user); keys.save(PASS, cfg.pass)
        }
        restart()
    }

    val connected: Boolean get() = _status.value.state == State.CONNECTED && mqtt?.closed == false

    /** Whether [peerId]'s XARVIS is on the relay right now (null: not known yet). */
    fun online(peerId: String): Boolean? = presence[mailbox(peerId)]

    /** The linked phones whose presence to watch. */
    fun setPeers(ids: Collection<String>) {
        val added = ids.filter { peers.add(it) }
        peers.retainAll(ids.toSet())
        if (added.isNotEmpty()) runCatching { mqtt?.subscribe(added.map { mailbox(it) + "/p" }) }
    }

    // ---- running ----

    fun start(scope: CoroutineScope, receiver: Receiver) {
        this.receiver = receiver
        watchNetwork()
        if (loop?.isActive == true) return
        loop = scope.launch { run() }
    }

    /** Drops the connection so it's made again right away (new settings, or the network changed). */
    fun restart() {
        runCatching { mqtt?.close() }
        kick?.invoke()
    }

    private suspend fun run() {
        var backoff = 2_000L
        while (kotlin.coroutines.coroutineContext.isActive) {
            val cfg = config()
            if (cfg == null) {
                _status.value = Status(State.OFF, "not set up")
                waitForKick(60_000)
                continue
            }
            _status.value = Status(State.CONNECTING, cfg.host)
            val closedSignal = kotlinx.coroutines.CompletableDeferred<String>()
            var why = "no answer"
            val m = Mqtt(
                cfg.host, cfg.port, "xv-" + myMailbox.removePrefix("xv/"), cfg.user, cfg.pass,
                will = Triple("$myMailbox/p", "0".toByteArray(), true),
            )
            try {
                m.connect(object : Mqtt.Listener {
                    override fun onMessage(topic: String, payload: ByteArray) = handle(topic, payload)
                    override fun onClosed(why: String) { closedSignal.complete(why) }
                })
                mqtt = m
                m.subscribe(listOf(myMailbox) + peers.map { mailbox(it) + "/p" })
                m.publish("$myMailbox/p", "1".toByteArray(), qos = 1, retain = true)
                if (pendingResend.isNotEmpty()) { m.resend(pendingResend); pendingResend = emptyList() }
                _status.value = Status(State.CONNECTED, cfg.host)
                backoff = 2_000L
                // Keep it alive: a ping when quiet; a dead line shows as no answer. Ends as soon as the line closes.
                while (true) {
                    val ended = kotlinx.coroutines.withTimeoutOrNull(PING_EVERY_MS) { closedSignal.await() }
                    if (ended != null) { why = ended; break }
                    if (m.closed) break
                    if (System.currentTimeMillis() - m.lastHeard > PING_EVERY_MS * 3) { m.close(); break }
                    runCatching { m.ping() }.onFailure { m.close() }
                    count(m)
                }
                Log.i(TAG, "Relay connection ended: $why")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) { m.close(); throw e }
                Log.w(TAG, "Relay: ${e.message}")
                _status.value = Status(State.ERROR, e.message ?: e.javaClass.simpleName)
            } finally {
                count(m)
                pendingResend = m.unacked.values.toList()
                if (mqtt === m) mqtt = null
                m.close()
            }
            if (_status.value.state == State.CONNECTED) _status.value = Status(State.CONNECTING, "reconnecting")
            waitForKick(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    private suspend fun waitForKick(ms: Long) {
        val woken = kotlinx.coroutines.CompletableDeferred<Unit>()
        kick = { woken.complete(Unit) }
        kotlinx.coroutines.withTimeoutOrNull(ms) { woken.await() }
        kick = null
    }

    private var callback: ConnectivityManager.NetworkCallback? = null

    /** A new network (Wi-Fi ↔ mobile data, data back after running out): reconnect at once. */
    private fun watchNetwork() {
        if (callback != null) return
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (_status.value.state != State.CONNECTED) kick?.invoke() else restart()
            }
        }
        callback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }
    }

    fun stop() {
        loop?.cancel()
        runCatching { mqtt?.publish("$myMailbox/p", "0".toByteArray(), qos = 1, retain = true) }
        runCatching { mqtt?.disconnect() }
        runCatching { callback?.let { app.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } }
        callback = null
    }

    // ---- messages ----

    /** Sends one line to [toDevice] on connection [cid]; split into pieces when it's too big. Throws when offline. */
    fun sendLine(toDevice: String, cid: String, kind: String, line: String) {
        val m = mqtt?.takeIf { !it.closed } ?: throw LinkException("the relay isn't connected")
        val topic = mailbox(toDevice)
        Envelope.split(deviceId, cid, kind, line).forEach { m.publish(topic, it.toByteArray(Charsets.UTF_8), qos = 1) }
    }

    private fun handle(topic: String, payload: ByteArray) {
        if (topic.endsWith("/p")) {
            presence[topic.removeSuffix("/p")] = payload.decodeToString() == "1"
            return
        }
        if (topic != myMailbox) return
        val env = runCatching { JSONObject(payload.decodeToString()) }.getOrNull() ?: return
        val mid = env.optString("m")
        synchronized(seen) {
            if (mid.isNotEmpty() && seen.put(mid, true) != null) return // a re-delivery
        }
        val line = Envelope.assemble(env, parts) ?: return
        receiver?.onLine(env.optString("f"), env.optString("c"), env.optString("k"), line)
    }

    // ---- data used ----

    private fun month() = SimpleDateFormat("yyyyMM", Locale.US).format(Date())

    /** Relay data used this month by this phone, in bytes. */
    fun usedThisMonth(): Long = if (prefs.getString("month", "") == month()) prefs.getLong("bytes", 0) else 0

    private var counted = 0L
    private var countedConn: Mqtt? = null

    /** Adds what [m] has carried since the last count to this month's total. */
    private fun count(m: Mqtt) {
        val add = synchronized(this) {
            if (countedConn !== m) { countedConn = m; counted = 0 }
            val total = m.bytesIn.get() + m.bytesOut.get()
            (total - counted).also { counted = total }
        }
        if (add <= 0) return
        val used = usedThisMonth() + add
        prefs.edit().putString("month", month()).putLong("bytes", used).apply()
        if (used > FREE_BYTES_MONTH * 8 / 10 && prefs.getString("warned", "") != month()) {
            prefs.edit().putString("warned", month()).apply()
            onWarning?.invoke("The relay has used ${used / (1024 * 1024)} MB of its free 10 GB this month (this phone). Remote control away from home uses the most.")
        }
    }

    /** Told once a month when this phone passes 80% of the free allowance. */
    @Volatile var onWarning: ((String) -> Unit)? = null

    /** Splits big lines and puts them back together (the broker takes at most 5 MB per message). */
    object Envelope {
        const val PART = 700_000

        fun split(from: String, cid: String, kind: String, line: String): List<String> {
            val pieces = if (line.length <= PART) listOf(line) else line.chunked(PART)
            val q = UUID.randomUUID().toString().take(8)
            return pieces.mapIndexed { i, piece ->
                val o = JSONObject().put("m", UUID.randomUUID().toString().take(12)).put("f", from).put("c", cid).put("k", kind).put("l", piece)
                if (pieces.size > 1) o.put("q", q).put("i", i).put("n", pieces.size)
                o.toString()
            }
        }

        /** The whole line once every piece of it has arrived; null while pieces are missing. */
        fun assemble(env: JSONObject, parts: MutableMap<String, Array<String?>>): String? {
            val n = env.optInt("n", 1)
            if (n <= 1) return env.optString("l")
            val key = env.optString("f") + "|" + env.optString("q")
            val arr = parts.getOrPut(key) { arrayOfNulls(n) }
            val i = env.optInt("i")
            if (i !in arr.indices) return null
            arr[i] = env.optString("l")
            if (arr.any { it == null }) return null
            parts.remove(key)
            return arr.joinToString("") { it.orEmpty() }
        }
    }

    companion object {
        private const val TAG = "XarvisRelay"
        private const val HOST = "relay_host"
        private const val USER = "relay_user"
        private const val PASS = "relay_pass"
        private const val PING_EVERY_MS = 45_000L
        /** HiveMQ Cloud's free plan: 10 GB a month for all of Rex's devices. */
        const val FREE_BYTES_MONTH = 10L * 1024 * 1024 * 1024

        /** A device's mailbox: a hash of its id, so nothing about Rex shows in the broker. */
        fun mailbox(deviceId: String): String {
            val h = MessageDigest.getInstance("SHA-256").digest("xarvis-relay|$deviceId".toByteArray())
            return "xv/" + h.take(12).joinToString("") { "%02x".format(it) }
        }

        /** "abc.s1.eu.hivemq.cloud", "abc.hivemq.cloud:8883", "mqtts://abc…:8883" → host and port. */
        fun splitHost(text: String): Pair<String, Int> {
            val t = text.trim().substringAfter("://").trimEnd('/')
            val host = t.substringBefore(':').substringBefore('/')
            val port = t.substringAfter(':', "").substringBefore('/').toIntOrNull() ?: 8883
            return host to port
        }
    }
}
