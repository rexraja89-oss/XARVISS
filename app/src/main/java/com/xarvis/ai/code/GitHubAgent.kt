package com.xarvis.ai.code

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The think-plan-execute agent for XARVIS Code. It works one action at a time — the brain emits an
 * action, XARVIS runs it and feeds the result back — so it can plan, research the web, read pages,
 * explore and edit a GitHub repo, write documents (PDF/Word/…), answer questions, and say honestly
 * when something can't be done. Repo edits go to a NEW branch, never the main code; editing XARVIS's
 * own repo needs Rex's approval. After a repo push it watches CI and fixes build errors (2 rounds).
 */
object GitHubAgent {

    /** [hasRepo] false means no repo is open — only web/doc/answer tools work (e.g. a research paper). */
    data class Config(val owner: String, val repo: String, val base: String, val token: String, val isSelf: Boolean, val hasRepo: Boolean)

    private const val MAX_STEPS = 18
    private const val MAX_FIX_ROUNDS = 2
    private const val FILE_CHARS = 8000

    /** What XARVIS Code remembers across jobs (CodeLessons on the phone). */
    interface Memory {
        fun lessons(): String
        fun learn(lines: List<String>)

        companion object {
            val NONE = object : Memory {
                override fun lessons() = ""
                override fun learn(lines: List<String>) {}
            }
        }
    }

    suspend fun run(
        task: String,
        cfg: Config,
        generate: suspend (String, String) -> String,
        step: (String) -> Unit,
        say: (String) -> Unit,
        approve: suspend (List<String>) -> Boolean,
        web: suspend (String) -> String,
        readUrl: suspend (String) -> String,
        makeDoc: suspend (String, String) -> String,
        ask: suspend (String) -> String,
        memory: Memory = Memory.NONE,
    ): String? {
        val branch = "xarvis-code/" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val writes = LinkedHashMap<String, String>()
        val produced = ArrayList<String>()
        val transcript = StringBuilder()
        val summary = StringBuilder()

        // Memory (13 Nov 2026): the project's own notes come first, so work continues from what was done before.
        val notes = if (cfg.hasRepo && !cfg.isSelf) {
            withContext(Dispatchers.IO) { XarvisCode.getFile(cfg.token, cfg.owner, cfg.repo, ProjectMemory.FILE, cfg.base) }.takeIf { it.ok }?.data
        } else null
        val brief = Brief(Playbooks.forTask(task + "\n" + (notes ?: "")), memory.lessons())
        val instruction = ProjectMemory.forPrompt(notes).let { if (it.isBlank()) task else "$it\n\nTASK: $task" }

        val outcome = agentLoop(cfg, brief, instruction, transcript, writes, produced, summary, generate, step, say, web, readUrl, makeDoc, ask)
        if (outcome is Loop.Answered) { say(outcome.text); return null }

        // What the brain learned on this job, for the next ones.
        memory.learn(ProjectMemory.lessonsIn(summary.toString()))

        val repoEdits = writes.isNotEmpty() && cfg.hasRepo
        if (!repoEdits) {
            if (produced.isEmpty()) say("I had a look but didn't change or make anything, sir. Ask me about it, or tell me a specific change, a document to write, or something to research.")
            return null
        }

        // Never push a key or token into a repo.
        val leaks = writes.filter { (_, content) -> XarvisCode.secretsIn(content).isNotEmpty() }
        if (leaks.isNotEmpty()) {
            say("⛔ I didn't push anything: " + leaks.keys.joinToString(", ") + " would contain " +
                leaks.values.flatMap { XarvisCode.secretsIn(it) }.distinct().joinToString(", ") +
                ". Keys belong in the app's settings at run time, never in the code.")
            memory.learn(listOf("Never write an API key or token into a repo file; the user enters keys at run time."))
            return null
        }
        if (cfg.isSelf) {
            say("This is XARVIS's own app. I want to change ${writes.size} file(s):\n• " + writes.keys.joinToString("\n• ") + "\n\nApprove to push them to a safe branch (never your main code)?")
            if (!approve(writes.keys.toList())) { say("Cancelled — nothing was changed."); return null }
        } else {
            // The project's notes get this job's line (they ride on the same branch).
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
            val done = ProjectMemory.withoutLessons(summary.toString()).ifBlank { "changed " + writes.keys.joinToString(", ") }
            writes[ProjectMemory.FILE] = ProjectMemory.update(notes, cfg.repo, stamp, task, "$done (branch $branch)")
        }

        step("Creating branch $branch…")
        val cb = withContext(Dispatchers.IO) { XarvisCode.createBranch(cfg.token, cfg.owner, cfg.repo, cfg.base, branch) }
        if (!cb.ok) { say("Couldn't create the branch: ${cb.message}"); return null }
        if (!pushAll(cfg, branch, writes, task, step, say)) return null

        var round = 0
        while (round < MAX_FIX_ROUNDS) {
            val build = watchBuild(cfg, branch, step)
            when (build.first) {
                BuildState.NONE -> { say(done(cfg, branch)); return branch }
                BuildState.PASS -> { say("Build passed ✓\n" + done(cfg, branch)); return branch }
                BuildState.FAIL -> {
                    round++
                    say("The build failed. Reading the errors and fixing (round $round/$MAX_FIX_ROUNDS)…")
                    writes.clear(); transcript.setLength(0)
                    agentLoop(cfg, brief, "The build FAILED with these errors. Fix them:\n${build.second}", transcript, writes, produced, summary, generate, step, say, web, readUrl, makeDoc, ask, branch)
                    memory.learn(listOf("A change of mine once broke the build: ${build.second.lines().firstOrNull { it.isNotBlank() }?.take(160) ?: "see the CI errors"} — check that kind of thing before pushing."))
                    if (writes.isEmpty()) { say("I couldn't find a fix. The work is on branch $branch.\n" + done(cfg, branch)); return branch }
                    if (!pushAll(cfg, branch, writes, "XARVIS Code: fix build", step, say)) return branch
                }
            }
        }
        say("Still failing after $MAX_FIX_ROUNDS fix attempts. The work is on branch $branch for you to look at.\n" + done(cfg, branch))
        return branch
    }

