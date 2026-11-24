package com.xarvis.remote

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import com.xarvis.ai.net.DeviceLink
import com.xarvis.ai.net.LinkException
import com.xarvis.ai.net.Peer
import org.json.JSONObject

/**
 * XARVIS Remote: a separate remote-control app for Rex's own phones (Nov 2026: "a TeamViewer-type secondary app I
 * can use in an emergency when XARVIS on the S22 is down or needs an upgrade"). It has its own link (relay +
 * XARVIS Direct, its own identity and keys), so nothing in XARVIS has to work for it to work.
 *
 * One app, two roles: on the benco it controls ("Control a phone"), on the S22 it is controlled — with
 * "Let my linked phones control this phone" on, no one has to be at the S22 to allow it.
 */
class RemoteApp : Application() {
    lateinit var link: DeviceLink
        private set

    /**
     * Every setting (identity, pairing keys, relay login, "hosting") lives in device storage, which Android opens
     * at boot before the pattern (Direct Boot), so XARVIS Remote works right after a restart. The link code asks the
     * application context for its prefs, so redirecting them here is enough. Moved over once, while unlocked.
     */
    @Volatile private var device: Context? = null

    private fun storage(): Context = device ?: synchronized(this) {
        device ?: createDeviceProtectedStorageContext().also { dp ->
            val flags = dp.getSharedPreferences(STORAGE_FLAGS, MODE_PRIVATE)
            if (!flags.getBoolean(MOVED, false) && getSystemService(UserManager::class.java)?.isUserUnlocked != false) {
                MOVED_PREFS.forEach { runCatching { dp.moveSharedPreferencesFrom(baseContext, it) } }
                flags.edit().putBoolean(MOVED, true).commit()
            }
            device = dp
        }
    }

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        storage().getSharedPreferences(name, mode)

    override fun onCreate() {
        super.onCreate()
        com.xarvis.ai.view.BarInsets.install(this) // Android 15+: screens clear of the status and navigation bars
        link = DeviceLink(this, RemoteHandler(this), helperApp = true, remoteApp = true)
        link.start()
        if (RemotePrefs.hosting(this)) RemoteService.start(this)
    }

    companion object {
        fun link(context: Context): DeviceLink = (context.applicationContext as RemoteApp).link

        private const val STORAGE_FLAGS = "storage"
        private const val MOVED = "moved_to_device_storage"
        /** Prefs XARVIS Remote and its link code use: link (identity, peers, keys), relay, brainkeys (relay login), remote. */
        private val MOVED_PREFS = listOf("link", "relay", "brainkeys", "remote")
    }
}

/** This app's few settings. */
object RemotePrefs {
    private const val PREFS = "remote"
    private const val HOSTING = "hosting"

    /** "Let my linked phones control this phone": on for the S22 (off by default). */
    fun hosting(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(HOSTING, false)

    fun setHosting(context: Context, on: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(HOSTING, on).apply()
        if (on) {
            RemoteService.start(context)
        } else {
            RemoteHost.endAll(context)
            RemoteService.stop(context)
        }
    }
}

/**
 * What a linked phone may ask this one: only to see and control it (DeviceLink refuses everything else before it
 * gets here — no memories, files, camera or AI, which stay XARVIS's).
 */
private class RemoteHandler(private val app: Context) : DeviceLink.Handler {
    private fun no(): Nothing = throw LinkException("XARVIS Remote only shares its screen with your linked phones")

    override suspend fun status() = "XARVIS Remote"
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
    override fun screenStop(from: String) = RemoteHost.stop(app, from)
    override suspend fun screenStream(from: String, req: JSONObject, channel: DeviceLink.Channel, accept: (JSONObject) -> Unit) =
        RemoteHost.stream(app, from, req, channel, accept)
    override suspend fun receiveFile(from: String, name: String, mime: String, bytes: ByteArray): String = no()
    override suspend fun receiveClip(from: String, text: String) {}
    override suspend fun act(from: String, kind: String, arg: String): String = no()
    override suspend fun brainOneShot(system: String, text: String): String? = null
    override suspend fun guard(from: String, action: String, req: JSONObject): JSONObject = no()
    override suspend fun guardAlert(from: String, text: String, jpeg: ByteArray?) {}
    /** The phone this one controls restarted and waits for the pattern (RestartNews): tap → its screen. */
    override suspend fun helpRequest(peer: Peer, text: String) = RestartNews.show(app, peer, text)
}
