package com.xarvis.helper

import android.app.Application
import android.content.Context
import com.xarvis.ai.net.DeviceLink
import com.xarvis.ai.net.LinkException
import com.xarvis.ai.net.Peer
import org.json.JSONObject

/**
 * XARVIS Helper: a tiny app for a family phone. It holds one link (the same encrypted link XARVIS phones use, through
 * Rex's relay and XARVIS Direct) and answers only "may I see / help?" — never memories, files, the camera or
 * anything else XARVIS phones share among themselves.
 */
class HelperApp : Application() {
    lateinit var link: DeviceLink
        private set

    override fun onCreate() {
        super.onCreate()
        link = DeviceLink(this, HelperHandler(this), helperApp = true)
        link.start()
    }

    companion object {
        fun link(context: Context): DeviceLink = (context.applicationContext as HelperApp).link
    }
}

/** What a helping phone may ask this one: only to see and help (DeviceLink refuses the rest before it gets here). */
private class HelperHandler(private val app: Context) : DeviceLink.Handler {
    private fun no(): Nothing = throw LinkException("XARVIS Helper only shares its screen, when its owner asks for help")

    override suspend fun status() = "XARVIS Helper"
    override suspend fun battery(): String = no()
    override suspend fun galleryPhotos(limit: Int): List<Pair<String, ByteArray>> = no()
    override suspend fun brainChat(peerId: String, facts: List<String>, devices: List<String>, text: String, history: String): String? = null
    override suspend fun brainPhoto(peerId: String, facts: List<String>, jpeg: ByteArray, text: String): String? = null
    override fun llmReady() = false
    override fun onNote(from: String, text: String) {}
    override fun onPeersChanged() {}
    override suspend fun memorySnapshot() = JSONObject()
    override suspend fun memoryAdd(content: String, timestamp: Long) {}
    override suspend fun memoryClear(timestamp: Long) {}
    override suspend fun findContacts(name: String): List<String> = emptyList()
    override suspend fun snapPhoto(front: Boolean, from: String): ByteArray = no()
    override suspend fun facesInPhoto(jpeg: ByteArray): String = no()
    override suspend fun screenFrame(from: String, maxSide: Int): Triple<ByteArray, Int, Int> = no()
    override suspend fun screenInput(from: String, cmd: JSONObject): Boolean = no()
    override fun screenStop(from: String) {}
    override suspend fun screenStream(from: String, req: JSONObject, channel: DeviceLink.Channel, accept: (JSONObject) -> Unit) =
        HelperSession.stream(app, from, req, channel, accept)
    override suspend fun receiveFile(from: String, name: String, mime: String, bytes: ByteArray): String = no()
    override suspend fun receiveClip(from: String, text: String) {}
    override suspend fun act(from: String, kind: String, arg: String): String = no()
    override suspend fun brainOneShot(system: String, text: String): String? = null
    override suspend fun guard(from: String, action: String, req: JSONObject): JSONObject = no()
    override suspend fun guardAlert(from: String, text: String, jpeg: ByteArray?) {}
    override suspend fun helpRequest(peer: Peer, text: String) {}
}