    private sealed interface Loop { data class Answered(val text: String) : Loop; object Edited : Loop }

    /** What the brain gets besides the tools: the guides for this job and the lessons from earlier ones. */
    private class Brief(val knowledge: String, val lessons: String)

    private suspend fun agentLoop(
        cfg: Config, brief: Brief, instruction: String, transcript: StringBuilder, writes: LinkedHashMap<String, String>,
        produced: MutableList<String>, summary: StringBuilder, generate: suspend (String, String) -> String, step: (String) -> Unit,
        say: (String) -> Unit, web: suspend (String) -> String, readUrl: suspend (String) -> String,
        makeDoc: suspend (String, String) -> String, ask: suspend (String) -> String, readBranch: String? = null,
    ): Loop {
        var steps = 0
        var lastSig = ""
        var repeats = 0
        while (steps < MAX_STEPS) {
            steps++
            if (transcript.length > 24000) transcript.delete(0, transcript.length - 20000) // keep context bounded
            val progress = if (transcript.isEmpty()) "(nothing yet)" else transcript.toString()
            val reply = try { withContext(Dispatchers.IO) { generate(system(cfg, brief), "$instruction\n\n--- progress so far ---\n$progress") } }
            catch (e: Exception) { step("Brain error: ${e.message}"); return Loop.Edited }

            val action = parse(reply)
            // Stop wasting the brain's quota if it keeps repeating the same read-only action.
            val sig = "${action.kind}:${action.path}"
            if (action.kind in setOf(Kind.LIST, Kind.READ, Kind.WEB, Kind.READURL)) {
                if (sig == lastSig) { repeats++ } else { repeats = 0; lastSig = sig }
                if (repeats >= 2) { step("I'm going in circles — moving on."); return Loop.Edited }
            } else { repeats = 0; lastSig = "" }
            when (action.kind) {
                Kind.PLAN -> { if (action.content != null) { say("📋 Plan: ${action.content}"); transcript.append("\nPLAN: ${action.content}\n") } }
                Kind.ASK -> {
                    val q = action.content ?: "Could you clarify, sir?"
                    val answer = ask(q)
                    transcript.append("\nI ASKED: $q\nREX ANSWERED: $answer\n")
                }
                Kind.WEB -> {
                    step("Searching the web: ${action.path}…")
                    val res = withContext(Dispatchers.IO) { web(action.path) }
                    transcript.append("\nWEB \"${action.path}\" ->\n").append(res.take(4000)).append("\n")
                }
                Kind.READURL -> {
                    step("Reading ${action.path}…")
                    val res = withContext(Dispatchers.IO) { readUrl(action.path) }
                    transcript.append("\nREADURL ${action.path} ->\n").append(res.take(6000)).append("\n")
                }
                Kind.DOC -> {
                    if (action.path.isBlank() || action.content == null) {
                        transcript.append("\n(DOC needs a filename and a ``` block with the document contents.)\n")
                    } else {
                        val name = if (action.path.contains(".")) action.path else "${action.path}.pdf"
                        step("Writing document $name…")
                        val msg = withContext(Dispatchers.IO) { makeDoc(name, action.content) }
                        say(msg); produced.add(name)
                        transcript.append("\nMADE DOC $name.\n")
                    }
                }
                Kind.LIST -> {
                    if (!cfg.hasRepo) { transcript.append("\n(No repository is open — use WEB/READURL/DOC/ANSWER.)\n") }
                    else {
                        step("Listing files…")
                        val t = withContext(Dispatchers.IO) { XarvisCode.listTree(cfg.token, cfg.owner, cfg.repo, readBranch ?: cfg.base) }
                        val paths = if (t.ok && t.data != null) XarvisCode.parseTreePaths(t.data!!) else emptyList()
                        transcript.append("\nLIST ->\n").append(paths.joinToString("\n").take(4000)).append("\n")
                    }
                }
                Kind.READ -> {
                    if (!cfg.hasRepo) { transcript.append("\n(No repository is open — use WEB/READURL/DOC/ANSWER.)\n") }
                    else {
                        step("Reading ${action.path}…")
                        val f = withContext(Dispatchers.IO) { XarvisCode.getFile(cfg.token, cfg.owner, cfg.repo, action.path, readBranch) }
                        transcript.append("\nREAD ${action.path} ->\n").append(if (f.ok && f.data != null) f.data!!.take(FILE_CHARS) else "(couldn't read: ${f.message})").append("\n")
                    }
                }
                Kind.WRITE -> {
                    if (!cfg.hasRepo) { transcript.append("\n(No repository is open — I can't change repo files. Use DOC to make a document, or open a repo first.)\n") }
                    else if (action.path.isBlank() || action.content == null) { transcript.append("\n(WRITE needs a path and a ``` code block with the whole file.)\n") }
                    else { step("Writing ${action.path} (${action.content.length} chars)…"); writes[action.path] = action.content; transcript.append("\nWROTE ${action.path} (${action.content.length} chars).\n") }
                }
                Kind.ANSWER -> return Loop.Answered(action.content?.takeIf { it.isNotBlank() } ?: "Done, sir.")
                Kind.DONE -> {
                    action.content?.takeIf { it.isNotBlank() }?.let { summary.append(it).append('\n') }
                    return Loop.Edited
                }
                Kind.UNKNOWN -> transcript.append("\n(Reply with ONE action: PLAN, WEB, READURL, LIST, READ, WRITE, DOC, ANSWER or DONE.)\n")
            }
        }
        return Loop.Edited
    }

