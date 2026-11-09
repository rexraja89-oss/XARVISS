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

/**
 * A linked device. [tailnetHost]: its Tailscale address, used when it isn't on the same Wi-Fi.
 * [kind]: [KIND_PHONE] (Rex's own phones: everything is shared), [KIND_HELPER] (a family phone with XARVIS Helper
 * that Rex helps: it may only ask for help) or [KIND_OWNER] (on a Helper: the phone that helps it).
 */
class Peer(
    val id: String, val name: String, val key: ByteArray, var host: String?, var port: Int,
    var tailnetHost: String? = null, val kind: String = KIND_PHONE,
) {
    companion object {
        const val KIND_PHONE = "phone"
        const val KIND_HELPER = "helper"
        const val KIND_OWNER = "owner"
    }
}

class LinkException(message: String) : Exception(message)

/**
 * Links XARVIS installs on the same network.
 *
 * Devices find each other with mDNS (`_xarvis._tcp`) and talk over TCP with one JSON
 * frame per line. Pairing is an ECDH exchange where the initiator commits to its key
 * first; both sides then derive a 6-digit code from the transcript, the responder
 * shows it and the user types it on the initiator, which defeats a man in the middle.
 * After pairing, every request and response is AES-256-GCM encrypted with the shared key.
 *
 * Away from home Wi-Fi the phones meet through the relay (Rex's own MQTT broker) and, for remote control and
 * files, go straight to each other over XARVIS Direct (UDP hole punching) when the networks allow it.
 * A family phone with XARVIS Helper links by an invite Rex sends on WhatsApp ([createInvite], [acceptInvite]).
 *
 * [helperApp]: this is XARVIS Helper (no home-network server, and it only answers the ops a helped phone needs).
 */
class DeviceLink(context: Context, private val handler: Handler, private val helperApp: Boolean = false) {

    interface Handler {
        /** Device status to report to a linked device. */
        suspend fun status(): String

        /** This phone's battery, for a linked phone that asks. */
        suspend fun battery(): String

        /** Recent photos (name + downscaled JPEG) from this phone's shared folders, for a linked phone. */
        suspend fun galleryPhotos(limit: Int): List<Pair<String, ByteArray>>

        /** Runs a linked device's free-form message through this device's LLM; null if it has none. */
        suspend fun brainChat(peerId: String, facts: List<String>, devices: List<String>, text: String, history: String): String?

        /** Looks at a linked device's photo ([jpeg]) with this device's LLM; null if it can't. */
        suspend fun brainPhoto(peerId: String, facts: List<String>, jpeg: ByteArray, text: String): String?

        fun llmReady(): Boolean
        fun onNote(from: String, text: String)
        fun onPeersChanged()

        /** Shared memory: this device's remembered facts, and facts or wipes pushed by a linked device. */
        suspend fun memorySnapshot(): JSONObject
        suspend fun memoryAdd(content: String, timestamp: Long)
        suspend fun memoryClear(timestamp: Long)

        /** This phone's contacts matching [name], for a linked phone's "find contact". */
        suspend fun findContacts(name: String): List<String>

        /**
         * Takes a photo with this phone's camera for linked phone [from] (it has none) and returns it as a
         * JPEG; throws with a plain reason when it can't.
         */
        suspend fun snapPhoto(front: Boolean, from: String): ByteArray

        /** Who is in a linked phone's photo, by this phone's face recognition ("That's Rex, sir — 87% match"). */
        suspend fun facesInPhoto(jpeg: ByteArray): String

        /** Remote control: this screen for linked phone [from] — JPEG, plus the screen's real width and height. */
        suspend fun screenFrame(from: String, maxSide: Int): Triple<ByteArray, Int, Int>

        /** Remote control: a tap, swipe, key or text from linked phone [from] (see ScreenHost.input). */
        suspend fun screenInput(from: String, cmd: JSONObject): Boolean

        /** Remote control: [from] closed its view of this screen. */
        fun screenStop(from: String)

        /**
         * Remote control, smooth: stream this screen to [from] over [channel] and take its touches, until either
         * side closes. Call [accept] once with the stream's description before sending anything; throw to refuse.
         */
        suspend fun screenStream(from: String, req: JSONObject, channel: Channel, accept: (JSONObject) -> Unit)

        /** A file sent from linked phone [from]: save it here and say so; returns the saved name. */
        suspend fun receiveFile(from: String, name: String, mime: String, bytes: ByteArray): String

        /** Text sent from linked phone [from] for this phone's clipboard. */
        suspend fun receiveClip(from: String, text: String)

        /** XARVIS acting on this phone for [from]: "open" an app, "read" the screen, "tap" a label, "type" text. */
        suspend fun act(from: String, kind: String, arg: String): String

        /** One decision by this phone's AI model with [system] instructions (a linked phone's PhoneAgent); null without one. */
        suspend fun brainOneShot(system: String, text: String): String?

        /**
         * Guard mode, run from Rex's other phone: "status", "say" (text), "play" (audio + ext), "stop", "panic".
         * Answers {"state","phase"} for status, {"text"} otherwise; throws with a reason when it can't.
         */
        suspend fun guard(from: String, action: String, req: JSONObject): JSONObject

        /** The linked phone's intruder alarm went off: tell Rex here (notification + chat), with the intruder's photo. */
        suspend fun guardAlert(from: String, text: String, jpeg: ByteArray?)

        /** A family phone with XARVIS Helper asks Rex for help (its owner just tapped "Ask for help"). */
        suspend fun helpRequest(peer: Peer, text: String) {}

        /** A new phone linked through an invite (XARVIS Helper on a family phone). */
        fun onInviteUsed(peer: Peer) {}
    }

    /** A linked phone's screen: the picture, and the size of the real screen it shows. */
    class ScreenFrame(val jpeg: ByteArray, val width: Int, val height: Int)

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

    /** The relay: the way to the linked phones away from home Wi-Fi, without Tailscale (☰ → Always connected). */
    val relay = Relay(appContext, deviceId)
    private val relayConns = ConcurrentHashMap<String, RelayConn>()
    /** When reaching a linked phone at its home Wi-Fi address last failed (so it isn't tried on every request). */
    private val wifiFailedAt = ConcurrentHashMap<String, Long>()
    /** When XARVIS Direct last failed to reach each phone (so seconds aren't spent on it again right away). */
    private val directFailedAt = ConcurrentHashMap<String, Long>()
    /** XARVIS Direct lines being set up or in use (a cap, so a linked phone can't make this one open many sockets). */
    private val directLines = java.util.concurrent.atomic.AtomicInteger()

    private class Discovered(val id: String, val name: String, val host: String, val port: Int)

