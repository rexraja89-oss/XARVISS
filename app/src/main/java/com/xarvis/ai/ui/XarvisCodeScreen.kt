package com.xarvis.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import android.content.Intent
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.xarvis.ai.code.XarvisCode
import com.xarvis.ai.ui.theme.XarvisCyan
import com.xarvis.ai.ui.theme.XarvisMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ☰ → XARVIS Code. Stage 1: connect a GitHub token and create a new project repository. XARVIS only
 * ever builds SEPARATE projects here — its own app is never touched.
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tokenInput by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(hasToken()) }
    var login by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }
    var projectName by remember { mutableStateOf("") }
    var projectDesc by remember { mutableStateOf("a small fun mobile game, like snake") }
    var playUrl by remember { mutableStateOf<String?>(null) }

    fun log(line: String) { logText = (logText + "\n" + line).trim() }

    // If a token is already saved, confirm the connection on open so the build section appears
    // without Rex having to tap "Verify" every time.
    LaunchedEffect(Unit) {
        if (saved && login == null) {
            busy = true; log("Verifying the saved token…")
            val r = withContext(Dispatchers.IO) { XarvisCode.verify(token() ?: "") }
            if (r.ok) { login = r.data; log("Connected as ${r.data}.") } else log("Couldn't connect: ${r.message}")
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        TextButton(onClick = onBack) { Text("‹ Back", color = XarvisCyan) }
        Text("XARVIS Code", style = MaterialTheme.typography.headlineSmall, color = XarvisCyan)
        Text(
            "Build separate projects with AI. XARVIS writes the code and GitHub builds it — your XARVIS app is never changed, so nothing here can break it.",
            style = MaterialTheme.typography.bodySmall, color = XarvisMuted,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )

        // ---- 1 · Connect GitHub ----
        Section("1 · Connect GitHub")
        login?.let { Text("✓ Connected as $it", color = Color(0xFF1B8A3A), style = MaterialTheme.typography.bodyLarge) }

        if (!saved) {
            Text(
                "XARVIS needs a GitHub token to create and build projects for you. Make one once:\n" +
                    "• On github.com → Settings → Developer settings → Personal access tokens → Tokens (classic) → Generate new token (classic).\n" +
                    "• Tick the \"repo\" and \"workflow\" boxes, generate it, and paste it below.\n" +
                    "It's stored encrypted on your phone and only ever sent to GitHub.",
                style = MaterialTheme.typography.bodySmall, color = XarvisMuted,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            OutlinedTextField(
                value = tokenInput, onValueChange = { tokenInput = it.trim() },
                label = { Text("GitHub token (starts with ghp_…)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = !busy && tokenInput.isNotBlank(),
                onClick = {
                    scope.launch {
                        busy = true; log("Checking the token…")
                        val r = withContext(Dispatchers.IO) { XarvisCode.verify(tokenInput) }
                        if (r.ok) {
                            saveToken(tokenInput); saved = true; login = r.data
                            log("Connected as ${r.data}. Token saved.")
                        } else log("Couldn't connect: ${r.message}")
                        busy = false
                    }
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Connect") }
        } else {
            Text("A GitHub token is saved on this phone.", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.padding(top = 8.dp)) {
                Button(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true; log("Verifying the saved token…")
                            val r = withContext(Dispatchers.IO) { XarvisCode.verify(token() ?: "") }
                            if (r.ok) { login = r.data; log("Connected as ${r.data}.") }
                            else log("Couldn't connect: ${r.message}")
                            busy = false
                        }
                    },
                ) { Text("Verify connection") }
                Spacer(Modifier.fillMaxWidth(0.04f))
                OutlinedButton(
                    enabled = !busy,
                    onClick = { clearToken(); saved = false; login = null; tokenInput = ""; log("Token removed.") },
                ) { Text("Remove") }
            }
        }

        // ---- 2 · Build a project (only once connected) ----
        if (login != null) {
            Section("2 · Build a project")
            OutlinedTextField(
                value = projectName,
                onValueChange = { projectName = it.replace(" ", "-") },
                label = { Text("Project name (e.g. snake-game)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            )
            OutlinedTextField(
                value = projectDesc, onValueChange = { projectDesc = it },
                label = { Text("What should XARVIS build?") },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            Button(
                enabled = !busy && projectName.isNotBlank(),
                onClick = {
                    playUrl = null
                    scope.launch {
                        busy = true
                        try {
                            buildProject(projectName.trim(), projectDesc, login!!, token() ?: "", cloudReady, generate, ::log) { playUrl = it }
                        } catch (e: Exception) {
                            log("Something went wrong: ${e.message}")
                        } finally {
                            busy = false
                        }
                    }
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Build it with XARVIS  ▶") }
            Text(
                "XARVIS writes the game, pushes it to a new public repo, and publishes it. It may take about a minute to go live.",
                style = MaterialTheme.typography.labelSmall, color = XarvisMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
            playUrl?.let { url ->
                Button(
                    onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) } },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                ) { Text("▶  Play the game") }
                Text(url, style = MaterialTheme.typography.labelSmall, color = XarvisMuted, modifier = Modifier.padding(top = 4.dp))
            }
        }

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))

        if (logText.isNotEmpty()) {
            Section("Log")
            Text(
                logText, style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 16.dp, bottom = 8.dp), color = XarvisMuted.copy(alpha = 0.3f))
    Text(title, style = MaterialTheme.typography.titleMedium, color = XarvisCyan)
}

/**
 * The whole build: make a public repo, have the brain write the game, push index.html, publish via
 * GitHub Pages. Progress goes to [log]; on success [onPlayUrl] gets the playable URL.
 */
private suspend fun buildProject(
    repo: String, description: String, owner: String, token: String,
    cloudReady: () -> Boolean, generate: suspend (String, String) -> String,
    log: (String) -> Unit, onPlayUrl: (String) -> Unit,
) {
    if (token.isBlank()) { log("No GitHub token saved."); return }
    if (!cloudReady()) {
        log("XARVIS needs a cloud brain to write code. Turn one on in ☰ → BRAIN (e.g. your Gemini key), then try again.")
        return
    }

    log("Creating public repo \"$repo\"…")
    val cr = withContext(Dispatchers.IO) { XarvisCode.createRepo(token, repo, description.take(200), private = false) }
    when {
        cr.ok -> log("Repo created.")
        cr.message.contains("already exists", ignoreCase = true) -> log("Repo already exists — reusing it.")
        else -> { log("Couldn't create the repo: ${cr.message}"); return }
    }

    log("XARVIS is writing the game… (this can take up to a minute)")
    val reply = try {
        withContext(Dispatchers.IO) { generate(GAME_SYSTEM, "Build: ${description.ifBlank { "a small fun arcade game such as Snake" }}") }
    } catch (e: Exception) {
        log("The AI brain couldn't write it: ${e.message}"); return
    }
    val html = XarvisCode.extractHtml(reply)
    if (html == null) { log("The AI didn't return a proper game page. Tap Build again to retry."); return }
    // A cut-off reply (no closing tags) would publish a half-game that doesn't run — don't.
    if (!html.contains("</html>", ignoreCase = true) && !html.contains("</script>", ignoreCase = true)) {
        log("The game came out incomplete (the AI's reply was cut off). Tap Build again to retry."); return
    }
    log("Game written (${html.length} characters). Pushing to GitHub…")

    val push = withContext(Dispatchers.IO) { XarvisCode.putFile(token, owner, repo, "index.html", html, "XARVIS Code: the game") }
    if (!push.ok) { log("Couldn't push the file: ${push.message}"); return }
    log("Pushed. Publishing the game…")

    val pages = withContext(Dispatchers.IO) { XarvisCode.enablePages(token, owner, repo) }
    if (pages.ok && pages.data != null) {
        onPlayUrl(pages.data)
        log("Published! It can take ~1 minute to go live — then tap \"Play the game\".\n${pages.data}")
    } else {
        log("Couldn't publish the page: ${pages.message}. Give it a moment and tap Build again.")
    }
}

private const val GAME_SYSTEM =
    "You are an expert game developer. Create a COMPLETE, self-contained, single-file HTML5 game. " +
        "Output ONLY the raw contents of index.html and nothing else — no explanation, no markdown fences. " +
        "Rules: put all HTML, CSS and JavaScript inline in the one file; use <canvas> and vanilla JavaScript; " +
        "NO external files, libraries, CDNs, images or fonts of any kind. Make it mobile-friendly: it must work " +
        "with touch (large on-screen buttons or swipes) AND with the keyboard. Include a title, a visible score, " +
        "and a Restart button. " +
        "CRITICAL: the file MUST be COMPLETE — every function the HTML calls (e.g. the Play button's onclick) " +
        "must be defined, and the file MUST end with a closing </script> and </html>. Completeness matters more " +
        "than features: a simple game that fully works and is not cut off is far better than a fancy one that is " +
        "incomplete. Keep it compact — aim for about 150–250 lines so it fits in one reply."