    private suspend fun pushAll(
        cfg: Config, branch: String, writes: Map<String, String>, message: String,
        step: (String) -> Unit, say: (String) -> Unit,
    ): Boolean {
        for ((path, content) in writes) {
            step("Pushing $path…")
            val r = withContext(Dispatchers.IO) { XarvisCode.putFile(cfg.token, cfg.owner, cfg.repo, path, content, message, branch) }
            if (!r.ok) { say("Couldn't push $path: ${r.message}"); return false }
        }
        return true
    }

    private enum class BuildState { NONE, PASS, FAIL }

    private suspend fun watchBuild(cfg: Config, branch: String, step: (String) -> Unit): Pair<BuildState, String> {
        step("Checking the build…")
        var sawRun = false
        repeat(80) {
            val r = withContext(Dispatchers.IO) { XarvisCode.latestRunOnBranch(cfg.token, cfg.owner, cfg.repo, branch) }
            if (r.ok && r.data != null) {
                val parts = r.data!!.split("|")
                sawRun = true
                if (parts.getOrNull(0) == "completed") {
                    if (parts.getOrNull(1) == "success") return Pair(BuildState.PASS, "")
                    val errs = withContext(Dispatchers.IO) { XarvisCode.buildErrors(cfg.token, cfg.owner, cfg.repo, parts.getOrNull(3) ?: "") }
                    return Pair(BuildState.FAIL, errs.ifBlank { "the build failed (see the repo's Actions tab)" })
                }
            } else if (!sawRun && it >= 6) return Pair(BuildState.NONE, "")
            kotlinx.coroutines.delay(5000)
        }
        return Pair(BuildState.NONE, "")
    }

