package com.xarvis.ai.policy

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one place that decides whether XARVIS may take an action ([check]) and records what it
 * did ([log]). Every action step goes through it in WorkflowEngine.execute; Rex sets the
 * levels in ☰ → Permissions and reads the record in ☰ → Activity log.
 */
class PolicyLayer(context: Context) {

    private val dao = PolicyDatabase.open(context).dao()
    private val loadLock = Mutex()
    private val _levels = MutableStateFlow<Map<Category, Level>>(emptyMap())
    @Volatile private var loaded = false

    /** Every category's level, for the Permissions screen. */
    val levels: StateFlow<Map<Category, Level>> get() = _levels

    suspend fun load(): Map<Category, Level> {
        if (loaded) return _levels.value
        loadLock.withLock {
            if (!loaded) {
                val saved = runCatching { dao.permissions() }.getOrDefault(emptyList())
                    .mapNotNull { row ->
                        val c = runCatching { Category.valueOf(row.category) }.getOrNull() ?: return@mapNotNull null
                        val l = runCatching { Level.valueOf(row.level) }.getOrNull() ?: return@mapNotNull null
                        (c to l).takeIf { PolicyRules.canSet(c.alwaysManual, l) }
                    }.toMap()
                _levels.value = Category.entries.associateWith { saved[it] ?: PolicyRules.defaultLevel(it) }
                loaded = true
            }
        }
        return _levels.value
    }

    suspend fun level(c: Category): Level = load()[c] ?: PolicyRules.defaultLevel(c)

    /** Allow, ask first, or block. */
    suspend fun check(c: Category): Decision = PolicyRules.decide(level(c))

    /** Changes a level; refuses (returns false) what the rules forbid, like Allow on an always-manual category. */
    suspend fun setLevel(c: Category, l: Level): Boolean {
        if (!PolicyRules.canSet(c.alwaysManual, l)) return false
        load()
        dao.setPermission(PermissionRow(c.name, l.name))
        _levels.value = _levels.value + (c to l)
        return true
    }

    /** Records an action. Everything is scrubbed of anything that looks like a password or code first. */
    suspend fun log(target: String, action: String, category: Category, level: String, result: String, failure: String? = null) {
        try {
            dao.addAudit(
                AuditRow(
                    time = System.currentTimeMillis(),
                    target = PolicyRules.scrub(target),
                    action = PolicyRules.scrub(action),
                    category = category.name,
                    level = level,
                    result = result,
                    failure = failure?.let(PolicyRules::scrub)?.take(300),
                ),
            )
            dao.trimAudit(2000)
        } catch (e: Exception) {
            Log.w("XarvisPolicy", "Couldn't write the activity log", e)
        }
    }

    /** The activity log, newest first; one category when [category] is set. */
    fun audit(category: Category?): Flow<List<AuditRow>> = if (category == null) dao.audit() else dao.audit(category.name)
}
