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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var tokenInput by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(hasToken()) }
    var login by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }
    var projectName by remember { mutableStateOf("") }
    var projectDesc by remember { mutableStateOf("") }

    fun log(line: String) { logText = (logText + "\n" + line).trim() }

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

        // ---- 2 · New project (only once connected) ----
        if (login != null) {
            Section("2 · New project")
            OutlinedTextField(
                value = projectName,
                onValueChange = { projectName = it.replace(" ", "-") },
                label = { Text("Project name (e.g. mini-xarvis)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            )
            OutlinedTextField(
                value = projectDesc, onValueChange = { projectDesc = it },
                label = { Text("What is it? (one line)") },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            Button(
                enabled = !busy && projectName.isNotBlank(),
                onClick = {
                    scope.launch {
                        busy = true; log("Creating the repository \"$projectName\"…")
                        val r = withContext(Dispatchers.IO) { XarvisCode.createRepo(token() ?: "", projectName, projectDesc) }
                        log(if (r.ok) "Created: ${r.data}" else "Couldn't create it: ${r.message}")
                        busy = false
                    }
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Create project on GitHub") }
            Text(
                "Next stage: XARVIS writes the project's code into this repo, builds it, and gives you an install link.",
                style = MaterialTheme.typography.labelSmall, color = XarvisMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
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
