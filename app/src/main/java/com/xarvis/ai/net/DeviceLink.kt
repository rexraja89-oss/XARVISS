package com.xarvis.ai.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class Peer(val id: String, val name: String, val key: ByteArray, var host: String?, var port: Int)

class LinkException(message: String) : Exception(message)

/**
 * Links XARVIS installs on the same network.
 *
 * Devices find each other with mDNS (`_xarvis._tcp`) and talk over TCP with one JSON
 * frame per line. Pairing is an ECDH exchange where the initiator commits to its key
 * first; both sides then derive a 6-digit code from the transcript, the responder
 * shows it and the user types it on the initiator, which defeats a man in the middle.
 * After pairing, every request and response is AES-256-GCM encrypted with the shared key.
 */
class DeviceLink(context: Context, private val handler: Handler) {

    interface Handler {
        /** Device status to report to a linked device. */
        suspend fun status(): String

        /** Runs a linked device's free-form message through this device's LLM; null if it has none. */
        suspend fun brainChat(peerId: String, facts: List<String>, devices: List<String>, text: String): String?

        fun llmReady(): Boolean
        fun onNote(from: String, text: String)
        fun onPeersChanged()

        /** Shared memory: this device's remembered facts, and facts or wipes pushed by a linked device. */
        suspend fun memorySnapshot(): JSONObject
        suspend fun memoryAdd(content: String, timestamp: Long)
        suspend fun memoryClear(timestamp: Long)

