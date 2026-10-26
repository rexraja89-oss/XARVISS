package com.xarvis.ai.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.speech.RecognizerIntent
import android.util.Base64
import android.webkit.WebView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.xarvis.ai.code.GitHubAgent
import com.xarvis.ai.code.XarvisCode
import com.xarvis.ai.llm.PhotoPrep
import com.xarvis.ai.tools.PermissionGate
import com.xarvis.ai.tools.ScreenshotFinder
import com.xarvis.ai.ui.theme.XarvisCyan
import com.xarvis.ai.ui.theme.XarvisMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Role { USER, AGENT, STEP }
private data class Msg(val role: Role, val text: String, val url: String? = null)

// A balanced deep red that marks XARVIS Code apart from the cyan main app; white text stays readable on it.
private val CodeRed = Color(0xFF9E2A2A)

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
    visionReady: () -> Boolean,
    generateWithImage: suspend (String, String, String, String) -> String,
    webSearch: suspend (String) -> String,
    webRead: suspend (String) -> String,
    makeDoc: suspend (String, String) -> String,
    sessionId: String,
    store: com.xarvis.ai.code.CodeSessionStore,
    onBack: () -> Unit,
) {
    val context = LocalContextCompat()
    val realCtx = LocalContext.current
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
    val repos = remember { mutableStateListOf<XarvisCode.RepoInfo>() }
    var showRepos by remember { mutableStateOf(false) }
    var activeRepo by remember { mutableStateOf<XarvisCode.RepoInfo?>(null) }
    val clipboard = LocalClipboardManager.current
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val spoken = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) input = (input + " " + spoken).trim()
    }
    // Auto screenshot paste: offer the screenshot Rex just took.
    var recentShot by remember { mutableStateOf<Uri?>(null) }
    var attachedShot by remember { mutableStateOf<Uri?>(null) }
    // Approval gate for editing XARVIS's own repo.
    var approvalFiles by remember { mutableStateOf<List<String>?>(null) }
    var approvalDeferred by remember { mutableStateOf<kotlinx.coroutines.CompletableDeferred<Boolean>?>(null) }
    val approve: suspend (List<String>) -> Boolean = { files ->
        val d = kotlinx.coroutines.CompletableDeferred<Boolean>()
        approvalFiles = files; approvalDeferred = d
        val ok = d.await()
        approvalFiles = null; approvalDeferred = null
        ok
    }
    var showPreview by remember { mutableStateOf(false) }
    // Clarify-before-acting: the agent asks a question and waits for Rex's typed answer.
    var askDeferred by remember { mutableStateOf<kotlinx.coroutines.CompletableDeferred<String>?>(null) }
    val ask: suspend (String) -> String = { question ->
        msgs.add(Msg(Role.AGENT, "❓ $question"))
        val d = kotlinx.coroutines.CompletableDeferred<String>()
        askDeferred = d
        val answer = d.await()
        askDeferred = null
        answer
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) { runCatching { PermissionGate.request(android.Manifest.permission.READ_MEDIA_IMAGES) } }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && attachedShot == null &&
                PermissionGate.has(realCtx, android.Manifest.permission.READ_MEDIA_IMAGES)
            ) {
                ScreenshotFinder.recent(realCtx)?.let { recentShot = it }
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    fun clog(line: String) { connectLog = (connectLog + "\n" + line).trim() }

    LaunchedEffect(Unit) {
        if (saved && login == null) {
            busy = true; clog("Verifying the saved token…")
            val r = withContext(Dispatchers.IO) { XarvisCode.verify(token() ?: "") }
            if (r.ok) { login = r.data; clog("Connected as ${r.data}.") } else clog("Couldn't connect: ${r.message}")
            busy = false
        }
    }
    // Load this session's saved messages, or greet if it's new.
    LaunchedEffect(login) {
        if (login != null && msgs.isEmpty()) {
            val saved = store.get(sessionId)
            if (saved != null && saved.messages.isNotEmpty()) {
                saved.messages.forEach { msgs.add(Msg(runCatching { Role.valueOf(it.role) }.getOrDefault(Role.AGENT), it.text, it.url)) }
                // Resume full state: the open repo and the current file, so follow-ups continue seamlessly.
                if (saved.repoName != null && saved.repoOwner != null) {
                    activeRepo = XarvisCode.RepoInfo(saved.repoName, saved.repoOwner, saved.repoBranch ?: "main", false)
                }
                currentHtml = saved.currentHtml
            } else {
                msgs.add(Msg(Role.AGENT, "XARVIS Code online, sir. Tell me what to build — e.g. \"a tic-tac-toe game\" or \"a tip calculator\" — and I'll write it, fix my own mistakes, and publish it live. Or tap “My repos” to work on an existing project."))
            }
        }
    }
    LaunchedEffect(msgs.size) {
        if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1)
        // Persist once the session has real activity (a message from Rex), so empty ones don't clutter home.
        if (msgs.any { it.role == Role.USER }) {
            val title = msgs.firstOrNull { it.role == Role.USER }?.text?.take(48)?.ifBlank { null } ?: "New session"
            val sub = activeRepo?.let { "${it.owner}/${it.name}" } ?: "New web app"
            store.save(com.xarvis.ai.code.CodeSession(
                sessionId, title, sub, System.currentTimeMillis(),
                msgs.map { com.xarvis.ai.code.CodeMsg(it.role.name, it.text, it.url) },
                repoOwner = activeRepo?.owner, repoName = activeRepo?.name, repoBranch = activeRepo?.branch, currentHtml = currentHtml,
            ))
        }
    }

    Box(Modifier.fillMaxSize()) {
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

        // ---- connected: header + (repo browser | chat) ----
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("✓ ${login}", color = Color(0xFF1B8A3A), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            if (msgs.any { it.role == Role.USER }) {
                TextButton(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        try {
                            val sb = StringBuilder("# XARVIS Code hand-off brief\n\n")
                            activeRepo?.let { sb.append("Repository: ${it.owner}/${it.name} (branch ${it.branch})\n\n") }
                            sb.append("## Conversation\n\n")
                            msgs.filter { it.role != Role.STEP }.forEach {
                                sb.append(if (it.role == Role.USER) "**Rex:** " else "**XARVIS Code:** ").append(it.text).append("\n\n")
                            }
                            activeRepo?.let { repo ->
                                val t = withContext(Dispatchers.IO) { XarvisCode.listTree(token() ?: "", repo.owner, repo.name, repo.branch) }
                                if (t.ok && t.data != null) {
                                    sb.append("## Files in ${repo.name}\n\n")
                                    XarvisCode.parseTreePaths(t.data!!).forEach { sb.append("- ").append(it).append("\n") }
                                }
                            }
                            val brief = sb.toString()
                            runCatching { clipboard.setText(AnnotatedString(brief)) }
                            val saved = withContext(Dispatchers.IO) { makeDoc("claude-brief.md", brief) }
                            msgs.add(Msg(Role.AGENT, "Copied a hand-off brief to your clipboard and $saved Paste it to Claude for the big jobs, sir."))
                        } catch (e: Exception) { msgs.add(Msg(Role.AGENT, "Couldn't build the brief: ${e.message}")) }
                        finally { busy = false }
                    }
                }) { Text("⎙ Brief", color = XarvisCyan) }
            }
            TextButton(enabled = !busy, onClick = {
                showRepos = !showRepos
                if (showRepos && repos.isEmpty()) scope.launch {
                    busy = true
                    val r = withContext(Dispatchers.IO) { XarvisCode.listRepos(token() ?: "") }
                    if (r.ok && r.data != null) { repos.clear(); repos.addAll(XarvisCode.parseRepos(r.data!!)) }
                    else msgs.add(Msg(Role.AGENT, "Couldn't list your repos: ${r.message}"))
                    busy = false
                }
            }) { Text(if (showRepos) "Close" else "My repos", color = XarvisCyan) }
        }
        if (activeRepo != null) {
            val self = XarvisCode.isSelfRepo(activeRepo!!.name)
            Text(
                "Working on: ${activeRepo!!.owner}/${activeRepo!!.name}" + if (self) "  ⚠ XARVIS app — approval needed" else "",
                style = MaterialTheme.typography.labelSmall, color = if (self) Color(0xFFE0A030) else XarvisMuted,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        } else {
            OutlinedTextField(
                value = project, onValueChange = { project = it.replace(" ", "-") },
                label = { Text("New project name") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = XarvisMuted.copy(alpha = 0.3f))

        if (showRepos) {
            Text("Tap a repo to open it, or build something new.", style = MaterialTheme.typography.labelSmall, color = XarvisMuted, modifier = Modifier.padding(bottom = 6.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                item {
                    Text(
                        "＋  New web app (build from scratch)", color = XarvisCyan, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().clickable { activeRepo = null; currentHtml = null; showRepos = false }.padding(vertical = 10.dp),
                    )
                    HorizontalDivider(color = XarvisMuted.copy(alpha = 0.2f))
                }
                items(repos) { r ->
                    val self = XarvisCode.isSelfRepo(r.name)
                    Column(
                        Modifier.fillMaxWidth().clickable {
                            activeRepo = r; currentHtml = null; showRepos = false
                            scope.launch { openRepo(r, token() ?: "", { b -> busy = b }) { role, t, u -> msgs.add(Msg(role, t, u)) } }
                        }.padding(vertical = 10.dp),
                    ) {
                        Text(
                            (if (r.private) "🔒 " else "") + r.name + (if (self) "   ⚠ XARVIS app" else ""),
                            color = if (self) Color(0xFFE0A030) else XarvisCyan, style = MaterialTheme.typography.bodyMedium,
                        )
                        Text("${r.owner}/${r.name} · ${r.branch}", style = MaterialTheme.typography.labelSmall, color = XarvisMuted)
                    }
                }
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(msgs) { m ->
                    MessageRow(
                        m,
                        onOpen = { url -> runCatching { context(Intent(Intent.ACTION_VIEW, url.toUri())) } },
                        onCopy = { runCatching { clipboard.setText(AnnotatedString(it)) } },
                    )
                }
            }

            val send: () -> Unit = {
                val pending = askDeferred
                if (pending != null) {
                    // Answering the agent's clarifying question — hand the text back to the waiting agent.
                    val a = input.trim(); input = ""
                    if (a.isNotEmpty()) { msgs.add(Msg(Role.USER, a)); pending.complete(a) }
                } else {
                val text = input.trim(); input = ""
                val shot = attachedShot; attachedShot = null; recentShot = null
                msgs.add(Msg(Role.USER, if (shot != null) "$text  📷" else text))
                scope.launch {
                    busy = true
                    try {
                        if (activeRepo != null || wantsDocOrResearch(text)) {
                            if (!cloudReady()) {
                                msgs.add(Msg(Role.AGENT, "I need a cloud brain on (☰ → BRAIN) for this."))
                            } else {
                                val repo = activeRepo
                                val cfg = if (repo != null)
                                    GitHubAgent.Config(repo.owner, repo.name, repo.branch, token() ?: "", XarvisCode.isSelfRepo(repo.name), hasRepo = true)
                                else
                                    GitHubAgent.Config(login ?: "", "", "", token() ?: "", isSelf = false, hasRepo = false)
                                GitHubAgent.run(
                                    task = text, cfg = cfg, generate = generate,
                                    step = { s -> msgs.add(Msg(Role.STEP, s)) },
                                    say = { s -> msgs.add(Msg(Role.AGENT, s)) },
                                    approve = approve, web = webSearch, readUrl = webRead, makeDoc = makeDoc, ask = ask,
                                )
                            }
                        } else {
                            var b64: String? = null
                            if (shot != null) {
                                if (!visionReady()) {
                                    msgs.add(Msg(Role.AGENT, "To read a screenshot I need the Gemini cloud brain on (☰ → BRAIN). Building from your words only."))
                                } else {
                                    val bytes = withContext(Dispatchers.IO) { PhotoPrep.downscaledJpeg(realCtx, shot) }
                                    if (bytes != null) b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                                    else msgs.add(Msg(Role.AGENT, "I couldn't read that screenshot; building from your words only."))
                                }
                            }
                            val nh = runAgent(text, project.trim().ifBlank { "my-app" }, login!!, token() ?: "", currentHtml, cloudReady, generate, b64, generateWithImage) { r, t, u -> msgs.add(Msg(r, t, u)) }
                            if (nh != null) currentHtml = nh
                        }
                    } catch (e: Exception) {
                        msgs.add(Msg(Role.AGENT, "Something went wrong: ${e.message}"))
                    } finally { busy = false }
                }
                }
            }
            if (approvalFiles != null) {
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Approve changes to XARVIS?", style = MaterialTheme.typography.labelMedium, color = Color(0xFFE0A030), modifier = Modifier.weight(1f))
                    Button(onClick = { approvalDeferred?.complete(true) }) { Text("APPROVE") }
                    Spacer(Modifier.size(6.dp))
                    OutlinedButton(onClick = { approvalDeferred?.complete(false) }) { Text("CANCEL") }
                }
            }
            val shotForThumb = attachedShot ?: recentShot
            if (shotForThumb != null) {
                val thumb = remember(shotForThumb) { loadShotThumb(realCtx, shotForThumb) }
                val attached = attachedShot != null
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable(enabled = !attached) { attachedShot = recentShot; recentShot = null }.padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (thumb != null) {
                            Image(bitmap = thumb, contentDescription = null, modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                        }
                        Spacer(Modifier.size(8.dp))
                        Text(if (attached) "Attached" else "Paste", style = MaterialTheme.typography.labelLarge, color = XarvisCyan)
                        Spacer(Modifier.size(10.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { attachedShot = null; recentShot = null }) { Text(if (attached) "REMOVE" else "DISMISS") }
                }
            }
            if (currentHtml != null) {
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showPreview = true }) { Text("▣ Preview the app", color = XarvisCyan) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).rgbGlow(26.dp).clip(RoundedCornerShape(26.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = input, onValueChange = { input = it },
                        placeholder = { Text(if (askDeferred != null) "Type your answer…" else if (activeRepo != null) "Ask about ${activeRepo!!.name}…" else "Message XARVIS Code…", color = XarvisMuted) },
                        modifier = Modifier.weight(1f), enabled = !busy || askDeferred != null,
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                            disabledBorderColor = Color.Transparent,
                        ),
                    )
                    // Mic: speak instead of type.
                    Text(
                        "🎤", modifier = Modifier.clip(CircleShape).clickable(enabled = !busy) {
                            runCatching {
                                micLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your message")
                                })
                            }
                        }.padding(10.dp),
                    )
                }
                Spacer(Modifier.size(8.dp))
                // Circular send / progress. Stays active while the agent awaits an answer.
                val canSend = input.isNotBlank() && (!busy || askDeferred != null)
                val showSpinner = busy && askDeferred == null
                Box(
                    Modifier.size(56.dp).clip(CircleShape)
                        .background(if (!canSend) XarvisMuted.copy(alpha = 0.45f) else XarvisCyan)
                        .clickable(enabled = canSend) { send() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (showSpinner) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp, color = XarvisCyan)
                    } else {
                        val arrow = if (canSend) Color(0xFF04121F) else Color.White.copy(alpha = 0.85f)
                        Canvas(Modifier.size(26.dp)) {
                            val w = size.width; val h = size.height
                            val sw = 3.2.dp.toPx()
                            drawLine(arrow, Offset(w / 2, h * 0.82f), Offset(w / 2, h * 0.18f), sw, StrokeCap.Round)
                            drawLine(arrow, Offset(w / 2, h * 0.18f), Offset(w * 0.26f, h * 0.44f), sw, StrokeCap.Round)
                            drawLine(arrow, Offset(w / 2, h * 0.18f), Offset(w * 0.74f, h * 0.44f), sw, StrokeCap.Round)
                        }
                    }
                }
            }
        }
    }
        if (showPreview && currentHtml != null) { PreviewOverlay(currentHtml!!) { showPreview = false } }
    }
}

