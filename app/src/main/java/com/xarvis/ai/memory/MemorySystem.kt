package com.xarvis.ai.memory

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import org.json.JSONObject

@Entity(tableName = "memories")
data class MemoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
)

@Dao
interface MemoryDao {
    @Insert
    suspend fun insert(entry: MemoryEntry): Long

    @Query("SELECT * FROM memories WHERE category = :category ORDER BY timestamp DESC LIMIT :limit")
    suspend fun byCategory(category: String, limit: Int): List<MemoryEntry>

    @Query(
        "SELECT * FROM memories WHERE category = :category AND content LIKE '%' || :query || '%' " +
            "ORDER BY timestamp DESC LIMIT :limit"
    )
    suspend fun search(category: String, query: String, limit: Int): List<MemoryEntry>

    @Query("SELECT * FROM memories WHERE category = :category ORDER BY timestamp DESC")
    suspend fun allByCategory(category: String): List<MemoryEntry>

    @Query("DELETE FROM memories WHERE category = :category AND timestamp < :before")
    suspend fun deleteOlder(category: String, before: Long)

    @Query("SELECT COUNT(*) FROM memories")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM memories WHERE category = :category")
    suspend fun countOf(category: String): Int

    @Query("DELETE FROM memories")
    suspend fun clear()
}

@Database(entities = [MemoryEntry::class], version = 1, exportSchema = false)
abstract class XarvisDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
}

/** One message from Rex and XARVIS's reply, saved at [time] in chat [chat]. */
data class Exchange(val user: String, val reply: String, val time: Long, val chat: String = "")

/** One chat in the chat list: its first message is its title. */
data class ChatSummary(val id: String, val title: String, val lastTime: Long, val exchanges: Int)

/**
 * An exchange from the log: JSON now, "command => response" in older entries. Entries saved
 * before chats existed are grouped by day.
 */
internal fun exchange(e: MemoryEntry): Exchange? {
    val c = e.content
    val day = "day-" + java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).format(java.util.Date(e.timestamp))
    if (c.startsWith("{")) return runCatching {
        JSONObject(c).let { Exchange(it.getString("u"), it.getString("x"), e.timestamp, it.optString("c").ifEmpty { day }) }
    }.getOrNull()
    val i = c.indexOf(" => ")
    return if (i < 0) null else Exchange(c.substring(0, i), c.substring(i + 4), e.timestamp, day)
}

/** Chats from their exchanges, most recently used first. */
internal fun chatsOf(exchanges: List<Exchange>): List<ChatSummary> =
    exchanges.groupBy { it.chat }.map { (id, list) ->
        val sorted = list.sortedBy { it.time }
        ChatSummary(id, sorted.first().user.replace('\n', ' ').take(60), sorted.last().time, sorted.size)
    }.sortedByDescending { it.lastTime }

/** Persistent on-device memory. Survives app restarts; nothing leaves the device. */
class MemorySystem(context: Context) {

    private val dao = Room.databaseBuilder(
        context.applicationContext,
        XarvisDatabase::class.java,
        DATABASE_NAME,
    ).build().memoryDao()

    private val prefs = context.applicationContext.getSharedPreferences("memory", Context.MODE_PRIVATE)

    /** When "forget everything" last ran on any linked device; older facts from other devices are ignored. */
    var factsClearedAt: Long
        get() = prefs.getLong("factsClearedAt", 0L)
        set(value) = prefs.edit().putLong("factsClearedAt", value).apply()

    suspend fun rememberFact(fact: String, timestamp: Long = System.currentTimeMillis()) {
        dao.insert(MemoryEntry(category = CATEGORY_FACT, content = fact, timestamp = timestamp))
    }

    suspend fun hasFact(fact: String): Boolean =
        dao.search(CATEGORY_FACT, fact, 50).any { it.content.equals(fact, ignoreCase = true) }

    suspend fun allFacts(): List<MemoryEntry> = dao.allByCategory(CATEGORY_FACT)

    suspend fun clearFactsBefore(timestamp: Long) = dao.deleteOlder(CATEGORY_FACT, timestamp)

    suspend fun recallFacts(query: String? = null, limit: Int = 10): List<MemoryEntry> =
        if (query.isNullOrBlank()) dao.byCategory(CATEGORY_FACT, limit)
        else dao.search(CATEGORY_FACT, query, limit)

    /** Saves one exchange of the chat, so XARVIS remembers conversations after it restarts. */
    suspend fun logInteraction(command: String, response: String, chat: String = "") {
        val json = JSONObject().put("u", command).put("x", response).put("c", chat).toString()
        dao.insert(MemoryEntry(category = CATEGORY_INTERACTION, content = json))
    }

    /** The last [limit] exchanges, oldest first. */
    suspend fun recentExchanges(limit: Int): List<Exchange> =
        dao.byCategory(CATEGORY_INTERACTION, limit).mapNotNull(::exchange).reversed()

    /** Earlier exchanges containing every word of [query] (newest [limit] of them), oldest first. */
    suspend fun searchExchanges(query: String, limit: Int): List<Exchange> {
        val words = query.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length > 2 }
        val key = words.maxByOrNull { it.length } ?: return recentExchanges(limit)
        return dao.search(CATEGORY_INTERACTION, key, 500).mapNotNull(::exchange)
            .filter { e -> words.all { w -> e.user.contains(w, true) || e.reply.contains(w, true) } }
            .take(limit).reversed()
    }

    /** Every chat, most recently used first. */
    suspend fun chats(): List<ChatSummary> = chatsOf(dao.allByCategory(CATEGORY_INTERACTION).mapNotNull(::exchange))

    /** The exchanges of chat [id], oldest first. */
    suspend fun chatExchanges(id: String): List<Exchange> =
        dao.allByCategory(CATEGORY_INTERACTION).mapNotNull(::exchange).filter { it.chat == id }.sortedBy { it.time }

    /** Remembered facts (not chat history). */
    suspend fun factCount(): Int = dao.countOf(CATEGORY_FACT)

    suspend fun count(): Int = dao.count()

    suspend fun clear() = dao.clear()

    companion object {
        const val DATABASE_NAME = "xarvis_memory.db"
        const val CATEGORY_FACT = "fact"
        const val CATEGORY_INTERACTION = "interaction"
    }
}
