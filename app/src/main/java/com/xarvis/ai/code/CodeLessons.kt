package com.xarvis.ai.code

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * What XARVIS Code learned on this phone, across all projects (like Task mode's lessons): "LESSON:" lines the brain
 * writes when it finishes a job, and the problems its own checks had to fix in a page it wrote. The newest go into
 * every later job, so the same mistake isn't made twice. Kept in prefs (≤ [KEPT]); Rex sees them by asking
 * "what have you learned".
 */
class CodeLessons(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("xarvis_code_lessons", Context.MODE_PRIVATE)

    fun all(): List<String> = runCatching {
        val a = JSONArray(prefs.getString(KEY, "[]"))
        (0 until a.length()).map { a.getJSONObject(it).optString("t") }.filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    /** Adds [lessons] (newest first; one already known in other words replaces the old one). */
    @Synchronized
    fun learn(lessons: List<String>) {
        if (lessons.isEmpty()) return
        var list = all()
        for (l in lessons.map { it.trim() }.filter { it.length >= 8 }) {
            list = listOf(l) + list.filterNot { similar(it, l) }
        }
        val a = JSONArray()
        list.take(KEPT).forEach { a.put(JSONObject().put("t", it).put("at", System.currentTimeMillis())) }
        prefs.edit().putString(KEY, a.toString()).apply()
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    /** For the brain: the newest few, one per line. */
    fun forPrompt(n: Int = 12): String {
        val l = all().take(n)
        return if (l.isEmpty()) "" else "WHAT YOU LEARNED ON EARLIER JOBS (don't repeat these mistakes):\n" + l.joinToString("\n") { "- $it" }
    }

    companion object {
        private const val KEY = "lessons"
        const val KEPT = 40

        /** Two lessons are the same when most of their words are. */
        fun similar(a: String, b: String): Boolean {
            fun words(s: String) = s.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length > 2 }.toSet()
            val x = words(a)
            val y = words(b)
            if (x.isEmpty() || y.isEmpty()) return false
            return x.intersect(y).size.toDouble() / minOf(x.size, y.size) >= 0.7
        }

        /** A lesson from a problem XARVIS Code's own checks found in a page it wrote (general, not one function's name). */
        fun fromIssue(issue: String): String = when {
            "never defined" in issue -> "Define every function a button calls (onclick) — a page of mine once had buttons that did nothing."
            "cut off" in issue -> "Keep the file compact so it isn't cut off, and end it with </script></html>."
            "external image" in issue -> "Draw images or use data: URIs; external image links break."
            "keyboard" in issue -> "Phones have no keyboard: give every control a big on-screen touch button."
            "API key" in issue || "key or token" in issue || "looks like" in issue ->
                "Never put an API key in a page or repo: the user types it into a settings box, kept in localStorage."
            else -> "A page I wrote had a problem: $issue. Check for it before finishing."
        }
    }
}
