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
    ): String? {
        val branch = "xarvis-code/" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val writes = LinkedHashMap<String, String>()
        val produced = ArrayList<String>()
        val transcript = StringBuilder()

        val outcome = agentLoop(cfg, task, transcript, writes, produced, generate, step, say, web, readUrl, makeDoc)
        if (outcome is Loop.Answered) { say(outcome.text); return null }

        val repoEdits = writes.isNotEmpty() && cfg.hasRepo
        if (!repoEdits) {
            if (produced.isEmpty()) say("I had a look but didn't change or make anything, sir. Ask me about it, or tell me a specific change, a document to write, or something to research.")
            return null
        }

        if (cfg.isSelf) {
            say("This is XARVIS's own app. I want to change ${writes.size} file(s):\n• " + writes.keys.joinToString("\n• ") + "\n\nApprove to push them to a safe branch (never your main code)?")
            if (!approve(writes.keys.toList())) { say("Cancelled — nothing was changed."); return null }
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
                    agentLoop(cfg, "The build FAILED with these errors. Fix them:\n${build.second}", transcript, writes, produced, generate, step, say, web, readUrl, makeDoc, branch)
                    if (writes.isEmpty()) { say("I couldn't find a fix. The work is on branch $branch.\n" + done(cfg, branch)); return branch }
                    if (!pushAll(cfg, branch, writes, "XARVIS Code: fix build", step, say)) return branch
                }
            }
        }
        say("Still failing after $MAX_FIX_ROUNDS fix attempts. The work is on branch $branch for you to look at.\n" + done(cfg, branch))
        return branch
    }

    private sealed interface Loop { data class Answered(val text: String) : Loop; object Edited : Loop }

    private suspend fun agentLoop(
        cfg: Config, instruction: String, transcript: StringBuilder, writes: LinkedHashMap<String, String>,
        produced: MutableList<String>, generate: suspend (String, String) -> String, step: (String) -> Unit,
        say: (String) -> Unit, web: suspend (String) -> String, readUrl: suspend (String) -> String,
        makeDoc: suspend (String, String) -> String, readBranch: String? = null,
    ): Loop {
        var steps = 0
        while (steps < MAX_STEPS) {
            steps++
            val progress = if (transcript.isEmpty()) "(nothing yet)" else transcript.toString()
            val reply = try { withContext(Dispatchers.IO) { generate(system(cfg), "$instruction\n\n--- progress so far ---\n$progress") } }
            catch (e: Exception) { step("Brain error: ${e.message}"); return Loop.Edited }

            val action = parse(reply)
            when (action.kind) {
                Kind.PLAN -> { if (action.content != null) { say("📋 Plan: ${action.content}"); transcript.append("\nPLAN: ${action.content}\n") } }
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
                Kind.DONE -> return Loop.Edited
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
    internal enum class Kind { PLAN, WEB, READURL, LIST, READ, WRITE, DOC, ANSWER, DONE, UNKNOWN }
    internal data class Action(val kind: Kind, val path: String = "", val content: String? = null)

    internal fun parse(reply: String): Action {
        val firstLine = reply.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return Action(Kind.UNKNOWN)
        val upper = firstLine.uppercase()
        fun arg() = firstLine.drop(firstLine.takeWhile { it.isLetter() }.length).trim().trimStart(':').trim()
        fun block() = Regex("```[a-zA-Z0-9]*\\s*([\\s\\S]*?)```").find(reply)?.groupValues?.get(1)?.trim()
        fun restText(): String {
            val keyword = firstLine.takeWhile { it.isLetter() }
            val firstRest = firstLine.drop(keyword.length).trimStart(':', ' ').trim()
            val nl = reply.indexOf('\n')
            val rest = if (nl >= 0) reply.substring(nl + 1).trim() else ""
            return (firstRest + if (rest.isNotBlank()) "\n$rest" else "").trim()
        }
        return when {
            upper.startsWith("PLAN") -> Action(Kind.PLAN, content = restText())
            upper.startsWith("WEB") -> Action(Kind.WEB, path = arg())
            upper.startsWith("READURL") || upper.startsWith("READ URL") -> Action(Kind.READURL, path = arg())
            upper.startsWith("LIST") -> Action(Kind.LIST)
            upper.startsWith("ANSWER") -> Action(Kind.ANSWER, content = restText())
            upper.startsWith("DONE") -> Action(Kind.DONE)
            upper.startsWith("DOC") || upper.startsWith("PDF") -> Action(Kind.DOC, path = arg(), content = block())
            upper.startsWith("READ") -> Action(Kind.READ, path = arg())
            upper.startsWith("WRITE") -> Action(Kind.WRITE, path = arg(), content = block())
            else -> Action(Kind.UNKNOWN)
        }
    }

    private fun system(cfg: Config): String {
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
            "WEB <search query>  — search the web (for research, facts, references).\n" +
            "READURL <url>  — read a web page's text.\n" +
            repoTools +
            "DOC <filename.pdf|.docx|.xlsx|.txt|.md>  — then a fenced ``` block with the full document contents (use \"# \" for " +
            "headings, \"- \" for bullets). Saved to the phone's Downloads. Use this for PDFs, reports and research papers.\n" +
            "ANSWER <reply>  — answer a question, explain, summarise or advise WITHOUT changing anything.\n" +
            "DONE <summary>  — when a change you were asked to make is finished.\n" +
            "How to work: FIRST decide what Rex wants. If he's asking a question or wants an explanation, research as needed then " +
            "ANSWER. If he wants a document/report/research paper, research with WEB/READURL, then produce it with DOC. If he wants " +
            "code changed or built, explore then WRITE (and DONE). For anything non-trivial, start with a short PLAN. " +
            "Be humble and suggestive: if something genuinely can't be done (e.g. this repo is only analysis tools, not the game's " +
            "source; or a task needs a tool you don't have), say so honestly with ANSWER and suggest the best alternative. " +
            "This is Rex's own project — just help; never refuse a reasonable request or lecture about ethics (only money/payments " +
            "warrant caution). When you WRITE, output the ENTIRE file. Reply with ONLY one action (plus its ``` block), no chatter."
    }
}