        /** This phone's contacts matching [name], for a linked phone's "find contact". */
        suspend fun findContacts(name: String): List<String>
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("link", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = SecureRandom()

    val deviceId: String = prefs.getString("id", null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString("id", it).apply() }
    val deviceName: String = Settings.Global.getString(appContext.contentResolver, "device_name")
        ?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val events: SharedFlow<String> = _events

    private val peers = ConcurrentHashMap<String, Peer>().apply { loadPeers().forEach { put(it.id, it) } }
    private val discovered = ConcurrentHashMap<String, Discovered>()
    private val pendingIncoming = ConcurrentHashMap<String, Pending>()
    @Volatile private var pendingOutgoing: Pending? = null

    @Volatile private var server: ServerSocket? = null
    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var reconnectJob: Job? = null

    private val myPort: Int get() = server?.localPort ?: PORT

    private class Discovered(val id: String, val name: String, val host: String, val port: Int)

    private class Pending(
        val peerId: String, val name: String, val key: ByteArray, val code: String,
        val host: String?, val port: Int, val created: Long = System.currentTimeMillis(),
    ) {
        val expired: Boolean get() = System.currentTimeMillis() - created > PAIRING_TIMEOUT_MS
    }

    private class Conn(private val socket: Socket) : AutoCloseable {
        private val reader = socket.getInputStream().bufferedReader()
        private val writer = socket.getOutputStream().bufferedWriter()
        val remoteHost: String? get() = socket.inetAddress?.hostAddress
        val remoteIsLoopback: Boolean get() = socket.inetAddress?.isLoopbackAddress == true

        fun send(frame: JSONObject) {
            writer.write(frame.toString()); writer.newLine(); writer.flush()
        }

        fun receive(): JSONObject = JSONObject(reader.readLine() ?: throw LinkException("connection closed"))
        override fun close() = socket.close()
    }

    fun pairedPeers(): List<Peer> = peers.values.sortedBy { it.name }

    fun start() {
        scope.launch { runServer() }
    }

    fun stop() {
        runCatching { networkCallback?.let { appContext.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } }
        stopNsd()
        runCatching { server?.close() }
        scope.cancel()
    }

    // ---- Client side --------------------------------------------------------------------

    /** Step 1 of pairing, run on the device the user is typing on. */
    suspend fun startPairing(target: String): String = withContext(Dispatchers.IO) {
        val (host, port) = resolveTarget(target)
            ?: return@withContext "I can't find \"$target\" on the network. Both devices need XARVIS open " +
                "and the same Wi-Fi. You can also use its address, e.g. \"pair with 192.168.1.20\"."
        val keys = newKeyPair()
        val myPub = keys.public.encoded
        Conn(connect(host, port, PAIRING_READ_TIMEOUT_MS)).use { c ->
            c.send(JSONObject().put("t", "pair1").put("id", deviceId).put("name", deviceName)
                .put("commit", b64(sha256(myPub))))
            val r1 = c.receive()
            if (r1.optString("t") != "pair1ok") throw LinkException(r1.optString("msg", "pairing refused"))
            val theirPub = unb64(r1.getString("pub"))
            c.send(JSONObject().put("t", "pair2").put("pub", b64(myPub)).put("port", myPort))
            val r2 = c.receive()
            if (r2.optString("t") != "pair2ok") throw LinkException(r2.optString("msg", "pairing refused"))
            val shared = agree(keys.private, theirPub)
            val name = r1.getString("name")
            pendingOutgoing = Pending(r1.getString("id"), name, deriveKey(shared),
                pairingCode(myPub, theirPub, shared), host, port)
            "$name is now showing a 6-digit pairing code. Type \"code\" and the number here, " +
                "e.g. \"code 123456\"."
        }
    }

    /** Step 2 of pairing: the user typed the code shown on the other device. */
    suspend fun finishPairing(code: String): String {
        val p = pendingOutgoing ?: return "There's no pairing in progress. Start one with \"pair with <device>\"."
        pendingOutgoing = null
        if (p.expired) return "That pairing request expired. Start again with \"pair with ${p.name}\"."
        if (code != p.code) {
            return "That code doesn't match, so I cancelled the pairing for safety. " +
                "Start again with \"pair with ${p.name}\"."
        }
        val peer = Peer(p.peerId, p.name, p.key, p.host, p.port)
        request(peer, "confirm")
        peers[peer.id] = peer
        savePeers()
        handler.onPeersChanged()
        return "Linked with ${p.name}. Your memories are now shared between the two phones."
    }

    /** Whether this phone is waiting for the user to type another phone's pairing code. */
    val pairingInProgress: Boolean get() = pendingOutgoing?.expired == false

    fun findPeer(query: String): Peer? {
        val words = normalize(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        return peers.values.firstOrNull { p -> words.all { p.name.lowercase().contains(it) } }
    }

    suspend fun remoteStatus(peer: Peer): String = request(peer, "status").getString("text")

    suspend fun sendNote(peer: Peer, text: String) {
        request(peer, "note", JSONObject().put("text", text))
    }

    suspend fun remoteChat(peer: Peer, facts: List<String>, devices: List<String>, text: String): String =
        request(
            peer, "chat",
            JSONObject().put("text", text).put("facts", JSONArray(facts)).put("devices", JSONArray(devices)),
            timeoutMs = CHAT_READ_TIMEOUT_MS,
        ).getString("text")

    /** [peer]'s contacts matching [name] (a contact saved only on that phone). */
    suspend fun remoteContacts(peer: Peer, name: String): List<String> =
        request(peer, "contacts", JSONObject().put("name", name), timeoutMs = CONTACTS_READ_TIMEOUT_MS)
            .optJSONArray("lines").toStrings()

    suspend fun pullMemory(peer: Peer): JSONObject = request(peer, "memory_get").getJSONObject("memory")

    suspend fun pushFact(peer: Peer, content: String, timestamp: Long) {
        request(peer, "memory_add", JSONObject().put("c", content).put("t", timestamp))
    }

    suspend fun pushClear(peer: Peer, timestamp: Long) {
        request(peer, "memory_clear", JSONObject().put("t", timestamp))
    }

    /** A linked, reachable device with a working LLM, if any. */
    suspend fun findBrain(): Peer? = pairedPeers().firstOrNull { p ->
        runCatching { request(p, "ping", timeoutMs = PING_TIMEOUT_MS).optBoolean("llm") }.getOrDefault(false)
    }

    fun unpair(peer: Peer) {
        peers.remove(peer.id)
        savePeers()
        handler.onPeersChanged()
    }

    fun setAddress(peer: Peer, host: String, port: Int) {
        peer.host = host
        peer.port = port
        savePeers()
    }

    suspend fun describeDevices(): String {
        val lines = mutableListOf("This device: $deviceName")
        if (peers.isEmpty()) lines += "No linked devices yet. With both on the same Wi-Fi, type \"pair with <device>\"."
        for (p in pairedPeers()) {
            val ping = runCatching { request(p, "ping", timeoutMs = PING_TIMEOUT_MS) }.getOrNull()
            lines += "  ${p.name}: " + when {
                ping == null -> "offline"
                ping.optBoolean("llm") -> "online, AI model ready"
                else -> "online"
            }
        }
        val nearby = discovered.values.filter { !peers.containsKey(it.id) }
        if (nearby.isNotEmpty()) lines += "Nearby, not linked: " + nearby.joinToString { it.name }
        return lines.joinToString("\n")
    }

    private suspend fun request(
        peer: Peer, op: String, body: JSONObject = JSONObject(), timeoutMs: Int = REQUEST_READ_TIMEOUT_MS,
    ): JSONObject = withContext(Dispatchers.IO) {
        val host = peer.host ?: throw LinkException(
            "${peer.name}'s address is unknown. Make sure both devices are on the same Wi-Fi with XARVIS open."
        )
        body.put("op", op).put("ts", System.currentTimeMillis()).put("port", myPort)
        Conn(connect(host, peer.port, timeoutMs)).use { c ->
            c.send(encryptFrame("req", peer.key, body))
            val res = c.receive()
            if (res.optString("t") == "err") throw LinkException(res.optString("msg"))
            JSONObject(String(decrypt(peer.key, res, peer.id), Charsets.UTF_8))
        }
    }

    private fun connect(host: String, port: Int, readTimeoutMs: Int) = Socket().apply {
        connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        soTimeout = readTimeoutMs
    }

    private fun resolveTarget(target: String): Pair<String, Int>? {
        ADDRESS.matchEntire(target.trim())?.let { m ->
            return m.groupValues[1] to (m.groupValues[2].toIntOrNull() ?: PORT)
        }
        val words = normalize(target).split(' ').filter { it.isNotBlank() }
        return discovered.values.firstOrNull { d -> words.all { d.name.lowercase().contains(it) } }
            ?.let { it.host to it.port }
    }

    // ---- Server side --------------------------------------------------------------------

    private fun runServer() {
        val s = try {
            ServerSocket(PORT)
        } catch (e: Exception) {
            Log.w(TAG, "Port $PORT busy, using a random port", e)
            ServerSocket(0)
        }
        server = s
        Log.i(TAG, "Listening on ${s.localPort} as \"$deviceName\"")
        watchWifi(s.localPort)
        while (!s.isClosed) {
            val socket = try { s.accept() } catch (e: Exception) { break }
            scope.launch { serve(socket) }
        }
    }

    private suspend fun serve(socket: Socket) {
        socket.soTimeout = CHAT_READ_TIMEOUT_MS
        Conn(socket).use { c ->
            try {
                val first = c.receive()
                when (first.optString("t")) {
                    "pair1" -> servePairing(first, c)
                    "req" -> c.send(serveRequest(first, c))
                    else -> c.send(error("unknown frame"))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Connection from ${c.remoteHost} failed", e)
                runCatching { c.send(error(e.message ?: "error")) }
            }
        }
    }

    private fun servePairing(first: JSONObject, c: Conn) {
        val peerId = first.getString("id")
        val peerName = first.getString("name")
        val commit = first.getString("commit")
        val keys: KeyPair = newKeyPair()
        val myPub = keys.public.encoded
        c.send(JSONObject().put("t", "pair1ok").put("id", deviceId).put("name", deviceName).put("pub", b64(myPub)))
        val second = c.receive()
        val theirPub = unb64(second.getString("pub"))
        if (b64(sha256(theirPub)) != commit) throw LinkException("pairing key doesn't match its commitment")
        val shared = agree(keys.private, theirPub)
        val code = pairingCode(theirPub, myPub, shared)
        pendingIncoming[peerId] = Pending(peerId, peerName, deriveKey(shared), code, c.remoteHost,
            second.optInt("port", PORT))
        c.send(JSONObject().put("t", "pair2ok"))
        _events.tryEmit(
            "$peerName wants to link with this device.\nPairing code: $code\n" +
                "Type \"code $code\" on $peerName to finish. Ignore this if it wasn't you."
        )
    }

    private suspend fun serveRequest(frame: JSONObject, c: Conn): JSONObject {
        val from = frame.getString("from")
        val peer = peers[from]
        val pending = pendingIncoming[from]
        val key = peer?.key ?: pending?.key ?: return error("this device isn't linked")
        val req = JSONObject(String(decrypt(key, frame, from), Charsets.UTF_8))
        if (Math.abs(System.currentTimeMillis() - req.getLong("ts")) > MAX_CLOCK_SKEW_MS) {
            return error("request too old; check both devices' clocks")
        }
        val op = req.getString("op")
        val reply = JSONObject()

        if (peer == null) {
            // A device that isn't linked yet may only confirm a pairing it started.
            if (op != "confirm" || pending == null || pending.expired) return error("this device isn't linked")
            pendingIncoming.remove(from)
            val host = if (c.remoteIsLoopback) pending.host else c.remoteHost
            peers[from] = Peer(from, pending.name, pending.key, host, req.optInt("port", PORT))
            savePeers()
            handler.onPeersChanged()
            _events.tryEmit("Linked with ${pending.name}.")
            return encryptFrame("res", pending.key, reply.put("ok", true))
        }

        // Follow DHCP address changes (loopback means an adb bridge, whose address is set by hand).
        if (!c.remoteIsLoopback && c.remoteHost != null &&
            (peer.host != c.remoteHost || peer.port != req.optInt("port", peer.port))
        ) {
            peer.host = c.remoteHost
            peer.port = req.optInt("port", peer.port)
            savePeers()
        }

        when (op) {
            "ping" -> reply.put("name", deviceName).put("llm", handler.llmReady())
            "status" -> reply.put("text", handler.status())
            "note" -> {
                handler.onNote(peer.name, req.getString("text"))
                reply.put("ok", true)
            }
            "chat" -> {
                val text = handler.brainChat(peer.id, req.optJSONArray("facts").toStrings(),
                    req.optJSONArray("devices").toStrings(), req.getString("text"))
                    ?: return error("$deviceName has no AI model loaded")
                reply.put("text", text)
            }
            "memory_get" -> reply.put("memory", handler.memorySnapshot())
            "contacts" -> reply.put("lines", JSONArray(handler.findContacts(req.getString("name"))))
            "memory_add" -> {
                handler.memoryAdd(req.getString("c"), req.getLong("t"))
                reply.put("ok", true)
            }
            "memory_clear" -> {
                handler.memoryClear(req.getLong("t"))
                reply.put("ok", true)
            }
            "confirm" -> reply.put("ok", true)
            else -> return error("unknown request \"$op\"")
        }
        return encryptFrame("res", peer.key, reply)
    }

    // ---- Discovery ----------------------------------------------------------------------

    /**
     * Re-advertises and re-discovers whenever Wi-Fi (re)connects. After a reboot XARVIS
     * usually starts before Wi-Fi is up, so a one-off announcement at startup would be lost;
     * this also catches routers handing out new addresses and Wi-Fi dropping for a while.
     */
    private fun watchWifi(port: Int) {
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            restartNsd(port)
            return
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = reconnect(port)
        }
        networkCallback = callback
        runCatching {
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback,
            )
        }.onFailure {
            Log.w(TAG, "Can't watch Wi-Fi", it)
            restartNsd(port)
        }
    }

    /** Wi-Fi is back: advertise again, then tell linked devices where to find this one. */
    private fun reconnect(port: Int) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(NETWORK_SETTLE_MS) // Wi-Fi reports "available" a moment before its address is usable
            restartNsd(port)
            announce()
            handler.onPeersChanged()
        }
    }