    private class Pending(
        val peerId: String, val name: String, val key: ByteArray, val code: String,
        val host: String?, val port: Int, val created: Long = System.currentTimeMillis(),
    ) {
        val expired: Boolean get() = System.currentTimeMillis() - created > PAIRING_TIMEOUT_MS
    }

    /**
     * One conversation with a linked phone, line by line: a TCP connection (home Wi-Fi, Tailscale) or a
     * connection through the relay. [route] is "wifi", "tailscale" or "relay" (shown in remote control).
     */
    internal interface LineLink : AutoCloseable {
        val remoteHost: String?
        val remoteIsLoopback: Boolean
        val route: String
        fun sendLine(line: String)
        fun receiveLine(): String
        /** For a long-lived stream: how long a read may wait (and, on TCP, no batching of small packets). */
        fun streaming(readTimeoutMs: Int)
        fun send(frame: JSONObject) = sendLine(frame.toString())
        fun receive(): JSONObject = JSONObject(receiveLine())
    }

    internal class Conn(private val socket: Socket, override val route: String = "wifi") : LineLink {
        private val reader = socket.getInputStream().bufferedReader()
        private val writer = socket.getOutputStream().bufferedWriter()
        override val remoteHost: String? get() = socket.inetAddress?.hostAddress
        override val remoteIsLoopback: Boolean get() = socket.inetAddress?.isLoopbackAddress == true

        override fun sendLine(line: String) = synchronized(writer) {
            writer.write(line); writer.newLine(); writer.flush()
        }

        override fun receiveLine(): String = reader.readLine() ?: throw LinkException("connection closed")

        override fun streaming(readTimeoutMs: Int) {
            socket.soTimeout = readTimeoutMs
            socket.tcpNoDelay = true
        }

        override fun close() = socket.close()
    }

    /**
     * A conversation through the relay: lines go to the other phone's mailbox, its answers arrive in [inbox].
     * [server]: the other phone started it (this phone answers).
     */
    private inner class RelayConn(
        val peerId: String, val cid: String, @Volatile var timeoutMs: Int, server: Boolean,
    ) : LineLink {
        val inbox = java.util.concurrent.LinkedBlockingQueue<String>()
        @Volatile private var opened = server
        @Volatile private var stream = false
        @Volatile var closedByPeer = false
        override val remoteHost: String? = null
        override val remoteIsLoopback = false
        override val route = "relay"

        override fun sendLine(line: String) {
            val kind = if (opened) "d" else "o"
            opened = true
            relay.sendLine(peerId, cid, kind, line)
        }

        override fun receiveLine(): String {
            val line = inbox.poll(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                ?: throw java.net.SocketTimeoutException("no answer through the relay")
            if (line === CLOSED) throw LinkException("connection closed")
            return line
        }

        override fun streaming(readTimeoutMs: Int) {
            timeoutMs = readTimeoutMs
            stream = true
        }

        override fun close() {
            relayConns.remove("$peerId|$cid")
            // A plain question needs no goodbye; a stream tells the other side it's over.
            if (stream && !closedByPeer) runCatching { relay.sendLine(peerId, cid, "x", "") }
        }
    }

    /** A conversation over XARVIS Direct: UDP straight to the other phone, through both phones' NATs. */
    private inner class DirectLink(private val c: Direct.Conn) : LineLink {
        // Not a home address (it must never replace the phone's Wi-Fi address).
        override val remoteHost: String? = null
        override val remoteIsLoopback = false
        override val route = "direct"
        override fun sendLine(line: String) = c.sendLine(line)
        override fun receiveLine(): String = c.receiveLine()
        override fun streaming(readTimeoutMs: Int) { c.readTimeoutMs = readTimeoutMs }
        private val done = java.util.concurrent.atomic.AtomicBoolean()
        override fun close() {
            c.close()
            if (done.compareAndSet(false, true)) directLines.decrementAndGet() // closed more than once by some callers
        }
    }

    /**
     * A long-lived, encrypted, two-way stream with a linked phone (remote control: video one way, touches
     * the other). Each message is one line, "nonce.ciphertext" in base64, AES-256-GCM with the pairing key;
     * the plaintext is a type byte and a payload. Opened with [openChannel] / served for op "stream".
     */
    class Channel internal constructor(
        private val conn: LineLink, private val key: ByteArray, private val myId: String, private val theirId: String,
    ) : AutoCloseable {
        /** "wifi", "tailscale", "direct", "relay" — how this stream travels (the remote-control screen shows it). */
        val route: String get() = conn.route

        private val random = SecureRandom()
        private val enc = Cipher.getInstance("AES/GCM/NoPadding")
        private val dec = Cipher.getInstance("AES/GCM/NoPadding")
        private val aesKey = SecretKeySpec(key, "AES")
        @Volatile var closed = false
            private set

        fun send(type: Int, payload: ByteArray = ByteArray(0)) {
            val plain = ByteArray(payload.size + 1)
            plain[0] = type.toByte()
            System.arraycopy(payload, 0, plain, 1, payload.size)
            val line = synchronized(enc) {
                val nonce = ByteArray(12).also(random::nextBytes)
                enc.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, nonce))
                enc.updateAAD(("ch:$myId").toByteArray())
                Base64.getEncoder().encodeToString(nonce) + "." + Base64.getEncoder().encodeToString(enc.doFinal(plain))
            }
            conn.sendLine(line)
        }

