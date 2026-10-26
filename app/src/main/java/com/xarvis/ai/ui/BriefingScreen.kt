package com.xarvis.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.xarvis.ai.ui.theme.XarvisCyan
import com.xarvis.ai.ui.theme.XarvisMuted
import kotlinx.coroutines.launch

/** ☰ → Morning briefing: connect Gmail (app password) + Calendar (secret iCal) and test the briefing. */
@Composable
fun BriefingScreen(
    email: () -> String?, appPw: () -> String?, ical: () -> String?, city: () -> String?,
    save: (String, String, String, String) -> Unit,
    buildBriefing: suspend () -> String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var em by remember { mutableStateOf(email() ?: "") }
    var pw by remember { mutableStateOf(appPw() ?: "") }
    var ic by remember { mutableStateOf(ical() ?: "") }
    var ct by remember { mutableStateOf(city() ?: "") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        TextButton(onClick = onBack) { Text("‹ Back", color = XarvisCyan) }
        Text("Morning briefing", style = MaterialTheme.typography.headlineSmall, color = XarvisCyan)
        Text(
            "Connect these once (all free, stored encrypted on your phone) and I'll read you a briefing. Setup steps are below each box.",
            style = MaterialTheme.typography.bodySmall, color = XarvisMuted, modifier = Modifier.padding(vertical = 8.dp),
        )

        OutlinedTextField(em, { em = it.trim() }, label = { Text("Your Gmail address") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        OutlinedTextField(pw, { pw = it }, label = { Text("Gmail App Password (16 letters)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        Hint("Google Account → Security → 2-Step Verification → App passwords → create one for \"Mail\". Needs 2-Step on (you have it).")

        OutlinedTextField(ic, { ic = it.trim() }, label = { Text("Calendar secret iCal URL") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        Hint("Google Calendar (on a computer) → Settings → your calendar → \"Secret address in iCal format\" → copy the .ics link.")

        OutlinedTextField(ct, { ct = it }, label = { Text("Your city (for weather)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))

        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { save(em, pw, ic, ct) }) { Text("Save") }
            Spacer(Modifier.size(10.dp))
            if (busy) CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp, color = XarvisCyan)
            else Button(onClick = {
                save(em, pw, ic, ct)
                scope.launch { busy = true; result = runCatching { buildBriefing() }.getOrElse { "Error: ${it.message}" }; busy = false }
            }) { Text("Test briefing now") }
        }

        if (result.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = XarvisMuted.copy(alpha = 0.3f))
            Text("Your briefing:", style = MaterialTheme.typography.labelMedium, color = XarvisCyan)
            Text(result, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 6.dp, bottom = 24.dp))
        } else {
            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = XarvisMuted, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
}
