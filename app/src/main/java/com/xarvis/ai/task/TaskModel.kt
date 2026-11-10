package com.xarvis.ai.task

import org.json.JSONArray
import org.json.JSONObject

/**
 * Task mode (Rex, Nov 2026: "complex tasks, from planning to execution" — YouTube videos and comics made on the S22
 * with free AI platforms). A [Plan] is written by the cloud brain, approved by Rex once, then run step by step by
 * [TaskRunner], which uses the phone through XARVIS Hands and answers in [TaskAction]s.
 */
data class PlanStep(
    val title: String,
    /** The app or website to use ("CapCut", "YT Studio", "https://…", or "XARVIS" for writing text itself). */
    val app: String,
    /** How to do it, in a few sentences. */
    val how: String,
    /** What "finished" looks like, so the runner knows when to move on. */
    val doneWhen: String,
) {
    fun toJson(): JSONObject = JSONObject().put("title", title).put("app", app).put("how", how).put("done", doneWhen)
}

data class Plan(val title: String, val steps: List<PlanStep>) {
    fun toJson(): JSONObject = JSONObject().put("title", title).put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })

    /** The plan as Rex reads it: numbered steps, each with its app. */
    fun describe(): String = buildString {
        append(title).append('\n')
        steps.forEachIndexed { i, s ->
            append(i + 1).append(". ").append(s.title)
            if (s.app.isNotBlank()) append("  (").append(s.app).append(')')
            append('\n')
        }
    }.trim()

    companion object {
        const val MAX_STEPS = 20

        /** The brain's plan: the first JSON object in its answer (code fences and chatter around it are fine). */
        fun parse(raw: String): Plan? = runCatching {
            val o = TaskText.firstJsonObject(raw) ?: return null
            val arr = o.optJSONArray("steps") ?: return null
            val steps = (0 until arr.length()).mapNotNull { i ->
                val s = arr.optJSONObject(i) ?: return@mapNotNull arr.optString(i).takeIf { it.isNotBlank() }?.let { PlanStep(it, "", "", "") }
                val title = s.optString("title").trim()
                if (title.isEmpty()) null
                else PlanStep(title, s.optString("app").trim(), s.optString("how").trim(), s.optString("done").ifBlank { s.optString("doneWhen") }.trim())
            }.take(MAX_STEPS)
            if (steps.isEmpty()) null else Plan(o.optString("title").trim().ifEmpty { "Task" }, steps)
        }.getOrNull()

        fun fromJson(o: JSONObject): Plan? = parse(o.toString())
    }
}

/** One thing the runner does on the phone, as the brain asked for it. */
sealed interface TaskAction {
    data class Open(val app: String) : TaskAction
    data class Url(val url: String) : TaskAction
    data class Tap(val n: Int, val long: Boolean = false) : TaskAction
    /** A point on the screenshot, 0–1000 across and down (for apps whose buttons have no text). */
    data class TapXY(val x: Int, val y: Int, val long: Boolean = false) : TaskAction
    /** [n] null: into the text box that has the cursor. */
    data class Type(val n: Int?, val text: String) : TaskAction
    data class Swipe(val direction: String) : TaskAction
    data object Back : TaskAction
    data object Home : TaskAction
    data class Wait(val seconds: Int, val why: String) : TaskAction
    data class Save(val name: String, val text: String) : TaskAction
    data class Note(val lesson: String) : TaskAction
    data class Ask(val question: String) : TaskAction
    data class Done(val result: String) : TaskAction
    data class Stuck(val why: String) : TaskAction
}

object TaskText {
    private val VERB = Regex(
        """(?im)^[\s*>`"'\-•]*(OPEN|URL|TAPXY|LONGXY|TAP|LONG|TYPE|SWIPE|SCROLL|BACK|HOME|WAIT|SAVE|NOTE|ASK|DONE|STUCK)\b[\s:]*(.*)$""",
    )
    private const val MAX_ACTIONS = 3
    const val MAX_WAIT_S = 600

