package com.xarvis.ai.memory

import android.util.Log
import com.xarvis.ai.net.DeviceLink
import com.xarvis.ai.net.Peer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps remembered facts the same on every linked device.
 *
 * New facts are pushed to linked devices immediately, and devices that were offline catch up
 * by pulling each other's facts ([syncAll]). "forget everything" is recorded as a timestamp
 * rather than just deleting, so a device that missed the wipe can't bring old facts back:
 * anything older than the latest wipe on any device is dropped.
 */
class MemorySync(
    private val memory: MemorySystem,
    private val link: DeviceLink,
    private val onChanged: suspend () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val merging = Mutex()

    /** Stores [fact] here and on linked devices; false if it was already known. */
    suspend fun remember(fact: String): Boolean {
        if (memory.hasFact(fact)) return false
        val now = System.currentTimeMillis()
        memory.rememberFact(fact, now)
        broadcast { link.pushFact(it, fact, now) }
        return true
    }

    suspend fun forgetEverything() {
        val now = System.currentTimeMillis()
        memory.clear()
        memory.factsClearedAt = now
        broadcast { link.pushClear(it, now) }
    }

    /** Pulls every reachable linked device's facts and merges them in. */
    suspend fun syncAll() {
        var changed = false
        for (peer in link.pairedPeers()) {
            val snapshot = runCatching { link.pullMemory(peer) }.getOrElse {
                Log.i(TAG, "Couldn't sync with ${peer.name}: ${it.message}")
                continue
            }
            if (merge(snapshot)) changed = true
        }
        if (changed) onChanged()
    }

    // ---- Called by DeviceLink for requests from linked devices ----

    suspend fun snapshot(): JSONObject {
        val facts = JSONArray()
        memory.allFacts().forEach { facts.put(JSONObject().put("c", it.content).put("ts", it.timestamp)) }
        return JSONObject().put("facts", facts).put("cleared", memory.factsClearedAt)
    }

    suspend fun receiveFact(content: String, timestamp: Long) {
        if (merging.withLock { addIfNew(content, timestamp) }) onChanged()
    }

    suspend fun receiveClear(timestamp: Long) {
        if (merging.withLock { applyClear(timestamp) }) onChanged()
    }

    // ---- Merge rules ----

    private suspend fun merge(snapshot: JSONObject): Boolean = merging.withLock {
        var changed = applyClear(snapshot.optLong("cleared", 0L))
        val facts = snapshot.optJSONArray("facts") ?: JSONArray()
        for (i in 0 until facts.length()) {
            val f = facts.getJSONObject(i)
            if (addIfNew(f.getString("c"), f.getLong("ts"))) changed = true
        }
        changed
    }

    private suspend fun addIfNew(content: String, timestamp: Long): Boolean {
        if (timestamp <= memory.factsClearedAt || memory.hasFact(content)) return false
        memory.rememberFact(content, timestamp)
        return true
    }

    private suspend fun applyClear(timestamp: Long): Boolean {
        if (timestamp <= memory.factsClearedAt) return false
        memory.clearFactsBefore(timestamp)
        memory.factsClearedAt = timestamp
        return true
    }

    private fun broadcast(block: suspend (Peer) -> Unit) {
        link.pairedPeers().forEach { peer ->
            scope.launch {
                runCatching { block(peer) }.onFailure { Log.i(TAG, "${peer.name} will catch up later: ${it.message}") }
            }
        }
    }

    private companion object {
        const val TAG = "XarvisMemorySync"
    }
}