        /** The next message (type, payload); throws when the stream closes or the other side reports an error. */
        fun receive(): Pair<Int, ByteArray> {
            val line = conn.receiveLine()
            if (line.startsWith("{")) throw LinkException(runCatching { JSONObject(line).optString("msg") }.getOrNull() ?: "stream error")
            val dot = line.indexOf('.')
            if (dot < 0) throw LinkException("bad stream message")
            val plain = synchronized(dec) {
                dec.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, Base64.getDecoder().decode(line.substring(0, dot))))
                dec.updateAAD(("ch:$theirId").toByteArray())
                dec.doFinal(Base64.getDecoder().decode(line.substring(dot + 1)))
            }
            return (plain[0].toInt() and 0xff) to plain.copyOfRange(1, plain.size)
        }

        /** Tells the other side why the stream is ending (a plain error line, which [receive] reports). */
        fun fail(message: String) {
            runCatching { conn.sendLine(JSONObject().put("t", "err").put("msg", message).toString()) }
        }

        override fun close() {
            closed = true
            runCatching { conn.close() }
        }
    }

    /** Rex's own linked phones (and, on a Helper, the phones that help it) — never the family phones he helps. */
    fun pairedPeers(): List<Peer> = peers.values.filter { it.kind != Peer.KIND_HELPER }.sortedBy { it.name }

    /** Family phones with XARVIS Helper that this phone can help. */
    fun helperPeers(): List<Peer> = peers.values.filter { it.kind == Peer.KIND_HELPER }.sortedBy { it.name }

    fun peerById(id: String): Peer? = peers[id]

    fun start() {
        // XARVIS Helper doesn't listen on the home network: it's reached through the relay only.
        if (!helperApp) scope.launch { runServer() }
        relay.setPeers(peers.keys)
        relay.start(scope) { from, cid, kind, line -> onRelayLine(from, cid, kind, line) }
    }

    /** A line from the relay: a linked phone starting a conversation ("o"), continuing one ("d") or ending it ("x"). */
    private fun onRelayLine(from: String, cid: String, kind: String, line: String) {
        val key = "$from|$cid"
        when (kind) {
            "o" -> {
                val c = RelayConn(from, cid, CHAT_READ_TIMEOUT_MS, server = true)
                relayConns[key] = c
                c.inbox.put(line)
                scope.launch { serve(c) }
            }
            "d" -> relayConns[key]?.inbox?.put(line)
            "x" -> relayConns[key]?.let { it.closedByPeer = true; it.inbox.put(CLOSED) }
        }
    }

    fun stop() {
        runCatching { networkCallback?.let { appContext.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } }
        stopNsd()
        runCatching { server?.close() }
        relay.stop()
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
        relay.setPeers(peers.keys)
        handler.onPeersChanged()
        return "Linked with ${p.name}. Your memories are now shared between the two phones."
    }

    /** Whether this phone is waiting for the user to type another phone's pairing code. */
    val pairingInProgress: Boolean get() = pendingOutgoing?.expired == false

    fun findPeer(query: String): Peer? {
        val words = normalize(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        return pairedPeers().firstOrNull { p -> words.all { p.name.lowercase().contains(it) } }
    }

    suspend fun remoteStatus(peer: Peer): String = request(peer, "status").getString("text")

    /** A linked phone's battery, as it reads it itself. */
    suspend fun remoteBattery(peer: Peer): String = request(peer, "battery").getString("text")

    suspend fun sendNote(peer: Peer, text: String) {
        request(peer, "note", JSONObject().put("text", text))
    }

    suspend fun remoteChat(peer: Peer, facts: List<String>, devices: List<String>, text: String, history: String = ""): String =
        request(
            peer, "chat",
            JSONObject().put("text", text).put("facts", JSONArray(facts)).put("devices", JSONArray(devices))
                .put("history", history),
            timeoutMs = CHAT_READ_TIMEOUT_MS,
        ).getString("text")

    /** Sends a photo to [peer]'s Gemma with Rex's question about it; returns its raw reply. */
    suspend fun remotePhoto(peer: Peer, facts: List<String>, jpeg: ByteArray, text: String): String =
        request(
            peer, "photo",
            JSONObject().put("text", text).put("facts", JSONArray(facts)).put("image", b64(jpeg)),
            timeoutMs = CHAT_READ_TIMEOUT_MS,
        ).getString("text")

    /** [peer]'s contacts matching [name] (a contact saved only on that phone). */
    /** Recent photos from a linked phone's shared folders (name + JPEG bytes). */
    suspend fun remoteGallery(peer: Peer, limit: Int): List<Pair<String, ByteArray>> {
        val arr = request(peer, "gallery", JSONObject().put("limit", limit), timeoutMs = GALLERY_READ_TIMEOUT_MS)
            .optJSONArray("photos") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            o.getString("name") to unb64(o.getString("jpg"))
        }
    }

    /** A photo taken just now with [peer]'s camera (front or back), as a JPEG. */
    suspend fun remoteSnap(peer: Peer, front: Boolean): ByteArray =
        unb64(request(peer, "snap", JSONObject().put("front", front), timeoutMs = SNAP_READ_TIMEOUT_MS).getString("jpg"))

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
    suspend fun findBrain(): Peer? = reachable().firstOrNull { it.second }?.first

    /**
     * The linked devices that answer right now, each with whether its AI model is ready.
     *
     * Also drops a stale pairing: after the S22's XARVIS was reinstalled and re-paired, the benco kept its
     * old entry for it (same name, old key — "2 LINKED" on the benco, "1 LINKED" on the S22). Requests
     * through it fail with BAD_DECRYPT, and once one hid a real error from the remote camera. It's removed
     * only when it fails that way and a device with the same name answers.
     */
    suspend fun reachable(): List<Pair<Peer, Boolean>> {
        val answers = pairedPeers().map { p -> p to runCatching { request(p, "ping", timeoutMs = PING_TIMEOUT_MS) } }
        val ok = answers.filter { it.second.isSuccess }
        for ((p, r) in answers) {
            val why = r.exceptionOrNull()?.message.orEmpty()
            val oldKey = "BAD_DECRYPT" in why || "isn't linked" in why
            if (oldKey && ok.any { (q, _) -> q.id != p.id && q.name == p.name }) {
                Log.i(TAG, "Dropping stale pairing ${p.name} (${p.id.take(8)}): $why")
                unpair(p)
            }
        }
        return ok.map { (p, r) -> p to r.getOrThrow().optBoolean("llm") }
    }

    /**
     * Opens a long-lived encrypted [Channel] to [peer] for [op] ("stream": remote control). Returns the peer's
     * first answer (e.g. the video size) and the channel; the caller closes it.
     */
    suspend fun openChannel(peer: Peer, op: String, body: JSONObject): Pair<JSONObject, Channel> = withContext(Dispatchers.IO) {
        body.put("op", op).put("ts", System.currentTimeMillis()).put("port", myPort)
        tailnetAddress()?.let { body.put("tailnet", it) }
        val c = open(peer, REQUEST_READ_TIMEOUT_MS, op) ?: throw LinkException("${peer.name} is offline.")
        try {
            c.send(encryptFrame("req", peer.key, body))
            val res = c.receive()
            if (res.optString("t") == "err") throw LinkException(res.optString("msg"))
            val first = JSONObject(String(decrypt(peer.key, res, peer.id), Charsets.UTF_8))
            c.streaming(STREAM_READ_TIMEOUT_MS)
            first to Channel(c, peer.key, deviceId, peer.id)
        } catch (e: Exception) {
            c.close()
            throw e
        }
    }


    /** Sends a file to [peer] (it lands in its Downloads › XARVIS); returns the name it was saved as. */
    suspend fun sendFile(peer: Peer, name: String, mime: String, bytes: ByteArray): String =
        request(
            peer, "file", JSONObject().put("name", name).put("mime", mime).put("data", b64(bytes)),
            timeoutMs = FILE_READ_TIMEOUT_MS,
        ).optString("saved", name)

    /** Puts [text] on [peer]'s clipboard. */
    suspend fun sendClip(peer: Peer, text: String) {
        request(peer, "clip", JSONObject().put("text", text))
    }

    /** Guard mode on [peer] (its alarm): "status", "say" {text}, "play" {audio, ext}, "stop", "panic". */
    suspend fun remoteGuard(peer: Peer, action: String, body: JSONObject = JSONObject()): JSONObject =
        request(peer, "guard", body.put("action", action), timeoutMs = SCREEN_READ_TIMEOUT_MS)

    /** A voice clip for [peer]'s alarm to play out loud (recorded on this phone). */
    suspend fun remoteGuardVoice(peer: Peer, audio: ByteArray, extension: String): JSONObject =
        request(peer, "guard", JSONObject().put("action", "play").put("audio", b64(audio)).put("ext", extension), timeoutMs = FILE_READ_TIMEOUT_MS)

    /** This phone's intruder alarm went off: alert [peer] (Rex's other phone) with [text] and the photo. */
    suspend fun sendGuardAlert(peer: Peer, text: String, jpeg: ByteArray?) {
        val body = JSONObject().put("text", text)
        if (jpeg != null) body.put("jpg", b64(jpeg))
        request(peer, "guard_alert", body, timeoutMs = FILE_READ_TIMEOUT_MS)
    }

    /** [peer]'s AI model answering [text] once, with [system] instructions (PhoneAgent's steps on a phone without one). */
    suspend fun remoteThink(peer: Peer, system: String, text: String): String =
        request(peer, "think", JSONObject().put("system", system).put("text", text), timeoutMs = CHAT_READ_TIMEOUT_MS).getString("text")

    /** XARVIS acting on [peer]: "open" (app), "read" (its screen), "tap" (label), "type" (text). Returns what happened. */
    suspend fun remoteAct(peer: Peer, kind: String, arg: String = ""): String =
        request(peer, "act", JSONObject().put("kind", kind).put("arg", arg), timeoutMs = SCREEN_READ_TIMEOUT_MS).getString("text")

    /** Remote control: [peer]'s screen right now, at most [maxSide] pixels on its long side. */
    suspend fun remoteScreen(peer: Peer, maxSide: Int): ScreenFrame {
        val r = request(peer, "screen", JSONObject().put("max", maxSide), timeoutMs = SCREEN_READ_TIMEOUT_MS)
        return ScreenFrame(unb64(r.getString("jpg")), r.getInt("w"), r.getInt("h"))
    }

    /** Remote control: a tap/swipe/key/text on [peer] (see ScreenHost.input); whether it was done. */
    suspend fun remoteInput(peer: Peer, cmd: JSONObject): Boolean =
        request(peer, "input", JSONObject().put("cmd", cmd), timeoutMs = SCREEN_READ_TIMEOUT_MS).optBoolean("ok")

    suspend fun remoteScreenStop(peer: Peer) {
        request(peer, "screen_stop", timeoutMs = PING_TIMEOUT_MS)
    }

    /** Who is in [jpeg], by [peer]'s face recognition (the faces Rex taught XARVIS on that phone). */
    suspend fun remoteFaces(peer: Peer, jpeg: ByteArray): String =
        request(peer, "faces", JSONObject().put("image", b64(jpeg)), timeoutMs = CHAT_READ_TIMEOUT_MS).getString("text")

    fun unpair(peer: Peer) {
        peers.remove(peer.id)
        savePeers()
        relay.setPeers(peers.keys)
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
        body.put("op", op).put("ts", System.currentTimeMillis()).put("port", myPort)
        tailnetAddress()?.let { body.put("tailnet", it) }
        // Home Wi-Fi first, then Tailscale (when it's on), then the relay (anywhere).
        val link = open(peer, timeoutMs, op)
            ?: return@withContext JSONObject().put("ok", true).put("queued", true) // waits at the relay for that phone
        val answer = exchange(link, peer, body)
        if (op == "ping") shareRelay(peer, answer)
        answer
    }

    /**
     * A connection to [peer] by the best route: its home Wi-Fi address (only when this phone is on Wi-Fi and it
     * didn't just fail), its Tailscale address (only when Tailscale is on here), then — for remote control and
     * files — XARVIS Direct, else the relay. Null: [peer] is offline but [op] can wait at the relay for it (a
     * guard alert, a note).
     */
    private suspend fun open(peer: Peer, timeoutMs: Int, op: String): LineLink? {
        var failure: Exception? = null
        val routes = buildList {
            val lan = peer.host
            // After a recent miss, go straight to the relay — but only when the relay is there to take over.
            val missedRecently = System.currentTimeMillis() - (wifiFailedAt[peer.id] ?: 0L) < WIFI_RETRY_MS
            if (lan != null && onWifi() && !(missedRecently && relay.connected)) add(lan to "wifi")
            if (peer.tailnetHost != null && tailnetAddress() != null) add(peer.tailnetHost!! to "tailscale")
        }.distinctBy { it.first }
        for ((host, route) in routes) {
            try {
                val conn = Conn(connect(host, peer.port, timeoutMs), route)
                if (route == "wifi") wifiFailedAt.remove(peer.id)
                return conn
            } catch (e: java.io.IOException) {
                failure = e
                if (route == "wifi") wifiFailedAt[peer.id] = System.currentTimeMillis()
            }
        }
        if (relay.connected) {
            if (relay.online(peer.id) == false) {
                if (op in DELIVER_LATER) {
                    // Posted now, delivered when that phone comes back online.
                    val c = RelayConn(peer.id, UUID.randomUUID().toString(), timeoutMs, server = false)
                    return QueuedOnce(c)
                }
                throw LinkException(
                    if (peer.kind == Peer.KIND_HELPER) "${peer.name} has XARVIS Helper closed. Ask them to open it and tap \"Ask for help\"."
                    else "${peer.name} is offline (its XARVIS isn't connected to the internet right now).",
                )
            }
            // Big things (remote control, files) go straight to the other phone when the networks allow it.
            if (op in DIRECT_OPS) tryDirect(peer, timeoutMs)?.let { return it }
            val c = RelayConn(peer.id, UUID.randomUUID().toString(), maxOf(timeoutMs, RELAY_MIN_TIMEOUT_MS), server = false)
            relayConns["${peer.id}|${c.cid}"] = c
            return c
        }
        throw LinkException(
            "${peer.name} isn't reachable" + when {
                relay.config() == null && peer.tailnetHost == null ->
                    ". Away from home Wi-Fi, set up the relay (☰ → Always connected) or turn Tailscale on."
                relay.config() != null -> ": the relay isn't connected (${relay.status.value.detail.ifBlank { "no internet?" }})."
                else -> " on Wi-Fi or Tailscale (${failure?.message ?: "no answer"})."
            },
        )
    }

    /** A one-way post for a phone that's offline: sent once, no answer awaited. */
    private inner class QueuedOnce(private val c: RelayConn) : LineLink by c {
        override fun receiveLine(): String = throw QueuedException()
    }

    private class QueuedException : Exception("queued at the relay")

    /**
     * XARVIS Direct to [peer]: swap addresses through the relay, punch through both NATs, and return the line;
     * null when it can't get through (then the relay carries it). A failure isn't retried for a while.
     */
    private suspend fun tryDirect(peer: Peer, timeoutMs: Int): LineLink? {
        if (System.currentTimeMillis() - (directFailedAt[peer.id] ?: 0L) < DIRECT_RETRY_MS) return null
        if (directLines.incrementAndGet() > MAX_DIRECT_LINES) {
            directLines.decrementAndGet()
            return null
        }
        val d = runCatching { Direct.open() }.getOrNull() ?: run { directLines.decrementAndGet(); return null }
        var conn: Direct.Conn? = null
        try {
            val mine = withContext(Dispatchers.IO) { d.candidates() }
            val token = ByteArray(16).also(random::nextBytes)
            val answer = request(
                peer, "direct", JSONObject().put("tok", b64(token)).put("cands", JSONArray(mine)), timeoutMs = DIRECT_SETUP_TIMEOUT_MS,
            )
            val theirs = answer.optJSONArray("cands").toStrings()
            if (theirs.isNotEmpty()) conn = withContext(Dispatchers.IO) { d.connect(token, theirs, initiator = true, timeoutMs = DIRECT_PUNCH_MS) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                d.close()
                directLines.decrementAndGet()
                throw e
            }
            Log.i(TAG, "XARVIS Direct to ${peer.name}: ${e.message}")
        }
        if (conn == null) {
            d.close()
            directLines.decrementAndGet()
            directFailedAt[peer.id] = System.currentTimeMillis()
            Log.i(TAG, "XARVIS Direct to ${peer.name} didn't get through; using the relay")
            return null
        }
        directFailedAt.remove(peer.id)
        Log.i(TAG, "XARVIS Direct to ${peer.name}: through to ${conn.remote}")
        conn.readTimeoutMs = timeoutMs
        return DirectLink(conn)
    }

    /** The answering side of XARVIS Direct: this phone's addresses go back in the reply; the line is served once through. */
    private fun answerDirect(req: JSONObject, reply: JSONObject) {
        if (directLines.incrementAndGet() > MAX_DIRECT_LINES) {
            directLines.decrementAndGet()
            reply.put("cands", JSONArray())
            return
        }
        val d = try {
            Direct.open()
        } catch (e: Exception) {
            directLines.decrementAndGet()
            reply.put("cands", JSONArray())
            return
        }
        val token = unb64(req.getString("tok"))
        val theirs = req.optJSONArray("cands").toStrings()
        val mine = runCatching { d.candidates() }.getOrDefault(emptyList())
        reply.put("cands", JSONArray(mine))
        scope.launch {
            val conn = runCatching { d.connect(token, theirs, initiator = false, timeoutMs = DIRECT_ANSWER_MS) }.getOrNull()
            if (conn == null) {
                d.close()
                directLines.decrementAndGet()
                return@launch
            }
            conn.readTimeoutMs = CHAT_READ_TIMEOUT_MS
            serve(DirectLink(conn))
        }
    }

    // ---- Linking by invite (XARVIS Helper on a family phone, through the relay) ----

    /**
     * An invite for a family phone with XARVIS Helper, to send on WhatsApp: the relay login, this phone's id and a
     * one-time secret. Whoever has the secret can link as a phone Rex helps (it may then only ask for help), and
     * the secret also proves to that phone it really linked with this one — so nobody in between (not even the
     * relay) can take part. Null without the relay.
     */
    fun createInvite(): String? {
        val cfg = relay.config() ?: return null
        val secret = ByteArray(16).also(random::nextBytes)
        val id = UUID.randomUUID().toString().take(8)
        val invites = JSONObject(prefs.getString("invites", "{}") ?: "{}")
        // Old ones expire (a week).
        invites.keys().asSequence().toList().forEach { k ->
            if (System.currentTimeMillis() - invites.getJSONObject(k).optLong("t") > INVITE_VALID_MS) invites.remove(k)
        }
        invites.put(id, JSONObject().put("s", b64(secret)).put("t", System.currentTimeMillis()))
        prefs.edit().putString("invites", invites.toString()).apply()
        val payload = JSONObject().put("v", 1).put("r", "${cfg.host}:${cfg.port}").put("u", cfg.user).put("p", cfg.pass)
            .put("d", deviceId).put("n", deviceName).put("i", id).put("s", b64(secret))
        return INVITE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().toByteArray())
    }

    /**
     * On XARVIS Helper: links with the phone that sent [text] (an invite pasted or shared from WhatsApp). Saves
     * the relay login, then pairs through the relay. Returns the other phone's name; throws with a plain reason.
     */
    suspend fun acceptInvite(text: String): String = withContext(Dispatchers.IO) {
        val inv = parseInvite(text) ?: throw LinkException("That isn't a XARVIS invite. Copy the whole message Rex sent and try again.")
        val (h, port) = Relay.splitHost(inv.getString("r"))
        val cfg = Relay.Config(h, port, inv.getString("u"), inv.getString("p"))
        if (relay.config() != cfg) relay.save(cfg)
        val ownerId = inv.getString("d")
        relay.setPeers(peers.keys + ownerId)
        // Wait for the relay (up to 20 s) and for the other phone's presence to be known.
        var waited = 0
        while (!relay.connected && waited < 20_000) { delay(250); waited += 250 }
        if (!relay.connected) throw LinkException("Couldn't reach the internet relay (${relay.status.value.detail.ifBlank { "no internet?" }}).")
        val secret = unb64(inv.getString("s"))
        val keys = newKeyPair()
        val myPub = keys.public.encoded
        val c = RelayConn(ownerId, UUID.randomUUID().toString(), INVITE_TIMEOUT_MS, server = false)
        relayConns["$ownerId|${c.cid}"] = c
        c.use {
            it.send(
                JSONObject().put("t", "ipair1").put("id", deviceId).put("name", deviceName).put("inv", inv.getString("i"))
                    .put("pub", b64(myPub)).put("mac", b64(hmac(secret, "ipair1|$deviceId|$deviceName|${b64(myPub)}"))),
            )
            val r = try {
                it.receive()
            } catch (e: java.net.SocketTimeoutException) {
                throw LinkException("${inv.optString("n", "Rex's phone")} didn't answer. Its XARVIS must be on and connected; try again in a minute.")
            }
            if (r.optString("t") != "ipair1ok") throw LinkException(r.optString("msg", "the invite was refused"))
            val theirPub = unb64(r.getString("pub"))
            val ownerName = r.getString("name")
            val expect = hmac(secret, "ipair1ok|$ownerId|$ownerName|${b64(theirPub)}|${b64(myPub)}")
            if (!MessageDigest.isEqual(expect, unb64(r.getString("mac")))) throw LinkException("the answer didn't prove it's the right phone, so I didn't link")
            val key = sha256(agree(keys.private, theirPub) + secret + "xarvis-invite-v1".toByteArray())
            peers[ownerId] = Peer(ownerId, ownerName, key, null, PORT, kind = Peer.KIND_OWNER)
            savePeers()
            relay.setPeers(peers.keys)
            handler.onPeersChanged()
            ownerName
        }
    }

    /** The other side of [acceptInvite], on Rex's phone: checks the invite's secret and links the family phone. */
    private fun serveInvite(first: JSONObject, c: LineLink) {
        val inviteId = first.getString("inv")
        val invites = JSONObject(prefs.getString("invites", "{}") ?: "{}")
        val inv = invites.optJSONObject(inviteId)
        if (inv == null || System.currentTimeMillis() - inv.optLong("t") > INVITE_VALID_MS) {
            c.send(error("this invite has expired; ask for a new one"))
            return
        }
        val secret = unb64(inv.getString("s"))
        val peerId = first.getString("id")
        val sentName = first.getString("name")
        val theirPub = unb64(first.getString("pub"))
        val expect = hmac(secret, "ipair1|$peerId|$sentName|${b64(theirPub)}")
        if (!MessageDigest.isEqual(expect, unb64(first.getString("mac")))) {
            c.send(error("this invite isn't valid"))
            return
        }
        // One family phone per invite (the same phone may try again).
        val usedBy = inv.optString("by")
        if (usedBy.isNotEmpty() && usedBy != peerId) {
            c.send(error("this invite was already used by another phone; ask for a new one"))
            return
        }
        if (peers[peerId]?.let { it.kind != Peer.KIND_HELPER } == true) {
            c.send(error("that phone is already linked as one of your own"))
            return
        }
        val peerName = sentName.take(60)
        val keys = newKeyPair()
        val myPub = keys.public.encoded
        val key = sha256(agree(keys.private, theirPub) + secret + "xarvis-invite-v1".toByteArray())
        val peer = Peer(peerId, peerName, key, null, PORT, kind = Peer.KIND_HELPER)
        peers[peerId] = peer
        savePeers()
        invites.put(inviteId, inv.put("by", peerId))
        prefs.edit().putString("invites", invites.toString()).apply()
        relay.setPeers(peers.keys)
        c.send(
            JSONObject().put("t", "ipair1ok").put("id", deviceId).put("name", deviceName).put("pub", b64(myPub))
                .put("mac", b64(hmac(secret, "ipair1ok|$deviceId|$deviceName|${b64(myPub)}|${b64(theirPub)}"))),
        )
        handler.onPeersChanged()
        handler.onInviteUsed(peer)
        _events.tryEmit("Linked with $peerName (XARVIS Helper). When they tap \"Ask for help\", you'll be told here.")
    }

    /** On XARVIS Helper: tells the helping phone its owner asks for help (it shows Rex a notification). */
    suspend fun askForHelp(peer: Peer, text: String = "") {
        request(peer, "help", JSONObject().put("text", text))
    }

    private fun onWifi(): Boolean = runCatching {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return true
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }.getOrDefault(true)

    private fun exchange(link: LineLink, peer: Peer, body: JSONObject): JSONObject =
        link.use { c ->
            c.send(encryptFrame("req", peer.key, body))
            val res = try {
                c.receive()
            } catch (e: QueuedException) {
                return@use JSONObject().put("ok", true).put("queued", true)
            }
            if (res.optString("t") == "err") throw LinkException(res.optString("msg"))
            JSONObject(String(decrypt(peer.key, res, peer.id), Charsets.UTF_8))
        }

    /** A linked phone without the relay login gets this phone's (sealed by the link) so it can be reached anywhere too. */
    private fun shareRelay(peer: Peer, pong: JSONObject) {
        if (pong.optBoolean("relay") || !pong.has("pong")) return
        val cfg = relay.config() ?: return
        scope.launch {
            runCatching {
                request(peer, "relay_cfg", JSONObject().put("host", "${cfg.host}:${cfg.port}").put("user", cfg.user).put("pass", cfg.pass))
            }.onSuccess { Log.i(TAG, "Shared the relay login with ${peer.name}") }
        }
    }

    /** Sends this phone's relay login to every linked phone that answers (after Rex sets it up here). */
    suspend fun shareRelayNow(): String {
        val cfg = relay.config() ?: return "Set up the relay here first."
        val done = pairedPeers().map { p ->
            p.name to runCatching {
                request(p, "relay_cfg", JSONObject().put("host", "${cfg.host}:${cfg.port}").put("user", cfg.user).put("pass", cfg.pass))
            }.isSuccess
        }
        if (done.isEmpty()) return "No linked phones yet."
        return done.joinToString("\n") { (n, ok) -> if (ok) "✓ $n has it now" else "✗ $n didn't answer (it gets it next time they're in touch)" }
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
        val host = socket.inetAddress?.hostAddress.orEmpty()
        serve(Conn(socket, if (isTailnet(host)) "tailscale" else "wifi"))
    }

    /** One conversation from a linked phone (a socket, or a conversation through the relay). */
    private suspend fun serve(link: LineLink) {
        link.use { c ->
            try {
                val first = c.receive()
                when (first.optString("t")) {
                    "pair1" -> if (helperApp) c.send(error("XARVIS Helper links by invite only")) else servePairing(first, c)
                    "ipair1" -> if (helperApp) c.send(error("not here")) else serveInvite(first, c)
                    "req" -> serveRequest(first, c)?.let { c.send(it) } // null: a stream that answered itself
                    else -> c.send(error("unknown frame"))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Connection from ${c.remoteHost} failed", e)
                runCatching { c.send(error(e.message ?: "error")) }
            }
        }
    }

    private fun servePairing(first: JSONObject, c: LineLink) {
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

    private suspend fun serveRequest(frame: JSONObject, c: LineLink): JSONObject? {
        val from = frame.getString("from")
        val peer = peers[from]
        val pending = pendingIncoming[from]
        val key = peer?.key ?: pending?.key ?: return error("this device isn't linked")
        val req = JSONObject(String(decrypt(key, frame, from), Charsets.UTF_8))
        val op = req.getString("op")
        // A guard alert or a note may have waited at the relay while this phone was offline.
        val maxAge = if (c.route == "relay" && op in DELIVER_LATER) 24 * 3_600_000L else MAX_CLOCK_SKEW_MS
        if (Math.abs(System.currentTimeMillis() - req.getLong("ts")) > maxAge) {
            return error("request too old; check both devices' clocks")
        }
        val reply = JSONObject()

        if (peer == null) {
            // A device that isn't linked yet may only confirm a pairing it started.
            if (op != "confirm" || pending == null || pending.expired) return error("this device isn't linked")
            pendingIncoming.remove(from)
            val host = if (c.remoteIsLoopback) pending.host else c.remoteHost
            peers[from] = Peer(from, pending.name, pending.key, host, req.optInt("port", PORT))
            savePeers()
            relay.setPeers(peers.keys)
            handler.onPeersChanged()
            _events.tryEmit("Linked with ${pending.name}.")
            return encryptFrame("res", pending.key, reply.put("ok", true))
        }

        // A family phone Rex helps may only ask for help (and say hello); on XARVIS Helper, only remote help is answered.
        if (peer.kind == Peer.KIND_HELPER && op !in FROM_HELPER) return error("not allowed from XARVIS Helper")
        if (helperApp && op !in HELPER_ANSWERS) return error("XARVIS Helper doesn't do \"$op\"")

        // Remember its Tailscale address, to reach it later from outside home Wi-Fi.
        req.optString("tailnet").takeIf { it.isNotEmpty() && it != peer.tailnetHost }?.let {
            peer.tailnetHost = it
            savePeers()
        }
        // Follow DHCP address changes (loopback means an adb bridge, whose address is set by hand).
        // Changes arriving over Tailscale aren't home addresses, so they don't replace the Wi-Fi one.
        val senderHost = c.remoteHost
        if (!c.remoteIsLoopback && senderHost != null && !isTailnet(senderHost) &&
            (peer.host != senderHost || peer.port != req.optInt("port", peer.port))
        ) {
            peer.host = senderHost
            peer.port = req.optInt("port", peer.port)
            savePeers()
        }

        when (op) {
            "ping" -> reply.put("name", deviceName).put("llm", handler.llmReady()).put("pong", true).put("relay", relay.config() != null)
                .apply { tailnetAddress()?.let { put("tailnet", it) } }
            // A linked phone shares the relay login (sealed by the link), so this one is reachable anywhere too.
            "relay_cfg" -> {
                val (h, port) = Relay.splitHost(req.getString("host"))
                val cfg = Relay.Config(h, port, req.getString("user"), req.getString("pass"))
                if (relay.config() != cfg) relay.save(cfg)
                reply.put("ok", true)
            }
            // XARVIS Direct: this phone's addresses back, then the line opens straight between the phones.
            "direct" -> answerDirect(req, reply)
            "help" -> {
                handler.helpRequest(peer, req.optString("text"))
                reply.put("ok", true)
            }
            "status" -> reply.put("text", handler.status())
            "battery" -> reply.put("text", handler.battery())
            "gallery" -> {
                val arr = JSONArray()
                handler.galleryPhotos(req.optInt("limit", 8).coerceIn(1, 20)).forEach { (name, jpg) ->
                    arr.put(JSONObject().put("name", name).put("jpg", b64(jpg)))
                }
                reply.put("photos", arr)
            }
            "note" -> {
                handler.onNote(peer.name, req.getString("text"))
                reply.put("ok", true)
            }
            "chat" -> {
                val text = handler.brainChat(peer.id, req.optJSONArray("facts").toStrings(),
                    req.optJSONArray("devices").toStrings(), req.getString("text"), req.optString("history"))
                    ?: return error("$deviceName has no AI model loaded")
                reply.put("text", text)
            }
            "photo" -> {
                val text = handler.brainPhoto(peer.id, req.optJSONArray("facts").toStrings(),
                    unb64(req.getString("image")), req.getString("text"))
                    ?: return error("$deviceName can't look at photos right now (its AI model isn't ready)")
                reply.put("text", text)
            }
            "memory_get" -> reply.put("memory", handler.memorySnapshot())
            "contacts" -> reply.put("lines", JSONArray(handler.findContacts(req.getString("name"))))
            // Failures throw; serve() sends their message back, which the asking phone shows Rex.
            "snap" -> reply.put("jpg", b64(handler.snapPhoto(req.optBoolean("front"), peer.name)))
            "faces" -> reply.put("text", handler.facesInPhoto(unb64(req.getString("image"))))
            "screen" -> {
                val (jpg, w, h) = handler.screenFrame(peer.name, req.optInt("max", 1100).coerceIn(480, 2400))
                reply.put("jpg", b64(jpg)).put("w", w).put("h", h)
            }
            "input" -> reply.put("ok", handler.screenInput(peer.name, req.getJSONObject("cmd")))
            "stream" -> {
                // Remote control: the connection stays open for video and touches until either side closes it.
                c.streaming(STREAM_READ_TIMEOUT_MS)
                req.put("route", c.route) // through the relay: lighter pictures (data)
                val viaRelay = c.route == "relay"
                var started = false
                val ch = Channel(c, peer.key, deviceId, peer.id)
                try {
                    if (viaRelay && !relay.screenAllowed()) throw LinkException(Relay.screenLimitMessage(relay.usedNow()))
                    kotlinx.coroutines.coroutineScope {
                        // Through the relay: end the session before the month's free data runs out.
                        val watch = if (!viaRelay) null else launch {
                            while (!ch.closed) {
                                delay(RELAY_SCREEN_CHECK_MS)
                                if (!relay.screenAllowed()) {
                                    ch.fail(Relay.screenLimitMessage(relay.usedNow()))
                                    ch.close()
                                }
                            }
                        }
                        try {
                            handler.screenStream(peer.name, req, ch) { header ->
                                started = true
                                c.send(encryptFrame("res", peer.key, header))
                            }
                        } finally {
                            watch?.cancel()
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // Say why before closing (remote control off, Hands off, no screenshot…): closing first
                    // left the other phone with only "connection closed".
                    val why = e.message ?: e.javaClass.simpleName
                    if (!started) runCatching { c.send(error(why)) } else ch.fail(why)
                } finally {
                    ch.close()
                }
                return null
            }
            "file" -> reply.put(
                "saved",
                handler.receiveFile(peer.name, req.getString("name"), req.optString("mime"), unb64(req.getString("data"))),
            )
            "clip" -> {
                handler.receiveClip(peer.name, req.getString("text"))
                reply.put("ok", true)
            }
            "act" -> reply.put("text", handler.act(peer.name, req.getString("kind"), req.optString("arg")))
            "guard" -> {
                val action = req.getString("action")
                // A voice clip arrives as base64; decode it here so the handler gets bytes.
                if (action == "play") req.put("audioBytes", unb64(req.getString("audio")))
                val answer = handler.guard(peer.name, action, req)
                answer.keys().forEach { k -> reply.put(k, answer.get(k)) }
            }
            "guard_alert" -> {
                handler.guardAlert(peer.name, req.getString("text"), req.optString("jpg").takeIf { it.isNotEmpty() }?.let { unb64(it) })
                reply.put("ok", true)
            }
            "think" -> reply.put(
                "text",
                handler.brainOneShot(req.getString("system"), req.getString("text"))
                    ?: return error("$deviceName has no AI model loaded"),
            )
            "screen_stop" -> {
                handler.screenStop(peer.name)
                reply.put("ok", true)
            }
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
                o.optString("host").ifEmpty { null }, o.optInt("port", PORT), o.optString("tailnet").ifEmpty { null },
                o.optString("kind").ifEmpty { Peer.KIND_PHONE })
        }
    }.getOrDefault(emptyList())

    private fun savePeers() {
        val arr = JSONArray()
        peers.values.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("key", b64(it.key))
                .put("host", it.host ?: "").put("port", it.port).put("tailnet", it.tailnetHost ?: "").put("kind", it.kind))
        }
        prefs.edit().putString("peers", arr.toString()).apply()
    }

    private fun error(msg: String) = JSONObject().put("t", "err").put("msg", msg)

    /**
     * This phone's Tailscale address (100.64.0.0/10 on a VPN network), or null when Tailscale
     * isn't on. Only VPN networks count: some mobile carriers use the same range themselves.
     */
    private fun tailnetAddress(): String? = runCatching {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return null
        cm.allNetworks.asSequence()
            .filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
            .flatMap { cm.getLinkProperties(it)?.linkAddresses.orEmpty().asSequence() }
            .mapNotNull { it.address.hostAddress }
            .firstOrNull { isTailnet(it) }
    }.getOrNull()

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
        private const val GALLERY_READ_TIMEOUT_MS = 60_000 // sending several downscaled photos takes a moment
        private const val SNAP_READ_TIMEOUT_MS = 90_000 // the other phone may wait up to a minute for Rex to tap its notification
        private const val SCREEN_READ_TIMEOUT_MS = 15_000 // one screen picture or one tap
        private const val STREAM_READ_TIMEOUT_MS = 20_000 // both sides send something at least every 2 s
        private const val RELAY_SCREEN_CHECK_MS = 10_000L // how often a screen through the relay checks the month's data
        private const val FILE_READ_TIMEOUT_MS = 180_000 // a file of up to 25 MB, over Tailscale on mobile data
        private const val PAIRING_TIMEOUT_MS = 5 * 60_000L
        private const val MAX_CLOCK_SKEW_MS = 5 * 60_000L
        /** Through the relay a question and its answer take a round trip via the broker: allow a little longer. */
        private const val RELAY_MIN_TIMEOUT_MS = 8_000
        /** After the home Wi-Fi address didn't answer, don't try it again for this long (straight to the relay). */
        private const val WIFI_RETRY_MS = 60_000L
        /** One-way messages that may wait at the relay for a phone that's offline. */
        private val DELIVER_LATER = setOf("guard_alert", "note")
        /** What goes over XARVIS Direct when the phones aren't on the same Wi-Fi (the rest is small: the relay). */
        private val DIRECT_OPS = setOf("stream", "file")
        /** Swapping addresses through the relay (the other phone asks STUN servers first). */
        private const val DIRECT_SETUP_TIMEOUT_MS = 10_000
        /** How long the asking phone tries to punch through before using the relay. */
        private const val DIRECT_PUNCH_MS = 3_000L
        /** How long the answering phone waits for the asking one to get through. */
        private const val DIRECT_ANSWER_MS = 12_000L
        /** After XARVIS Direct couldn't get through, use the relay straight away for this long. */
        private const val DIRECT_RETRY_MS = 5 * 60_000L
        private const val MAX_DIRECT_LINES = 4
        /** All a family phone (XARVIS Helper) may ask Rex's phone. */
        private val FROM_HELPER = setOf("ping", "help", "confirm")
        /** All XARVIS Helper answers: being seen and helped (with its owner's consent each time). */
        private val HELPER_ANSWERS = setOf("ping", "stream", "direct", "screen_stop", "relay_cfg", "confirm")
        const val INVITE_PREFIX = "XARVIS-INVITE:"
        private const val INVITE_VALID_MS = 7 * 24 * 3_600_000L
        private const val INVITE_TIMEOUT_MS = 20_000
        /** Marks a relay conversation the other side ended. */
        private val CLOSED = String(charArrayOf('\u0000', 'x'))
        private const val NETWORK_SETTLE_MS = 3_000L
        private val ADDRESS = Regex("""^(\d{1,3}(?:\.\d{1,3}){3})(?::(\d{1,5}))?$""")

        private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

        private fun hmac(key: ByteArray, text: String): ByteArray =
            javax.crypto.Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(text.toByteArray())
            }

        /** The invite inside [text] (a whole WhatsApp message is fine), or null. */
        fun parseInvite(text: String): JSONObject? {
            val at = text.indexOf(INVITE_PREFIX)
            if (at < 0) return null
            val code = text.substring(at + INVITE_PREFIX.length).takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
            return runCatching {
                JSONObject(String(Base64.getUrlDecoder().decode(code))).takeIf { o ->
                    listOf("r", "u", "p", "d", "i", "s").all { o.has(it) }
                }
            }.getOrNull()
        }
        private fun b64(data: ByteArray): String = Base64.getEncoder().encodeToString(data)
        private fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)

        /** Tailscale gives each device an address in 100.64.0.0/10. */
        fun isTailnet(address: String): Boolean {
            val parts = address.split('.').mapNotNull { it.toIntOrNull() }
            return parts.size == 4 && parts[0] == 100 && parts[1] in 64..127
        }

        /** "my benco mobile" -> "benco". */
        fun normalize(query: String): String = query.lowercase().trim()
            .removePrefix("my ").removePrefix("the ")
            .removeSuffix(" phone").removeSuffix(" mobile").removeSuffix(" device").trim()

        fun shortName(name: String): String = name.split(' ').firstOrNull { it.length > 2 }?.lowercase() ?: name

        /** Whether [query] ("s22 ultra", "my benco") means the phone called [name]. */
        fun sameDevice(query: String, name: String): Boolean {
            val words = normalize(query).split(' ').filter { it.isNotBlank() }
            return words.isNotEmpty() && words.all { name.lowercase().contains(it) }
        }

        private val GENERIC = setOf("galaxy", "phone", "mobile", "plus", "pro", "max", "mini", "lite", "the", "my")

        /**
         * The phone Rex's [message] is about, among [names] (this phone's and the linked ones), or
         * null if it names none: "what is s22 ultra battery status" -> "Galaxy S22 Ultra".
         */
        fun namedIn(message: String, names: List<String>): String? {
            val said = message.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).toSet()
            return names.firstOrNull { name ->
                name.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).any { it.length >= 3 && it !in GENERIC && it in said }
            }
        }
    }
}