    /**
     * The actions in the brain's answer, in order (at most [MAX_ACTIONS]; a turn ends at DONE, STUCK, ASK or WAIT,
     * which need a fresh look at the screen or at Rex). A SAVE takes the lines after it, up to END SAVE.
     */
    fun parseActions(raw: String): List<TaskAction> {
        val lines = raw.replace("\r", "").lines()
        val out = ArrayList<TaskAction>()
        var i = 0
        while (i < lines.size && out.size < MAX_ACTIONS) {
            val m = VERB.matchEntire(lines[i]) ?: VERB.find(lines[i])?.takeIf { it.range.first == 0 }
            i++
            if (m == null) continue
            val verb = m.groupValues[1].uppercase()
            val rest = m.groupValues[2].trim().trim('`', '*').trim()
            val action: TaskAction? = when (verb) {
                "OPEN" -> rest.trim('"', '\'').takeIf { it.isNotBlank() }?.let { TaskAction.Open(it) }
                "URL" -> Regex("""https?://\S+""").find(rest)?.value?.trimEnd('.', ',', ')', '"', '\'')?.let { TaskAction.Url(it) }
                "TAP", "LONG" -> Regex("""\[?(\d+)""").find(rest)?.groupValues?.get(1)?.toIntOrNull()?.let { TaskAction.Tap(it, verb == "LONG") }
                "TAPXY", "LONGXY" -> Regex("""(\d+)\D+(\d+)""").find(rest)?.let {
                    TaskAction.TapXY(it.groupValues[1].toInt().coerceIn(0, 1000), it.groupValues[2].toInt().coerceIn(0, 1000), verb == "LONGXY")
                }
                "TYPE" -> typeAction(rest)
                "SWIPE", "SCROLL" -> TaskAction.Swipe(
                    when {
                        rest.startsWith("up", true) -> "up"
                        rest.startsWith("left", true) -> "left"
                        rest.startsWith("right", true) -> "right"
                        else -> "down"
                    },
                )
                "BACK" -> TaskAction.Back
                "HOME" -> TaskAction.Home
                "WAIT" -> {
                    val s = Regex("""\d+""").find(rest)?.value?.toIntOrNull() ?: 10
                    TaskAction.Wait(s.coerceIn(1, MAX_WAIT_S), rest.replace(Regex("""^\d+\s*(s|sec|seconds?)?\s*"""), "").trim())
                }
                "SAVE" -> {
                    // SAVE name.txt: one line   — or —   SAVE name.txt  + lines + END SAVE
                    val name = rest.substringBefore(':').trim().trim('"', '\'')
                    val inline = rest.substringAfter(':', "").trim()
                    val text = if (inline.isNotEmpty()) inline else {
                        val body = StringBuilder()
                        while (i < lines.size && !lines[i].trim().equals("END SAVE", ignoreCase = true)) {
                            body.append(lines[i]).append('\n')
                            i++
                        }
                        if (i < lines.size) i++ // END SAVE
                        body.toString().trim()
                    }
                    if (name.isBlank() || text.isBlank()) null else TaskAction.Save(safeName(name), text)
                }
                "NOTE" -> rest.takeIf { it.isNotBlank() }?.let { TaskAction.Note(it) }
                "ASK" -> TaskAction.Ask(rest.ifBlank { "What should I do next?" })
                "DONE" -> TaskAction.Done(rest)
                "STUCK" -> TaskAction.Stuck(rest.ifBlank { "no reason given" })
                else -> null
            }
            if (action != null) {
                out += action
                if (action is TaskAction.Done || action is TaskAction.Stuck || action is TaskAction.Ask || action is TaskAction.Wait) break
            }
        }
        return out
    }

    /** "TYPE 7: hello", "TYPE 7 \"hello\"", "TYPE 7 hello" → box 7; "TYPE: hello" / "TYPE hello" → the box with the cursor. */
    private fun typeAction(rest: String): TaskAction.Type? {
        if (rest.isBlank()) return null
        Regex("""^\[?(\d+)\]?\s*[:|]\s*(.+)$""").find(rest)?.let { return TaskAction.Type(it.groupValues[1].toInt(), unquote(it.groupValues[2])) }
        Regex("""^\[?(\d+)\]?\s+["“](.+)["”]$""").find(rest)?.let { return TaskAction.Type(it.groupValues[1].toInt(), it.groupValues[2]) }
        Regex("""^\[(\d+)\]\s+(.+)$""").find(rest)?.let { return TaskAction.Type(it.groupValues[1].toInt(), unquote(it.groupValues[2])) }
        Regex("""^(\d+)\s+(\D.*)$""").find(rest)?.let { return TaskAction.Type(it.groupValues[1].toInt(), unquote(it.groupValues[2])) }
        return TaskAction.Type(null, unquote(rest.removePrefix(":").trim()))
    }

    private fun unquote(s: String) = s.trim().removeSurrounding("\"").removeSurrounding("“", "”").removeSurrounding("'")

    /** A file name that's safe to save (no folders, no odd characters), with .txt when it has no extension. */
    fun safeName(name: String): String {
        val clean = name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().take(60).ifEmpty { "notes" }
        return if ('.' in clean) clean else "$clean.txt"
    }

    /** The first {...} JSON object in [raw] (the brain may wrap it in ``` fences or add a sentence). */
    fun firstJsonObject(raw: String): JSONObject? {
        var start = raw.indexOf('{')
        while (start >= 0) {
            var depth = 0
            var inString = false
            var escaped = false
            for (j in start until raw.length) {
                val c = raw[j]
                if (inString) {
                    if (escaped) escaped = false
                    else if (c == '\\') escaped = true
                    else if (c == '"') inString = false
                    continue
                }
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            runCatching { return JSONObject(raw.substring(start, j + 1)) }
                            break
                        }
                    }
                }
            }
            start = raw.indexOf('{', start + 1)
        }
        return null
    }

    /** "LESSON <app>: <tip>" lines from the brain's look back at a finished task. */
    fun parseLessons(raw: String): List<Pair<String, String>> =
        Regex("""(?im)^[\s*\-•]*LESSON\s+([^:]{1,40}):\s*(.{8,240})$""").findAll(raw)
            .map { it.groupValues[1].trim() to it.groupValues[2].trim() }.toList()
}
