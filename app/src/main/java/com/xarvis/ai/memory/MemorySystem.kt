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

    @Query("DELETE FROM memories")
    suspend fun clear()
}

@Database(entities = [MemoryEntry::class], version = 1, exportSchema = false)
abstract class XarvisDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
}

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

    suspend fun logInteraction(command: String, response: String) {
        dao.insert(MemoryEntry(category = CATEGORY_INTERACTION, content = "$command => $response"))
    }

    suspend fun count(): Int = dao.count()

    suspend fun clear() = dao.clear()

    companion object {
        const val DATABASE_NAME = "xarvis_memory.db"
        const val CATEGORY_FACT = "fact"
        const val CATEGORY_INTERACTION = "interaction"
    }
}