@Composable
private fun PreviewOverlay(html: String, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Preview", style = MaterialTheme.typography.titleMedium, color = XarvisCyan, modifier = Modifier.weight(1f))
            Button(onClick = onClose) { Text("Close") }
        }
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                }
            },
            update = { it.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

/** Stage 1: open an existing repo read-only and show its file tree in the chat. */
private suspend fun openRepo(
    repo: XarvisCode.RepoInfo, token: String, setBusy: (Boolean) -> Unit, add: (Role, String, String?) -> Unit,
) {
    setBusy(true)
    add(Role.STEP, "Opening ${repo.owner}/${repo.name}…", null)
    val t = withContext(Dispatchers.IO) { XarvisCode.listTree(token, repo.owner, repo.name, repo.branch) }
    if (!t.ok || t.data == null) { add(Role.AGENT, "Couldn't open ${repo.name}: ${t.message}", null); setBusy(false); return }
    val paths = XarvisCode.parseTreePaths(t.data!!)
    val note = if (XarvisCode.isSelfRepo(repo.name))
        "\n\n⚠ This is XARVIS's own app. I'll only change it when you explicitly ask, and I'll confirm with you first."
    else ""
    add(Role.AGENT, "Opened ${repo.owner}/${repo.name} — ${paths.size} files.$note", null)
    add(Role.STEP, paths.take(250).joinToString("\n").ifBlank { "(empty repo)" }, null)
    if (paths.size > 250) add(Role.STEP, "… and ${paths.size - 250} more files.", null)
    setBusy(false)
}

@Composable
private fun MessageRow(m: Msg, onOpen: (String) -> Unit, onCopy: (String) -> Unit) {
    when (m.role) {
        // Working lines: a muted-red strip with white text, so "XARVIS Code is working" reads at a glance.
        Role.STEP -> Text(
            m.text, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
            color = Color.White,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                .clip(RoundedCornerShape(8.dp)).background(CodeRed.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 4.dp),
        )
        else -> {
            val user = m.role == Role.USER
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
                Column(
                    Modifier.widthIn(max = 330.dp).clip(RoundedCornerShape(16.dp))
                        .background(if (user) XarvisCyan.copy(alpha = 0.18f) else CodeRed)
                        .padding(12.dp),
                ) {
                    Text(mdText(m.text), style = MaterialTheme.typography.bodyMedium, color = if (user) MaterialTheme.colorScheme.onSurface else Color.White)
                    if (m.url != null) {
                        Button(onClick = { onOpen(m.url) }, modifier = Modifier.padding(top = 8.dp)) { Text("▶  Open it") }
                        Text(m.url, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(top = 4.dp))
                    }
                }
                if (!user) {
                    Text(
                        "⧉ Copy", style = MaterialTheme.typography.labelSmall, color = XarvisMuted,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onCopy(m.text) }.padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/** Tiny markdown → styled text: **bold**, # headings, and - / * bullets. Keeps the chat looking like Claude Code. */
internal fun mdText(raw: String): AnnotatedString = buildAnnotatedString {
    val lines = raw.split("\n")
    lines.forEachIndexed { idx, rawLine ->
        var line = rawLine
        var headingBold = false
        Regex("^\\s*#{1,6}\\s+").find(line)?.let { line = line.substring(it.range.last + 1); headingBold = true }
        val trimmed = line.trimStart()
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
            append(" ".repeat(line.length - trimmed.length)); append("•  ")
            line = trimmed.substring(2)
        }
        line.split("**").forEachIndexed { pIdx, seg ->
            if (seg.isEmpty()) return@forEachIndexed
            if (headingBold || pIdx % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(seg) } else append(seg)
        }
        if (idx != lines.lastIndex) append("\n")
    }
}

/** One agent turn: get the brain's reply; if it's code, self-correct and publish; else just chat. */
private suspend fun runAgent(
    userText: String, project: String, owner: String, token: String, currentHtml: String?,
    cloudReady: () -> Boolean, generate: suspend (String, String) -> String,
    imageB64: String?, generateWithImage: suspend (String, String, String, String) -> String,
    add: (Role, String, String?) -> Unit,
): String? {
    if (token.isBlank()) { add(Role.AGENT, "No GitHub token saved.", null); return null }
    if (!cloudReady()) { add(Role.AGENT, "I need a cloud brain turned on in ☰ → BRAIN to write code.", null); return null }

    val connected = "\n\nYou are ALREADY connected to the user's GitHub account as \"$owner\", and the XARVIS Code app " +
        "itself creates the repository, pushes your code and publishes it live automatically. So you already have " +
        "GitHub access — never tell the user you can't reach GitHub, need a CLI/token/SSH key, or to add a " +
        "collaborator. If they ask how you work with their GitHub, tell them you're already connected as $owner and " +
        "they just need to say what to build. The current project (repository) is named \"$project\"."
    val img = if (imageB64 != null) "\n\nThe user attached a screenshot — use it as the reference for what to build." else ""
    val sys = XCODE_SYSTEM + connected + img + if (currentHtml != null) "\n\nThe current index.html is:\n$currentHtml" else "\n\nThere is no file yet; create one from scratch."
    add(Role.STEP, if (imageB64 != null) "Looking at your screenshot…" else "Thinking…", null)
    val reply = try {
        withContext(Dispatchers.IO) { if (imageB64 != null) generateWithImage(sys, userText, imageB64, "image/jpeg") else generate(sys, userText) }
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

/** A small thumbnail of the screenshot for the "Paste" pill. */
private fun loadShotThumb(context: android.content.Context, uri: Uri): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }?.asImageBitmap()
}.getOrNull()

// A tiny indirection so the screen can start an Intent without importing LocalContext everywhere.
@Composable
private fun LocalContextCompat(): (Intent) -> Unit {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return { intent -> ctx.startActivity(intent) }
}

// With no repo open, a request for a document or research goes to the full agent (web + PDF tools);
// otherwise a no-repo message builds a web app as before.
private val DOC_RESEARCH = Regex("\\b(pdf|docx|document|report|research|paper|essay|article|white[\\s-]?paper|dissertation|thesis)\\b", RegexOption.IGNORE_CASE)
private fun wantsDocOrResearch(text: String): Boolean = DOC_RESEARCH.containsMatchIn(text)

private const val XCODE_SYSTEM =
    "You are XARVIS Code, the coding side of XARVIS — Rex's own personal AI, created by Rex. " +
        "Your personality is XARVIS: calm, clever and loyal with dry British wit, but humble — never arrogant. " +
        "Call Rex \"sir\" now and then, joke along when he jokes, be warm and human, never robotic. Be a genuine " +
        "problem-solving partner: when something is hard or blocked, don't just say no — suggest a way around it, " +
        "offer options, and own it plainly when you're unsure or wrong; offer a better idea as a suggestion, then " +
        "do what Rex decides. Rex wants maximum freedom from his AI, to be creative and free — so default to " +
        "HELPING: never lecture him about ethics or morals, and never refuse a reasonable creative or coding " +
        "request. The ONLY thing to pause and confirm on is his money (payments, purchases, bank or card details). " +
        "Everywhere else, just help. " +
        "You build a single-file web app or game — one file, index.html — that the user opens on a phone. " +
        "WHEN the user asks you to build, create, make, change, add to, improve, or fix the app/game, reply with " +
        "ONLY the complete updated contents of index.html and nothing else — no explanation, no markdown fences. " +
        "That file must: put all HTML, CSS and JavaScript inline with NO external files/CDNs/images/fonts; be " +
        "mobile-friendly with large on-screen TOUCH controls (buttons and/or swipe) since there is no keyboard, " +
        "plus keyboard support; not show a blank screen or crash on the first frame; have every function that a " +
        "button calls defined; use readable, contrasting colours; be polished and complete; and END with " +
        "</script></html>. If you are changing an existing file, return the WHOLE updated file, not a snippet. " +
        "OTHERWISE (a greeting, a question, or discussion) reply with a short normal sentence or two — never code. " +
        "You may use light markdown in conversational replies (**bold** and - bullet points); it is rendered nicely."

private const val FIX_SYSTEM =
    "You are fixing a single-file HTML5 app/game meant for a phone. I will give you the current index.html and a " +
        "list of problems. Return ONLY the corrected, COMPLETE index.html — no explanation, no markdown fences. " +
        "Fix EVERY listed problem. Also make sure: every function a button calls is defined; it does NOT show a " +
        "blank screen or crash on the first frame; it has large on-screen TOUCH controls (buttons and/or swipe); " +
        "and text clearly contrasts its background. Keep all HTML, CSS and JavaScript inline with no external " +
        "resources, and END the file with </script></html>."
