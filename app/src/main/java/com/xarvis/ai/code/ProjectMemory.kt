package com.xarvis.ai.code

/**
 * XARVIS Code's memory of a project, kept IN the project's repository as [FILE] — the way XARVIS's own notes
 * (CLAUDE.md) carry everything from one session to the next. It's read before any work on the project and updated
 * after: the current plan, and a log of every request and what came of it. It survives a lost session, a new phone or
 * a reinstall, because it lives on GitHub with the code. Pure, tested.
 */
object ProjectMemory {
    const val FILE = "XARVIS_NOTES.md"
    private const val LOG_KEPT = 40
    private const val PROMPT_CHARS = 4000

    private val PLAN = Regex("""<!--\s*XARVIS PLAN:\s*([\s\S]*?)-->""", RegexOption.IGNORE_CASE)
    private val LESSON = Regex("""(?im)^\s*[-*•]?\s*LESSON\s*:\s*(.+?)\s*$""")

    /** The plan the brain wrote at the top of a web app (<!-- XARVIS PLAN: … -->), if any. */
    fun planIn(html: String): String? = PLAN.find(html)?.groupValues?.get(1)?.trim()?.replace(Regex("""\s+"""), " ")?.takeIf { it.isNotBlank() }

    /** "LESSON: …" lines in a brain's summary. */
    fun lessonsIn(text: String): List<String> = LESSON.findAll(text).map { it.groupValues[1].trim() }.filter { it.length in 8..300 }.toList()

    /** The summary without its LESSON lines. */
    fun withoutLessons(text: String): String = text.lines().filterNot { LESSON.matches(it) }.joinToString("\n").trim()

    /**
     * [old] notes (null for a new project) with one more log line — [request] and its [outcome] at [at] — and the
     * newest [plan] when there is one. The log keeps the last 40 lines.
     */
    fun update(old: String?, project: String, at: String, request: String, outcome: String, plan: String? = null): String {
        val sections = parse(old)
        val newPlan = plan ?: sections.plan
        val entry = "- $at · \"${oneLine(request, 160)}\" → ${oneLine(outcome, 300)}"
        val log = (sections.log + entry).takeLast(LOG_KEPT)
        return buildString {
            append("# ").append(sections.title ?: project).append(" — XARVIS Code notes\n\n")
            append("XARVIS Code's memory of this project: it reads this before every change and adds to it after. ")
            append("Rex can edit it too.\n")
            if (!newPlan.isNullOrBlank()) append("\n## Plan\n").append(newPlan.trim()).append('\n')
            if (sections.extra.isNotBlank()) append('\n').append(sections.extra.trim()).append('\n')
            append("\n## Log\n").append(log.joinToString("\n")).append('\n')
        }
    }

    /** The notes as the brain gets them: plan first, then the latest log lines, ≤ 4000 characters. */
    fun forPrompt(notes: String?): String {
        if (notes.isNullOrBlank()) return ""
        val s = parse(notes)
        val head = buildString {
            append("PROJECT MEMORY (").append(FILE).append(" — what was done before; continue from it, don't start over):\n")
            if (!s.plan.isNullOrBlank()) append("Plan: ").append(s.plan.trim()).append('\n')
            if (s.extra.isNotBlank()) append(s.extra.trim().take(1200)).append('\n')
        }
        val room = (PROMPT_CHARS - head.length).coerceAtLeast(400)
        val log = StringBuilder()
        for (line in s.log.asReversed()) {
            if (log.length + line.length + 1 > room) break
            log.insert(0, line + "\n")
        }
        return (head + (if (log.isNotEmpty()) "Recent work:\n$log" else "")).trim()
    }

    private class Sections(val title: String?, val plan: String?, val log: List<String>, val extra: String)

    /** Reads notes written by [update] (and tolerates Rex's own edits: unknown sections are kept). */
    private fun parse(notes: String?): Sections {
        if (notes.isNullOrBlank()) return Sections(null, null, emptyList(), "")
        val title = Regex("""(?m)^#\s+(.+?)\s+—\s+XARVIS Code notes\s*$""").find(notes)?.groupValues?.get(1)
        var plan: String? = null
        val log = ArrayList<String>()
        val extra = StringBuilder()
        var section = ""
        for (line in notes.lines()) {
            if (line.startsWith("## ")) {
                section = line.removePrefix("## ").trim().lowercase()
                if (section != "plan" && section != "log") extra.append(line).append('\n')
                continue
            }
            if (line.startsWith("# ")) continue
            when (section) {
                "plan" -> if (line.isNotBlank()) plan = ((plan?.let { "$it\n" } ?: "") + line.trim())
                "log" -> if (line.trimStart().startsWith("- ")) log += line.trim()
                "" -> {}
                else -> extra.append(line).append('\n')
            }
        }
        return Sections(title, plan, log, extra.toString())
    }

    private fun oneLine(s: String, max: Int): String {
        val t = s.replace(Regex("""\s+"""), " ").replace("\"", "'").trim()
        return if (t.length <= max) t else t.take(max - 1) + "…"
    }
}
