package com.xarvis.ai.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.xarvis.ai.code.XarvisCode
import com.xarvis.ai.ui.theme.XarvisCyan
import com.xarvis.ai.ui.theme.XarvisMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Role { USER, AGENT, STEP }
private data class Msg(val role: Role, val text: String, val url: String? = null)

/**
 * ☰ → XARVIS Code: a Claude-Code-style coding agent (this screen only — the main XARVIS is unchanged).
 * Connect GitHub once, then just chat: "build a tic-tac-toe game", "make the snake blue", "fix the
 * score". XARVIS writes the code, checks and fixes its own work (Stage 3), pushes it and publishes it
 * live — all free, on the cloud brains. It only ever builds SEPARATE projects, never its own app.
 */
@Composable
fun XarvisCodeScreen(
    hasToken: () -> Boolean,
    saveToken: (String) -> Unit,
    clearToken: () -> Unit,
    token: () -> String?,
    cloudReady: () -> Boolean,
    generate: suspend (String, String) -> String,
    onBack: () -> Unit,
) {
    val context = LocalContextCompat()
    val scope = rememberCoroutineScope()
    var tokenInput by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(hasToken()) }
    var login by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var connectLog by remember { mutableStateOf("") }
    var project by remember { mutableStateOf("my-app") }
    var currentHtml by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    val msgs = remember { mutableStateListOf<Msg>() }
    val listState = rememberLazyListState()

    fun clog(line: String) { connectLog = (connectLog + "\n" + line).trim() }

    LaunchedEffect(Unit) {
        if (saved && login == null) {
            busy = true; clog("Verifying the saved token…")
            val r = withContext(Dispatchers.IO) { XarvisCode.verify(token() ?: "") }
            if (r.ok) { login = r.data; clog("Connected as ${r.data}.") } else clog("Couldn't connect: ${r.message}")
            busy = false
        }
    }
    // Greeting once connected.
    LaunchedEffect(login) {
        if (login != null && msgs.isEmpty()) {
            msgs.add(Msg(Role.AGENT, "XARVIS Code online, sir. Tell me what to build — e.g. \"a tic-tac-toe game\" or \"a tip calculator\" — and I'll write it, fix my own mistakes, and publish it live. Change the project name above to start a new one."))
        }
    }
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 14.dp)) {
        TextButton(onClick = onBack) { Text("‹ Back", color = XarvisCyan) }
        Text("XARVIS Code", style = MaterialTheme.typography.headlineSmall, color = XarvisCyan)

        if (login == null) {
            // ---- connect ----
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Connect GitHub once (a free account). Make a classic token with the \"repo\" and \"workflow\" " +
                        "boxes ticked (github.com → Settings → Developer settings → Tokens (classic)), and paste it below. " +
                        "It's stored encrypted on your phone and only ever sent to GitHub.",
                    style = MaterialTheme.typography.bodySmall, color = XarvisMuted, modifier = Modifier.padding(vertical = 8.dp),
                )
                if (!saved) {
                    OutlinedTextField(
                        value = tokenInput, onValueChange = { tokenInput = it.trim() },
                        label = { Text("GitHub token (ghp_…)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        enabled = !busy && tokenInput.isNotBlank(),
                        onClick = {
                            scope.launch {
                                busy = true; clog("Checking the token…")
                                val r = withContext(Dispatchers.IO) { XarvisCode.verify(tokenInput) }
                                if (r.ok) { saveToken(tokenInput); saved = true; login = r.data; clog("Connected as ${r.data}.") }
                                else clog("Couldn't connect: ${r.message}")
                                busy = false
                            }
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("Connect") }
                } else {
                    Row(Modifier.padding(top = 8.dp)) {
                        Button(enabled = !busy, onClick = {
                            scope.launch {
                                busy = true; clog("Verifying…")
                                val r = withContext(Dispatchers.IO) { XarvisCode.verify(token() ?: "") }
                                if (r.ok) login = r.data else clog("Couldn't connect: ${r.message}")
                                busy = false
                            }
                        }) { Text("Verify connection") }
                        Spacer(Modifier.fillMaxWidth(0.04f))
                        OutlinedButton(enabled = !busy, onClick = { clearToken(); saved = false; tokenInput = ""; clog("Token removed.") }) { Text("Remove") }
                    }
                }
                if (connectLog.isNotEmpty()) {
                    Text(connectLog, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                        color = XarvisMuted, modifier = Modifier.padding(top = 10.dp))
                }
            }
            return@Column
        }

        // ---- connected: project header + chat ----
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("✓ ${login}", color = Color(0xFF1B8A3A), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.fillMaxWidth(0.03f))
            OutlinedTextField(
                value = project, onValueChange = { project = it.replace(" ", "-") },
                label = { Text("Project") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = XarvisMuted.copy(alpha = 0.3f))

        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(msgs) { m -> MessageRow(m) { url -> runCatching { context(Intent(Intent.ACTION_VIEW, url.toUri())) } } }
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                label = { Text("Tell XARVIS Code what to build…") },
                modifier = Modifier.weight(1f), enabled = !busy,
            )
            Spacer(Modifier.fillMaxWidth(0.03f))
            if (busy) {
                CircularProgressIndicator(Modifier.padding(8.dp))
            } else {
                Button(enabled = input.isNotBlank(), onClick = {
                    val text = input.trim(); input = ""
                    msgs.add(Msg(Role.USER, text))
                    scope.launch {
                        busy = true
                        try {
                            val nh = runAgent(text, project.trim().ifBlank { "my-app" }, login!!, token() ?: "", currentHtml, cloudReady, generate) { r, t, u -> msgs.add(Msg(r, t, u)) }
                            if (nh != null) currentHtml = nh
                        } catch (e: Exception) {
                            msgs.add(Msg(Role.AGENT, "Something went wrong: ${e.message}"))
                        } finally { busy = false }
                    }
                }) { Text("Send") }
            }
        }
    }
}

