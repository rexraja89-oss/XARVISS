package com.xarvis.ai.faces

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The faces Rex has taught XARVIS: for each name, a few faceprints (192 numbers each, from
 * MobileFaceNet). Kept only in XARVIS's private storage on this phone — never the photos, never
 * synced, never sent anywhere. Rex can forget anyone in ☰ → XARVIS Eyes → "Faces I know".
 */
class FaceStore(context: Context) {
    private val file = File(context.filesDir, "faces.json")

    fun all(): Map<String, List<FloatArray>> = runCatching { parse(file.readText()) }.getOrDefault(emptyMap())

    fun names(): List<String> = all().map { (name, prints) -> "$name (${prints.size} photo${if (prints.size == 1) "" else "s"})" }

    /** Adds [prints] to [name] (creating them), keeping at most [MAX_PER_PERSON]. */
    fun add(name: String, prints: List<FloatArray>) {
        val now = all().toMutableMap()
        val key = now.keys.firstOrNull { it.equals(name.trim(), ignoreCase = true) } ?: name.trim()
        now[key] = (now[key].orEmpty() + prints).takeLast(MAX_PER_PERSON)
        file.writeText(format(now))
    }

    fun forget(name: String) {
        val now = all().filterKeys { !it.equals(name, ignoreCase = true) }
        file.writeText(format(now))
    }

    companion object {
        const val MAX_PER_PERSON = 30

        fun format(people: Map<String, List<FloatArray>>): String {
            val arr = JSONArray()
            for ((name, prints) in people) {
                val ps = JSONArray()
                prints.forEach { p -> ps.put(JSONArray().apply { p.forEach { put(it.toDouble()) } }) }
                arr.put(JSONObject().put("name", name).put("prints", ps))
            }
            return arr.toString()
        }

        fun parse(json: String): Map<String, List<FloatArray>> {
            val arr = JSONArray(json)
            val out = LinkedHashMap<String, List<FloatArray>>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ps = o.getJSONArray("prints")
                out[o.getString("name")] = (0 until ps.length()).map { j ->
                    val p = ps.getJSONArray(j)
                    FloatArray(p.length()) { p.getDouble(it).toFloat() }
                }
            }
            return out
        }
    }
}
