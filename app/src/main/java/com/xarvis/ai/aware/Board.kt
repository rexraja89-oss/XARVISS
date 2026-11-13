package com.xarvis.ai.aware

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.xarvis.ai.BuildConfig
import com.xarvis.ai.R
import com.xarvis.ai.hands.HandsBridge
import com.xarvis.ai.net.DeviceLink
import com.xarvis.ai.net.Peer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * The shared picture of all Rex's devices ([Situation]): this phone's status card, the latest card from each linked
 * phone, and the watchers. Cards travel inside the existing link op `task` (action "state": mine in the request,
 * theirs in the answer), so the shared link code — and XARVIS Remote, which is built from it — stays unchanged.
 * They're swapped every [HEARTBEAT_MS] and whenever something here changes (unlocked, charger, battery step, network,
 * a missed call). Nothing new is asked of Android: battery, network, screen, the last location XARVIS already has and,
 * on the S22, the notifications its XARVIS Hands service already sees.
 */
class Board(
    context: Context,
    private val link: DeviceLink,
    private val scope: CoroutineScope,
    private val post: (String) -> Unit,
    /** "Gemma ready", "" … */
    private val brain: () -> String,
    /** What XARVIS is busy with here (task, guard, night shift, remote control…). */
    private val doing: () -> List<String>,
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("aware", Context.MODE_PRIVATE)
    private val lock = Any()
    private val labels = HashMap<String, String>()

    @Volatile private var usedAt = prefs.getLong("usedAt", 0)
    @Volatile private var unlockedAt = prefs.getLong("unlockedAt", 0)
    @Volatile private var inUse = false
    @Volatile private var lastPush = 0L
    @Volatile private var lastBucket = -1
    private var pushJob: Job? = null

    /** The S22 shares its notifications (masked codes, no banking apps) with Rex's other phones. */
    var shareNotes: Boolean
        get() = prefs.getBoolean("shareNotes", true)
        set(v) {
            prefs.edit().putBoolean("shareNotes", v).apply()
            if (!v) prefs.edit().remove("notes").apply()
            push("settings", urgent = true)
        }

    /** XARVIS speaks up by itself (low battery, unlocks, missed calls, lost contact). */
    var alertsOn: Boolean
        get() = prefs.getBoolean("alerts", true)
        set(v) = prefs.edit().putBoolean("alerts", v).apply()

    fun start() {
        instance = this
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        runCatching { ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
        runCatching {
            app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = push("network")
                override fun onLost(network: Network) = push("network")
            })
        }
        HandsBridge.notification = { pkg, title, text -> noteNotification(pkg, title, text) }
        scope.launch {
            delay(45_000)
            while (true) {
                runCatching { exchangeAll() }
                runCatching { checkLost() }
                delay(HEARTBEAT_MS)
            }
        }
    }

    // ---- this phone ----

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val now = System.currentTimeMillis()
            when (i.action) {
                Intent.ACTION_USER_PRESENT -> {
                    usedAt = now
                    unlockedAt = now
                    inUse = true
                    savePresence()
                    push("unlocked", urgent = true)
                }
                Intent.ACTION_SCREEN_ON -> if (app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false) {
                    usedAt = now
                    inUse = true
                    savePresence()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    if (inUse) usedAt = now
                    inUse = false
                    savePresence()
                    push("screen off")
                }
                Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED -> push("power", urgent = true)
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    if (level >= 0 && scale > 0) {
                        val bucket = level * 100 / scale / 5
                        if (lastBucket >= 0 && bucket != lastBucket) push("battery")
                        lastBucket = bucket
                    }
                }
            }
        }
    }

    private fun savePresence() = prefs.edit().putLong("usedAt", usedAt).putLong("unlockedAt", unlockedAt).apply()

    /** This phone's card right now. */
    fun myCard(): Situation.Card {
        val now = System.currentTimeMillis()
        val bat = runCatching { app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val level = bat?.let {
            val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l >= 0 && s > 0) l * 100 / s else -1
        } ?: -1
        val status = bat?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL ||
            (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val temp = bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.div(10.0)
        val screenOn = app.getSystemService(PowerManager::class.java)?.isInteractive == true
        val locked = app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false
        val front = HandsBridge.front?.takeIf { screenOn && !locked && now - HandsBridge.frontAt < 30 * 60_000L }?.let { pkg ->
            if (pkg.contains("launcher", ignoreCase = true)) "home screen" else label(pkg)
        }.orEmpty()
        val free = runCatching { StatFs(app.filesDir.path).availableBytes / 1e9 }.getOrNull()
        val loc = prefs.getString("loc", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        return Situation.Card(
            id = link.deviceId, name = link.deviceName, at = now, app = BuildConfig.VERSION_NAME,
            battery = level, charging = charging, tempC = temp, net = network(), screenOn = screenOn, locked = locked,
            usedAt = if (screenOn && !locked) now else usedAt, unlockedAt = unlockedAt, front = front,
            lat = loc?.optDouble("la"), lon = loc?.optDouble("lo"), locAt = loc?.optLong("at") ?: 0,
            brain = runCatching { brain() }.getOrDefault(""), doing = runCatching { doing() }.getOrDefault(emptyList()),
            freeGb = free?.let { Math.round(it * 10) / 10.0 }, notes = if (shareNotes) notes() else emptyList(),
        )
    }

    private fun network(): String {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return ""
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return "none"
        fun kind(c: NetworkCapabilities): String? = when {
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "wifi"
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            else -> null
        }
        kind(caps)?.let { return it }
        // A VPN (Proton) in front: the network under it.
        @Suppress("DEPRECATION")
        return cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }
            .filter { !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
            .firstNotNullOfOrNull { kind(it) } ?: ""
    }

    private fun label(pkg: String): String = synchronized(labels) {
        labels.getOrPut(pkg) {
            runCatching { app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        }
    }

    /** The last place this phone knew it was (never asks for a new fix: no battery, no new permission). */
    private suspend fun updateLocation() {
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED }
        if (!granted) return
        val loc = withTimeoutOrNull(3_000) {
            runCatching {
                suspendCancellableCoroutine<android.location.Location?> { cont ->
                    @Suppress("MissingPermission")
                    com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(app).lastLocation
                        .addOnCompleteListener { t -> if (cont.isActive) cont.resume(if (t.isSuccessful) t.result else null) }
                }
            }.getOrNull()
        } ?: return
        val old = prefs.getString("loc", null)?.let { runCatching { JSONObject(it).optLong("at") }.getOrNull() } ?: 0
        if (loc.time > old) {
            prefs.edit().putString("loc", JSONObject().put("la", loc.latitude).put("lo", loc.longitude).put("at", loc.time).toString()).apply()
        }
    }

    // ---- notifications (the S22, through XARVIS Hands) ----

    private fun notes(): List<Situation.Note> {
        val now = System.currentTimeMillis()
        val a = prefs.getString("notes", null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Situation.Note::from) }
            .filter { now - it.at <= Situation.NOTES_FOR_MS }
    }

    private fun noteNotification(pkg: String, title: String, text: String) {
        if (!shareNotes) return
        if (pkg == "android" || pkg.startsWith("com.xarvis.") || pkg.startsWith("com.android.systemui")) return
        val name = label(pkg)
        // Banking and payment apps stay out of it entirely.
        if (com.xarvis.ai.agent.PhoneAgent.Safety.moneyApp(name) || com.xarvis.ai.agent.PhoneAgent.Safety.moneyApp(pkg)) return
        val n = Situation.note(name, title, text, System.currentTimeMillis()) ?: return
        synchronized(lock) {
            val list = Situation.addNote(notes(), n)
            prefs.edit().putString("notes", JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()).apply()
        }
        if (n.missedCall) push("missed call", urgent = true)
    }

    // ---- the other devices ----

    /** The latest card from each linked phone. */
    fun others(): List<Situation.Card> {
        val ids = link.pairedPeers().map { it.id }.toSet()
        val o = prefs.getString("cards", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyList()
        return o.keys().asSequence().filter { it in ids }.mapNotNull { k -> o.optJSONObject(k)?.let(Situation.Card::from) }.toList()
    }

    private fun store(card: Situation.Card): Situation.Card? = synchronized(lock) {
        val o = prefs.getString("cards", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val prev = o.optJSONObject(card.id)?.let(Situation.Card::from)
        o.put(card.id, card.toJson())
        prefs.edit().putString("cards", o.toString()).apply()
        prev
    }

    /** A card from [peer] (the link vouches for who sent it, so its id and name are taken from the pairing). */
    private fun received(peer: Peer, json: JSONObject, me: Situation.Card) {
        val card = runCatching { Situation.Card.from(json) }.getOrNull()?.copy(id = peer.id, name = peer.name) ?: return
        val prev = store(card)
        val now = System.currentTimeMillis()
        val out = ArrayList<String>()
        if (card.id in lost()) {
            setLost(card.id, false)
            out += Situation.backLine(card)
        }
        out += Situation.alerts(me, prev, card, now)
        out.forEach(::alert)
    }

    /** The other phone sent its card (link op `task`, action "state"): keep it, answer with this phone's. */
    fun answer(peer: Peer, card: JSONObject?): String {
        val me = myCard()
        card?.let { received(peer, it, me) }
        scope.launch { runCatching { updateLocation() } }
        return me.toJson().toString()
    }

    /** Swaps cards with every linked phone now; how many answered. */
    suspend fun exchangeAll(): Int {
        lastPush = System.currentTimeMillis()
        runCatching { updateLocation() }
        val me = myCard()
        val body = JSONObject().put("action", "state").put("card", me.toJson())
        val ok = AtomicInteger()
        coroutineScope {
            link.pairedPeers().map { p ->
                async(Dispatchers.IO) {
                    // An older XARVIS answers "unknown task request": it just has no card yet.
                    val text = withTimeoutOrNull(EXCHANGE_TIMEOUT_MS) { runCatching { link.remoteTask(p, body) }.getOrNull() }
                    val json = text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@async
                    received(p, json, me)
                    ok.incrementAndGet()
                    Unit
                }
            }.awaitAll()
        }
        return ok.get()
    }

    /** Asks the others for fresh cards (for "status report"), at most ~12 s. */
    suspend fun refresh() {
        withTimeoutOrNull(EXCHANGE_TIMEOUT_MS + 2_000) { exchangeAll() }
    }

    /** Fresh cards unless the ones here are recent. */
    suspend fun refreshIfOld(maxAgeMs: Long = 3 * 60_000L) {
        val now = System.currentTimeMillis()
        if (others().isEmpty() || others().any { now - it.at > maxAgeMs }) refresh()
    }

    /** Sends this phone's card soon ([urgent]: now; else after a short pause, at most every [MIN_GAP_MS]). */
    fun push(reason: String, urgent: Boolean = false) {
        synchronized(lock) {
            if (!urgent && pushJob?.isActive == true) return
            pushJob?.cancel()
            val wait = if (urgent) 1_000L else maxOf(5_000L, MIN_GAP_MS - (System.currentTimeMillis() - lastPush))
            pushJob = scope.launch {
                delay(wait)
                runCatching { exchangeAll() }
            }
        }
    }

    private fun lost(): Set<String> = prefs.getStringSet("lost", emptySet()) ?: emptySet()

    private fun setLost(id: String, on: Boolean) = synchronized(lock) {
        prefs.edit().putStringSet("lost", lost().toMutableSet().apply { if (on) add(id) else remove(id) }).apply()
    }

    private fun checkLost() {
        if (network() == "none") return
        val now = System.currentTimeMillis()
        val lost = lost()
        for (c in others()) {
            if (now - c.at > Situation.LOST_MS && c.id !in lost) {
                setLost(c.id, true)
                alert(Situation.lostLine(c, now))
            }
        }
    }

    // ---- what Rex sees ----

    fun report(): String = Situation.report(myCard(), others(), System.currentTimeMillis())

    fun brief(): String = Situation.brief(myCard(), others(), System.currentTimeMillis())

    /** "What's new on my S22" / "any missed calls": null when [where] doesn't name one of Rex's devices. */
    fun notesText(where: String?, callsOnly: Boolean): String? {
        val now = System.currentTimeMillis()
        val me = myCard()
        val all = others() + me
        val cards = if (where == null) others().ifEmpty { listOf(me) } else {
            val name = DeviceLink.namedIn(where, all.map { it.name }) ?: return null
            all.filter { it.name == name }
        }
        return cards.joinToString("\n\n") { c ->
            if (callsOnly) {
                val calls = c.notes.filter { it.missedCall }
                if (calls.isEmpty()) "📞 No missed calls on the ${c.name}." else "📞 Missed calls on the ${c.name}:\n" +
                    calls.joinToString("\n") { "• ${Situation.clock(it.at)} ${it.title.ifBlank { it.app }}" }
            } else {
                Situation.notesReport(c, now)
            }
        }
    }

    /** The last alerts, newest first ("time · text"). */
    fun recentAlerts(): List<String> {
        val a = prefs.getString("alertLog", null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { "${Situation.clock(it.optLong("at"))} · ${it.optString("t")}" }
    }

    private fun alert(text: String) {
        synchronized(lock) {
            val old = prefs.getString("alertLog", null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
            val log = JSONArray().put(JSONObject().put("at", System.currentTimeMillis()).put("t", text))
            for (i in 0 until minOf(old.length(), 19)) log.put(old.get(i))
            prefs.edit().putString("alertLog", log.toString()).apply()
        }
        if (!alertsOn) return
        post(text)
        runCatching {
            val nm = app.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "XARVIS awareness", NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(
                app, 7401, Intent(app, BoardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            nm.notify(
                NOTIFY_BASE + (text.hashCode() and 0xff),
                NotificationCompat.Builder(app, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_xarvis)
                    .setContentTitle("🛰 XARVIS")
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build(),
            )
        }
    }

    companion object {
        /** The running board (XarvisCore makes it); null until XARVIS has started. */
        @Volatile var instance: Board? = null

        const val HEARTBEAT_MS = 15 * 60_000L
        const val MIN_GAP_MS = 2 * 60_000L
        private const val EXCHANGE_TIMEOUT_MS = 10_000L
        private const val CHANNEL = "xarvis_aware"
        private const val NOTIFY_BASE = 74_000
    }
}