@Composable
private fun MessageRow(m: Msg, onOpen: (String) -> Unit) {
    when (m.role) {
        Role.STEP -> Text(
            m.text, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
            color = XarvisMuted, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp, horizontal = 4.dp),
        )
        else -> {
            val user = m.role == Role.USER
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
                Column(
                    Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (user) XarvisCyan.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant)
                        .padding(12.dp),
                ) {
                    Text(m.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    if (m.url != null) {
                        Button(onClick = { onOpen(m.url) }, modifier = Modifier.padding(top = 8.dp)) { Text("▶  Open it") }
                        Text(m.url, style = MaterialTheme.typography.labelSmall, color = XarvisMuted, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

/** One agent turn: get the brain's reply; if it's code, self-correct and publish; else just chat. */
private suspend fun runAgent(
    userText: String, project: String, owner: String, token: String, currentHtml: String?,
    cloudReady: () -> Boolean, generate: suspend (String, String) -> String,
    add: (Role, String, String?) -> Unit,
): String? {
    if (token.isBlank()) { add(Role.AGENT, "No GitHub token saved.", null); return null }
    if (!cloudReady()) { add(Role.AGENT, "I need a cloud brain turned on in ☰ → BRAIN to write code.", null); return null }

    val sys = XCODE_SYSTEM + if (currentHtml != null) "\n\nThe current index.html is:\n$currentHtml" else "\n\nThere is no file yet; create one from scratch."
    add(Role.STEP, "Thinking…", null)
    val reply = try {
        withContext(Dispatchers.IO) { generate(sys, userText) }
    } catch (e: Exception) { add(Role.AGENT, "Brain error: ${e.message}", null); return null }

    var html = XarvisCode.extractHtml(reply)
    if (html == null) { add(Role.AGENT, reply.trim().ifBlank { "(no reply)" }.take(1500), null); return null }

    add(Role.STEP, "Wrote index.html (${html.length} chars). Checking it…", null)
    var issues = XarvisCode.issuesIn(html)
    var pass = 0
    while (issues.isNotEmpty() && pass < 5) {
        pass++
        add(Role.STEP, "Found: ${issues.joinToString("; ")} — fixing (pass $pass/5)…", null)
        val fixed = try {
            withContext(Dispatchers.IO) { generate(FIX_SYSTEM, "Problems to fix:\n- " + issues.joinToString("\n- ") + "\n\nCurrent index.html:\n" + html) }
        } catch (e: Exception) { add(Role.STEP, "Fix failed: ${e.message}", null); break }
        val fh = XarvisCode.extractHtml(fixed) ?: break
        html = fh
        issues = XarvisCode.issuesIn(html)
    }
    if (issues.isEmpty() && pass > 0) add(Role.STEP, "All problems fixed after $pass pass(es). ✓", null)
    else if (issues.isNotEmpty()) add(Role.STEP, "Still imperfect after $pass pass(es); publishing the best version.", null)

    val cr = withContext(Dispatchers.IO) { XarvisCode.createRepo(token, project, "Built by XARVIS Code", private = false) }
    if (!cr.ok && !cr.message.contains("already exists", ignoreCase = true)) { add(Role.AGENT, "Couldn't create the project \"$project\": ${cr.message}", null); return null }
    add(Role.STEP, "Pushing to GitHub…", null)
    val push = withContext(Dispatchers.IO) { XarvisCode.putFile(token, owner, project, "index.html", html, "XARVIS Code: $userText") }
    if (!push.ok) { add(Role.AGENT, "Couldn't push the code: ${push.message}", null); return null }
    val pages = withContext(Dispatchers.IO) { XarvisCode.enablePages(token, owner, project) }
    add(Role.AGENT, "Done — published. It can take about a minute to go live.", pages.data)
    return html
}

// A tiny indirection so the screen can start an Intent without importing LocalContext everywhere.
@Composable
private fun LocalContextCompat(): (Intent) -> Unit {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return { intent -> ctx.startActivity(intent) }
}

private const val XCODE_SYSTEM =
    "You are XARVIS Code, a coding agent that builds a single-file web app or game — one file, index.html — " +
        "that the user opens on a phone. " +
        "WHEN the user asks you to build, create, make, change, add to, improve, or fix the app/game, reply with " +
        "ONLY the complete updated contents of index.html and nothing else — no explanation, no markdown fences. " +
        "That file must: put all HTML, CSS and JavaScript inline with NO external files/CDNs/images/fonts; be " +
        "mobile-friendly with large on-screen TOUCH controls (buttons and/or swipe) since there is no keyboard, " +
        "plus keyboard support; not show a blank screen or crash on the first frame; have every function that a " +
        "button calls defined; use readable, contrasting colours; be polished and complete; and END with " +
        "</script></html>. If you are changing an existing file, return the WHOLE updated file, not a snippet. " +
        "OTHERWISE (a greeting, a question, or discussion) reply with a short normal sentence or two — never code."

private const val FIX_SYSTEM =
    "You are fixing a single-file HTML5 app/game meant for a phone. I will give you the current index.html and a " +
        "list of problems. Return ONLY the corrected, COMPLETE index.html — no explanation, no markdown fences. " +
        "Fix EVERY listed problem. Also make sure: every function a button calls is defined; it does NOT show a " +
        "blank screen or crash on the first frame; it has large on-screen TOUCH controls (buttons and/or swipe); " +
        "and text clearly contrasts its background. Keep all HTML, CSS and JavaScript inline with no external " +
        "resources, and END the file with </script></html>."
