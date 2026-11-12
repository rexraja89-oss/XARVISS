package com.xarvis.ai.agent

import android.content.Context
import com.xarvis.ai.hands.ExternalHands
import com.xarvis.ai.hands.HandsBridge
import com.xarvis.ai.net.DeviceLink
import com.xarvis.ai.net.Peer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

/** Hands' screen listing (JSON {"app","w","h","els":[…]}) as numbered elements for the brain. */
internal fun screenFrom(json: String): PhoneAgent.Screen {
    val o = runCatching { JSONObject(json) }.getOrElse { return PhoneAgent.Screen("", emptyList(), 0, 0) }
    val arr = o.optJSONArray("els")
    val elements = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
        val e = arr?.optJSONObject(i) ?: return@mapNotNull null
        PhoneAgent.Element(
            n = i + 1, label = e.optString("t"), tappable = e.optBoolean("c"), editable = e.optBoolean("e"),
            password = e.optBoolean("p"), x = e.optInt("x"), y = e.optInt("y"), id = e.optString("i"),
        )
    }
    return PhoneAgent.Screen(o.optString("app"), elements, o.optInt("w"), o.optInt("h"))
}

/** This phone, through its own Hands: built into XARVIS on the S22, the separate Hands app on the benco. */
class LocalPhone(private val context: Context, override val name: String) : PhoneAgent.Target {
    @Volatile private var stop = false
    private var width = 0
    private var height = 0

    private fun hands(): HandsBridge.Hands = HandsBridge.service ?: ExternalHands.get(context)
        ?: throw IllegalStateException("XARVIS Hands is off on this phone — switch it on in ☰ → XARVIS Hands")

    fun available(): Boolean = HandsBridge.service != null || ExternalHands.get(context) != null

    override suspend fun begin() = withContext(Dispatchers.IO) {
        val h = hands()
        h.controlBanner("XARVIS") {
            stop = true
            h.controlBanner(null) {}
        }
    }

    override suspend fun end() = withContext(Dispatchers.IO) { runCatching { hands().controlBanner(null) {} }.let { } }

    override suspend fun screen(): PhoneAgent.Screen = withContext(Dispatchers.IO) {
        screenFrom(hands().screenElements()).also { width = it.width; height = it.height }
    }

    override suspend fun tap(x: Int, y: Int) = withContext(Dispatchers.IO) {
        hands().gesture("tap", x.toFloat(), y.toFloat(), x.toFloat(), y.toFloat(), 60).let { }
    }

    override suspend fun type(text: String): Boolean = withContext(Dispatchers.IO) { hands().typeText(text, false) }

    override suspend fun scroll(down: Boolean) = withContext(Dispatchers.IO) {
        val w = width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val h = height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
        hands().gesture("swipe", w / 2f, h * if (down) 0.72f else 0.30f, w / 2f, h * if (down) 0.30f else 0.72f, 350).let { }
    }

    override suspend fun key(name: String) = withContext(Dispatchers.IO) { hands().globalAction(name).let { } }

    override suspend fun open(app: String): String = withContext(Dispatchers.IO) {
        val found = com.xarvis.ai.device.DeviceCapabilityManager(context).findApp(app)
            ?: return@withContext "there's no app called \"$app\" on this phone"
        if (hands().openApp(found.packageName)) "opened ${found.label}" else "couldn't open ${found.label}"
    }

    override suspend fun stopped(): Boolean = stop

    /** A yes/no question on top of whatever app is open (Rex may be looking at WhatsApp, not XARVIS). */
    suspend fun ask(question: String): Boolean = suspendCancellableCoroutine { cont ->
        hands().ask(question) { yes -> if (cont.isActive) cont.resume(yes) }
    }
}

/** The linked phone (the S22, asked on the benco), through its XARVIS over the link. Needs its remote-control switch on. */
class LinkedPhone(private val link: DeviceLink, private val peer: Peer) : PhoneAgent.Target {
    override val name: String = peer.name

    override suspend fun begin() {
        link.remoteAct(peer, "begin")
    }

    override suspend fun end() {
        runCatching { link.remoteAct(peer, "end") }
    }

    override suspend fun screen(): PhoneAgent.Screen = screenFrom(link.remoteAct(peer, "elements"))

    override suspend fun tap(x: Int, y: Int) {
        link.remoteAct(peer, "tapxy", "$x,$y")
    }

    override suspend fun type(text: String): Boolean = link.remoteAct(peer, "type", text).startsWith("Typed")

    override suspend fun scroll(down: Boolean) {
        link.remoteAct(peer, "swipe", if (down) "down" else "up")
    }

    override suspend fun key(name: String) {
        link.remoteAct(peer, "key", name)
    }

    override suspend fun open(app: String): String = link.remoteAct(peer, "open", app)

    // STOP on its banner makes its next answer an error ("STOP was pressed on it"), which ends the task.
    override suspend fun stopped(): Boolean = false
}
