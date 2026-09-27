package com.xarvis.ai.policy

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** Rex's level for one category. Categories with no row use [PolicyRules.defaultLevel]. */
@Entity(tableName = "permission")
data class PermissionRow(@PrimaryKey val category: String, val level: String)

/** One action XARVIS took (or was stopped from taking). Never holds passwords or codes. */
@Entity(tableName = "audit")
data class AuditRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val target: String,
    val action: String,
    val category: String,
    /** The permission level used, e.g. "ALLOW", "ASK → allowed once", "DENY". */
    val level: String,
    /** done, failed, declined, blocked, no answer */
    val result: String,
    val failure: String? = null,
)

@Dao
interface PolicyDao {
    @Query("SELECT * FROM permission")
    suspend fun permissions(): List<PermissionRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setPermission(row: PermissionRow)

    @Insert
    suspend fun addAudit(row: AuditRow)

    @Query("DELETE FROM audit WHERE id NOT IN (SELECT id FROM audit ORDER BY time DESC LIMIT :keep)")
    suspend fun trimAudit(keep: Int)

    @Query("SELECT * FROM audit ORDER BY time DESC, id DESC LIMIT 500")
    fun audit(): Flow<List<AuditRow>>

    @Query("SELECT * FROM audit WHERE category = :category ORDER BY time DESC, id DESC LIMIT 500")
    fun audit(category: String): Flow<List<AuditRow>>
}

/** Its own small database, separate from memories, so the memory database never needs migrating for this. */
@Database(entities = [PermissionRow::class, AuditRow::class], version = 1, exportSchema = false)
abstract class PolicyDatabase : RoomDatabase() {
    abstract fun dao(): PolicyDao

    companion object {
        fun open(context: Context): PolicyDatabase =
            Room.databaseBuilder(context.applicationContext, PolicyDatabase::class.java, "xarvis_policy.db").build()
    }
}