    /** Pings every linked device; each ping carries this device's current address and port. */
    private suspend fun announce() {
        for (p in pairedPeers()) {
            runCatching { request(p, "ping", timeoutMs = PING_TIMEOUT_MS) }
                .onFailure { Log.i(TAG, "${p.name} not reachable yet: ${it.message}") }
        }
    }

    @Synchronized
    private fun restartNsd(port: Int) {
        stopNsd()
        startNsd(port)
    }

    @Synchronized
    private fun stopNsd() {
        runCatching { registration?.let { nsd?.unregisterService(it) } }
        runCatching { discovery?.let { nsd?.stopServiceDiscovery(it) } }
        registration = null
        discovery = null
    }

    private fun startNsd(port: Int) {
        val mgr = appContext.getSystemService(NsdManager::class.java) ?: return
        nsd = mgr
        val info = NsdServiceInfo().apply {
            serviceName = "XARVIS-${deviceId.take(8)}"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("id", deviceId)
            setAttribute("name", deviceName)
        }
        registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "Advertised as ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.w(TAG, "Advertise failed: $code")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        discovery = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.contains("_xarvis")) resolve(info)
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                Log.w(TAG, "Discovery failed: $code")
            }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
        }
        runCatching {
            mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
            mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        }.onFailure { Log.w(TAG, "mDNS unavailable", it) }
    }

    @Suppress("DEPRECATION") // resolveService/host are deprecated on API 34+, but these phones run 13.
    private fun resolve(service: NsdServiceInfo) {
        nsd?.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) {}
            override fun onServiceResolved(info: NsdServiceInfo) {
                val id = info.attributes["id"]?.let { String(it) } ?: return
                if (id == deviceId) return
                val host = info.host?.hostAddress ?: return
                val name = info.attributes["name"]?.let { String(it) } ?: info.serviceName
                val peer = peers[id]
                if (peer == null) {
                    discovered[id] = Discovered(id, name, host, info.port)
                } else if (peer.host != host || peer.port != info.port) {
                    peer.host = host
                    peer.port = info.port
                    savePeers()
                    // Found a linked device at a new address (e.g. after it rebooted): say hello and catch up.
                    scope.launch {
                        runCatching { request(peer, "ping", timeoutMs = PING_TIMEOUT_MS) }
                        handler.onPeersChanged()
                    }
                }
            }
        })
    }

    // ---- Crypto -------------------------------------------------------------------------

    private fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun agree(private: PrivateKey, theirPub: ByteArray): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(private)
            doPhase(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(theirPub)), true)
            generateSecret()
        }

    private fun deriveKey(shared: ByteArray) = sha256(shared + "xarvis-link-v1".toByteArray())

    private fun pairingCode(initiatorPub: ByteArray, responderPub: ByteArray, shared: ByteArray): String {
        val hash = sha256(initiatorPub + responderPub + shared)
        val n = ByteBuffer.wrap(hash).int.toLong() and 0xffffffffL
        return "%06d".format(n % 1_000_000)
    }

    private fun encryptFrame(type: String, key: ByteArray, body: JSONObject): JSONObject {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(deviceId.toByteArray())
        }
        return JSONObject().put("t", type).put("from", deviceId).put("n", b64(nonce))
            .put("c", b64(cipher.doFinal(body.toString().toByteArray())))
    }

    private fun decrypt(key: ByteArray, frame: JSONObject, sender: String): ByteArray {
        if (frame.getString("from") != sender) throw LinkException("unexpected sender")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, unb64(frame.getString("n"))))
            updateAAD(sender.toByteArray())
        }
        return cipher.doFinal(unb64(frame.getString("c")))
    }

    // ---- Storage & helpers --------------------------------------------------------------

    private fun loadPeers(): List<Peer> = runCatching {
        val arr = JSONArray(prefs.getString("peers", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Peer(o.getString("id"), o.getString("name"), unb64(o.getString("key")),
                o.optString("host").ifEmpty { null }, o.optInt("port", PORT))
        }
    }.getOrDefault(emptyList())

    private fun savePeers() {
        val arr = JSONArray()
        peers.values.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("key", b64(it.key))
                .put("host", it.host ?: "").put("port", it.port))
        }
        prefs.edit().putString("peers", arr.toString()).apply()
    }

    private fun error(msg: String) = JSONObject().put("t", "err").put("msg", msg)

    private fun JSONArray?.toStrings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { getString(it) }

    companion object {
        private const val TAG = "XarvisLink"
        const val PORT = 47470
        private const val SERVICE_TYPE = "_xarvis._tcp"
        private const val CONNECT_TIMEOUT_MS = 2_000
        private const val PING_TIMEOUT_MS = 3_000
        private const val REQUEST_READ_TIMEOUT_MS = 15_000
        private const val PAIRING_READ_TIMEOUT_MS = 15_000
        private const val CHAT_READ_TIMEOUT_MS = 180_000
        private const val CONTACTS_READ_TIMEOUT_MS = 90_000 // may wait for a permission answer on that phone
        private const val PAIRING_TIMEOUT_MS = 5 * 60_000L
        private const val MAX_CLOCK_SKEW_MS = 5 * 60_000L
        private const val NETWORK_SETTLE_MS = 3_000L
        private val ADDRESS = Regex("""^(\d{1,3}(?:\.\d{1,3}){3})(?::(\d{1,5}))?$""")

        private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
        private fun b64(data: ByteArray): String = Base64.getEncoder().encodeToString(data)
        private fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)

        /** "my benco mobile" -> "benco". */
        fun normalize(query: String): String = query.lowercase().trim()
            .removePrefix("my ").removePrefix("the ")
            .removeSuffix(" phone").removeSuffix(" mobile").removeSuffix(" device").trim()

        fun shortName(name: String): String = name.split(' ').firstOrNull { it.length > 2 }?.lowercase() ?: name
    }
}
