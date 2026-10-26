package com.xarvis.ai.code

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One message in a saved XARVIS Code session (role is "USER"/"AGENT"/"STEP"). */
data class CodeMsg(val role: String, val text: String, val url: String?)

/** A saved XARVIS Code conversation, shown on the home page and reopenable like a Claude Code session. */
data class CodeSession(
    val id: String,
    val title: String,
    val subtitle: String,
    val updatedAt: Long,
    val messages: List<CodeMsg>,
)

/**
 * Stores XARVIS Code sessions on the phone (SharedPreferences JSON), so XARVIS Code has a home page
 * that lists past sessions to reopen — like the Claude Code app's sessions list.
 */
class CodeSessionStore(context: Context) {

    private val prefs = context.getSharedPreferences("xarvis_code_sessions", Context.MODE_PRIVATE)

    fun list(): List<CodeSession> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).map { parse(arr.getJSONObject(it)) }.sortedByDescending { it.updatedAt }
    }.getOrDefault(emptyList())

    fun get(id: String): CodeSession? = list().firstOrNull { it.id == id }

    fun save(session: CodeSession) {
        val all = list().filter { it.id != session.id } + session
        val arr = JSONArray(); all.forEach { arr.put(toJson(it)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun delete(id: String) {
        val arr = JSONArray(); list().filter { it.id != id }.forEach { arr.put(toJson(it)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun toJson(s: CodeSession): JSONObject {
        val msgs = JSONArray()
        s.messages.forEach { m -> msgs.put(JSONObject().put("r", m.role).put("t", m.text).put("u", m.url ?: "")) }
        return JSONObject().put("id", s.id).put("title", s.title).put("sub", s.subtitle).put("at", s.updatedAt).put("msgs", msgs)
    }

    private fun parse(o: JSONObject): CodeSession {
        val msgs = o.optJSONArray("msgs") ?: JSONArray()
        val list = (0 until msgs.length()).map {
            val m = msgs.getJSONObject(it)
            CodeMsg(m.optString("r"), m.optString("t"), m.optString("u").ifBlank { null })
        }
        return CodeSession(o.optString("id"), o.optString("title"), o.optString("sub"), o.optLong("at"), list)
    }

    private companion object { const val KEY = "sessions" }
}