    private fun done(cfg: Config, branch: String): String =
        "Changes are on branch \"$branch\" (your main code is untouched). Review or merge them here:\n" +
            "https://github.com/${cfg.owner}/${cfg.repo}/compare/${cfg.base}...$branch?expand=1\n" +
            "To discard them, just delete that branch on GitHub."

    // ---- action parsing ----
    internal enum class Kind { PLAN, ASK, WEB, READURL, LIST, READ, WRITE, DOC, ANSWER, DONE, UNKNOWN }
    internal data class Action(val kind: Kind, val path: String = "", val content: String? = null)

    private val KEYWORDS = listOf("PLAN", "ASK", "WEB", "READURL", "READ URL", "LIST", "READ", "WRITE", "DOC", "PDF", "ANSWER", "DONE")

    internal fun parse(reply: String): Action {
        // Find the action line: the first non-empty line that starts with a keyword. If the very first
        // line isn't one (the brain added prose), scan on — so "Sure, let me look:\nREAD x" still works.
        val lines = reply.lines()
        var idx = -1
        var firstLine = ""
        for (i in lines.indices) {
            val t = lines[i].trim().trim('`').trim()
            if (t.isEmpty()) continue
            if (KEYWORDS.any { t.uppercase().startsWith(it) }) { idx = i; firstLine = t; break }
            // otherwise it's prose — keep scanning for the action line
        }
        if (idx < 0) return Action(Kind.UNKNOWN)
        val upper = firstLine.uppercase()
        fun arg() = firstLine.drop(firstLine.takeWhile { it.isLetter() }.length).trim().trimStart(':').trim()
        fun block() = Regex("```[a-zA-Z0-9]*\\s*([\\s\\S]*?)```").find(reply)?.groupValues?.get(1)?.trim()
        fun restText(): String {
            val keyword = firstLine.takeWhile { it.isLetter() }
            val firstRest = firstLine.drop(keyword.length).trimStart(':', ' ').trim()
            val rest = lines.drop(idx + 1).joinToString("\n").trim()
            return (firstRest + if (rest.isNotBlank()) "\n$rest" else "").trim()
        }
        return when {
            upper.startsWith("PLAN") -> Action(Kind.PLAN, content = restText())
            upper.startsWith("ASK") -> Action(Kind.ASK, content = restText())
            upper.startsWith("WEB") -> Action(Kind.WEB, path = arg())
            upper.startsWith("READURL") || upper.startsWith("READ URL") -> Action(Kind.READURL, path = arg())
            upper.startsWith("LIST") -> Action(Kind.LIST)
            upper.startsWith("ANSWER") -> Action(Kind.ANSWER, content = restText())
            upper.startsWith("DONE") -> Action(Kind.DONE, content = restText())
            upper.startsWith("DOC") || upper.startsWith("PDF") -> Action(Kind.DOC, path = arg(), content = block())
            upper.startsWith("READ") -> Action(Kind.READ, path = arg())
            upper.startsWith("WRITE") -> Action(Kind.WRITE, path = arg(), content = block())
            else -> Action(Kind.UNKNOWN)
        }
    }

