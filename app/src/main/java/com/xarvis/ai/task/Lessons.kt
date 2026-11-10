package com.xarvis.ai.task

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What Task mode has learned about each app or website ("CapCut: Export is the ↑ arrow at the top right",
 * "Site X: the free plan allows 3 videos a day"). The runner notes lessons while it works (NOTE) and writes a few
 * after each task; they go into the brain's prompt the next time it plans or works in that app, so it gets better
 * with use. Rex can read and delete them. Kept on the phone only ([file], JSON).
 */
class LessonBook(private val file: File) {
    private val byApp = LinkedHashMap<String, MutableList<String>>()

    init {
        runCatching {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                o.keys().forEach { k ->
                    val arr = o.optJSONArray(k) ?: return@forEach
                    byApp[k] = (0 until arr.length()).map { arr.getString(it) }.toMutableList()
                }
            }
        }
    }

    @Synchronized
    fun add(app: String, lesson: String): Boolean {
        val key = keyOf(app)
        val text = lesson.trim().replace(Regex("""\s+"""), " ").take(MAX_CHARS)
        if (key.isEmpty() || text.length < 8) return false
        val list = byApp.getOrPut(key) { ArrayList() }
        // The same lesson again (or one that starts the same way) replaces the old one, as the newest.
        val similar = list.indexOfFirst { same(it, text) }
        if (similar >= 0) list.removeAt(similar)
        list += text
        while (list.size > MAX_PER_APP) list.removeAt(0)
        save()
        return true
    }

    /** Lessons for [app] (newest last), and the general ones. */
    @Synchronized
    fun forApp(app: String): List<String> = byApp[keyOf(app)].orEmpty().toList()

    @Synchronized
    fun general(): List<String> = byApp[GENERAL].orEmpty().toList()

    /** Everything, for Rex's screen: app → lessons. */
    @Synchronized
    fun all(): Map<String, List<String>> = byApp.mapValues { it.value.toList() }

    @Synchronized
    fun remove(app: String, lesson: String) {
        val key = keyOf(app)
        byApp[key]?.remove(lesson)
        if (byApp[key]?.isEmpty() == true) byApp.remove(key)
        save()
    }

    /** The lessons to show the brain for [apps] (current app first), at most [max]. */
    @Synchronized
    fun promptFor(apps: List<String>, max: Int = 12): List<String> {
        val out = LinkedHashSet<String>()
        for (a in apps) byApp[keyOf(a)]?.asReversed()?.forEach { out += "$a: $it" }
        byApp[GENERAL]?.asReversed()?.forEach { out += it }
        return out.take(max)
    }

    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            val o = JSONObject()
            byApp.forEach { (k, v) -> o.put(k, JSONArray(v)) }
            file.writeText(o.toString())
        }
    }

    companion object {
        const val GENERAL = "general"
        private const val MAX_PER_APP = 25
        private const val MAX_CHARS = 240

        /** "CapCut", "capcut ", "com.lemon.lvoverseas" stay as given apart from case and spaces; web addresses keep their site. */
        fun keyOf(app: String): String {
            val a = app.trim().lowercase()
            if (a.isEmpty() || a == "xarvis" || a == "any" || a == "phone") return if (a.isEmpty()) "" else GENERAL
            Regex("""^https?://(?:www\.)?([^/\s]+)""").find(a)?.let { return it.groupValues[1] }
            return a.replace(Regex("""\s+"""), " ").take(40)
        }

        private fun same(a: String, b: String): Boolean {
            val x = a.lowercase()
            val y = b.lowercase()
            return x == y || (x.length > 30 && y.length > 30 && x.take(30) == y.take(30))
        }
    }
}
