package com.xarvis.ai.code

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Claude-Code-style agent for XARVIS Code (Stages 2–5). It works on an EXISTING repo by emitting
 * one action at a time — LIST / READ / WRITE / DONE — which XARVIS runs and feeds back, so the brain
 * can explore the project and change several files on its own (Stages 2–3). Everything it writes goes
 * to a NEW branch, never the repo's main code (Stage 5 safety); on XARVIS's own repo it must get Rex's
 * approval before pushing. After pushing it watches the branch's CI and feeds build errors back for a
 * couple of fix rounds (Stage 4). The worst case is a throwaway branch Rex can delete.
 */
object GitHubAgent {

    data class Config(val owner: String, val repo: String, val base: String, val token: String, val isSelf: Boolean)

    private const val MAX_STEPS = 14
    private const val MAX_FIX_ROUNDS = 2
    private const val FILE_CHARS = 8000

    /** Runs the whole task. Returns the branch name it pushed to, or null if nothing was pushed. */
    suspend fun run(
        task: String,
        cfg: Config,
        generate: suspend (String, String) -> String,
        step: (String) -> Unit,
        say: (String) -> Unit,
        approve: suspend (List<String>) -> Boolean,
    ): String? {
        val branch = "xarvis-code/" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val writes = LinkedHashMap<String, String>()
        val transcript = StringBuilder()

        // ---- explore + edit loop (Stages 2–3) ----
        agentLoop(cfg, task, transcript, writes, generate, step)
        if (writes.isEmpty()) { say("I didn't make any file changes — ask me to be more specific about what to change."); return null }

        // ---- approval for XARVIS's own repo ----
        if (cfg.isSelf) {
            say("This is XARVIS's own app. I want to change ${writes.size} file(s):\n• " + writes.keys.joinToString("\n• ") + "\n\nApprove to push them to a safe branch (never your main code)?")
            if (!approve(writes.keys.toList())) { say("Cancelled — nothing was changed."); return null }
        }

        // ---- push to a new branch ----
        step("Creating branch $branch…")
        val cb = withContext(Dispatchers.IO) { XarvisCode.createBranch(cfg.token, cfg.owner, cfg.repo, cfg.base, branch) }
        if (!cb.ok) { say("Couldn't create the branch: ${cb.message}"); return null }
        if (!pushAll(cfg, branch, writes, task, step, say)) return null

        // ---- Stage 4: watch CI, feed errors back, fix, re-push ----
        var round = 0
        while (round < MAX_FIX_ROUNDS) {
            val outcome = watchBuild(cfg, branch, step)
            when (outcome.first) {
                BuildState.NONE -> { say(done(cfg, branch)); return branch }
                BuildState.PASS -> { say("Build passed ✓\n" + done(cfg, branch)); return branch }
                BuildState.FAIL -> {
                    round++
                    say("The build failed. Reading the errors and fixing (round $round/$MAX_FIX_ROUNDS)…")
                    val errors = outcome.second
                    writes.clear()
                    transcript.setLength(0)
                    agentLoop(cfg, "The build FAILED with these errors. Fix them:\n$errors", transcript, writes, generate, step, branch)
                    if (writes.isEmpty()) { say("I couldn't find a fix. The changes are on branch $branch.\n" + done(cfg, branch)); return branch }
                    if (!pushAll(cfg, branch, writes, "XARVIS Code: fix build", step, say)) return branch
                }
            }
        }
        say("Still failing after $MAX_FIX_ROUNDS fix attempts. The work is on branch $branch for you to look at.\n" + done(cfg, branch))
        return branch
    }