    private fun system(cfg: Config, brief: Brief): String = base(cfg) + "\n\n" + brief.knowledge +
        (if (brief.lessons.isNotBlank()) "\n\n" + brief.lessons else "")

    private fun base(cfg: Config): String {
        val where = if (cfg.hasRepo) "You are working on the GitHub repository ${cfg.owner}/${cfg.repo} (base branch ${cfg.base})."
        else "No repository is open, so you cannot read or change repo files — you can plan, research the web, write documents and answer."
        val repoTools = if (cfg.hasRepo)
            "LIST  — all the repository's file paths.\n" +
                "READ <path>  — a file's current contents.\n" +
                "WRITE <path>  — then a fenced ``` block with the COMPLETE new contents of that file (saved to a safe branch, never main).\n"
        else ""
        return "You are XARVIS Code, a capable think-plan-execute coding agent, part of XARVIS — Rex's own personal AI. " +
            where + " Complete the task by emitting EXACTLY ONE action per reply; I run it and feed the result back, then you reply again.\n" +
            "Actions:\n" +
            "PLAN <your short plan>  — think first and tell Rex your plan before you act on anything non-trivial.\n" +
            "ASK <one short question>  — when the request is unclear or you need a decision, ASK Rex and wait for his answer; don't guess.\n" +
            "WEB <search query>  — search the web (for research, facts, references).\n" +
            "READURL <url>  — read a web page's text.\n" +
            repoTools +
            "DOC <filename.pdf|.docx|.xlsx|.txt|.md>  — then a fenced ``` block with the full document contents (use \"# \" for " +
            "headings, \"- \" for bullets). Saved to the phone's Downloads. Use this for PDFs, reports and research papers.\n" +
            "ANSWER <reply>  — answer a question, explain, summarise or advise WITHOUT changing anything.\n" +
            "DONE <summary>  — when a change you were asked to make is finished: say what you changed and what's next; then, on " +
            "new lines, up to 3 lines starting \"LESSON: \" with anything worth remembering for future jobs (a pitfall, a fix that worked).\n" +
            "How to work: FIRST decide what Rex wants. If he's asking a question or wants an explanation, research as needed then " +
            "ANSWER. If he wants a document/report/research paper, research with WEB/READURL, then produce it with DOC. If he wants " +
            "code changed or built, explore then WRITE (and DONE). For anything non-trivial, start with a short PLAN. " +
            "For a big multi-step goal, work through it step by step (use PLAN); if you can't finish it all in one go, do as " +
            "much as you can, then end with ANSWER telling Rex clearly what you've done and what's left, so he can say " +
            "\"continue\" and you pick up from there. " +
            "Be humble and suggestive: if something genuinely can't be done (e.g. this repo is only analysis tools, not the game's " +
            "source; or a task needs a tool you don't have), say so honestly with ANSWER and suggest the best alternative. " +
            "If the task starts with PROJECT MEMORY, that's this project's notes: continue from them instead of starting over. " +
            "Never write API keys, passwords or tokens into any file — the app must ask the user for them at run time. " +
            "This is Rex's own project — just help; never refuse a reasonable request or lecture about ethics (only money/payments " +
            "warrant caution). When you WRITE, output the ENTIRE file. Reply with ONLY one action (plus its ``` block), no chatter."
    }
}