    // ---- the explore/edit loop: brain emits one action at a time ----
    private suspend fun agentLoop(
        cfg: Config, instruction: String, transcript: StringBuilder, writes: LinkedHashMap<String, String>,
        generate: suspend (String, String) -> String, step: (String) -> Unit, readBranch: String? = null,
    ): Boolean {
        var steps = 0
        while (steps < MAX_STEPS) {
            steps++
            val progress = if (transcript.isEmpty()) "(nothing yet)" else transcript.toString()
            val user = "$instruction\n\n--- progress so far ---\n$progress"
            val reply = try { withContext(Dispatchers.IO) { generate(system(cfg), user) } }
            catch (e: Exception) { step("Brain error: ${e.message}"); return false }

            val action = parse(reply)
            when (action.kind) {
                Kind.LIST -> {
                    step("Listing files…")
                    val t = withContext(Dispatchers.IO) { XarvisCode.listTree(cfg.token, cfg.owner, cfg.repo, readBranch ?: cfg.base) }
                    val paths = if (t.ok && t.data != null) XarvisCode.parseTreePaths(t.data!!) else emptyList()
                    transcript.append("\nLIST ->\n").append(paths.joinToString("\n").take(4000)).append("\n")
                }
                Kind.READ -> {
                    step("Reading ${action.path}…")
                    val f = withContext(Dispatchers.IO) { XarvisCode.getFile(cfg.token, cfg.owner, cfg.repo, action.path, readBranch) }
                    val body = if (f.ok && f.data != null) f.data!!.take(FILE_CHARS) else "(couldn't read: ${f.message})"
                    transcript.append("\nREAD ${action.path} ->\n").append(body).append("\n")
                }
                Kind.WRITE -> {
                    if (action.path.isBlank() || action.content == null) {
                        transcript.append("\n(WRITE needs a path and a ``` code block with the whole file.)\n")
                    } else {
                        step("Writing ${action.path} (${action.content.length} chars)…")
                        writes[action.path] = action.content
                        transcript.append("\nWROTE ${action.path} (${action.content.length} chars).\n")
                    }
                }
                Kind.DONE -> return true
                Kind.UNKNOWN -> transcript.append("\n(Reply with ONE action: LIST, READ <path>, WRITE <path> + code block, or DONE.)\n")
            }
        }
        return true // hit the step cap; push whatever was written
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

    /** Waits for the branch's CI run; returns its state and any error text. */
    private suspend fun watchBuild(cfg: Config, branch: String, step: (String) -> Unit): Pair<BuildState, String> {
        step("Checking the build…")
        var sawRun = false
        var headSha = ""
        repeat(80) { // up to ~7 min
            val r = withContext(Dispatchers.IO) { XarvisCode.latestRunOnBranch(cfg.token, cfg.owner, cfg.repo, branch) }
            if (r.ok && r.data != null) {
                val parts = r.data!!.split("|")
                val status = parts.getOrNull(0); val conclusion = parts.getOrNull(1); headSha = parts.getOrNull(3) ?: ""
                sawRun = true
                if (status == "completed") {
                    if (conclusion == "success") return Pair(BuildState.PASS, "")
                    val errs = withContext(Dispatchers.IO) { XarvisCode.buildErrors(cfg.token, cfg.owner, cfg.repo, headSha) }
                    return Pair(BuildState.FAIL, errs.ifBlank { "the build failed (see the repo's Actions tab)" })
                }
            } else if (!sawRun && it >= 6) {
                // ~30s and still no run for this branch — the repo probably has no CI.
                return Pair(BuildState.NONE, "")
            }
            kotlinx.coroutines.delay(5000)
        }
        return Pair(BuildState.NONE, "")
    }

    private fun done(cfg: Config, branch: String): String =
        "Changes are on branch \"$branch\" (your main code is untouched). Review or merge them here:\n" +
            "https://github.com/${cfg.owner}/${cfg.repo}/compare/${cfg.base}...$branch?expand=1\n" +
            "To discard them, just delete that branch on GitHub."

    // ---- action parsing ----
    internal enum class Kind { LIST, READ, WRITE, DONE, UNKNOWN }
    internal data class Action(val kind: Kind, val path: String = "", val content: String? = null)

    internal fun parse(reply: String): Action {
        val firstLine = reply.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return Action(Kind.UNKNOWN)
        val upper = firstLine.uppercase()
        return when {
            upper.startsWith("LIST") -> Action(Kind.LIST)
            upper.startsWith("DONE") -> Action(Kind.DONE)
            upper.startsWith("READ") -> Action(Kind.READ, path = firstLine.removePrefix(firstLine.takeWhile { it.isLetter() }).trim().trimStart(':').trim())
            upper.startsWith("WRITE") -> {
                val path = firstLine.removePrefix(firstLine.takeWhile { it.isLetter() }).trim().trimStart(':').trim()
                val code = Regex("```[a-zA-Z0-9]*\\s*([\\s\\S]*?)```").find(reply)?.groupValues?.get(1)?.trim()
                Action(Kind.WRITE, path = path, content = code)
            }
            else -> Action(Kind.UNKNOWN)
        }
    }

    private fun system(cfg: Config): String =
        "You are XARVIS Code, an autonomous coding agent working on the GitHub repository ${cfg.owner}/${cfg.repo} " +
            "(base branch ${cfg.base}). Complete the user's task by emitting EXACTLY ONE action per reply; I run it and " +
            "give you the result, then you reply again.\n" +
            "Actions:\n" +
            "LIST  — I reply with all the repository's file paths.\n" +
            "READ <path>  — I reply with that file's current contents.\n" +
            "WRITE <path>  — then on the next lines a fenced ``` code block with the COMPLETE new contents of that file. " +
            "I save it to a safe branch (never the main code).\n" +
            "DONE <one-line summary>  — when the task is fully finished.\n" +
            "Rules: explore with LIST/READ before writing; ALWAYS READ a file before you rewrite it; when you WRITE, " +
            "output the ENTIRE file (never a diff or snippet); change as few files as possible and nothing unrelated; " +
            "keep the code valid and complete. Reply with ONLY one action (plus the code block for WRITE), no chatter."
}
