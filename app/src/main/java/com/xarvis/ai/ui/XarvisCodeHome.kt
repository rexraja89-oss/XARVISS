package com.xarvis.ai.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.xarvis.ai.code.CodeSession
import com.xarvis.ai.code.CodeSessionStore
import com.xarvis.ai.ui.theme.XarvisCyan
import com.xarvis.ai.ui.theme.XarvisMuted

/** XARVIS Code's home: a list of past sessions to reopen, plus New session — like the Claude Code app. */
@Composable
fun XarvisCodeHome(
    store: CodeSessionStore,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
) {
    var sessions by remember { mutableStateOf(store.list()) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back", color = XarvisCyan) }
            Spacer(Modifier.weight(1f))
        }
        Text("XARVIS Code", style = MaterialTheme.typography.headlineSmall, color = XarvisCyan)

        Button(onClick = onNew, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) { Text("＋  New session") }

        Text("SESSIONS", style = MaterialTheme.typography.labelMedium, color = XarvisMuted, modifier = Modifier.padding(vertical = 6.dp))

        if (sessions.isEmpty()) {
            Text("No sessions yet. Tap “New session” to start building.", style = MaterialTheme.typography.bodySmall, color = XarvisMuted)
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sessions) { s ->
                    SessionRow(
                        s,
                        onOpen = { onOpen(s.id) },
                        onDelete = { store.delete(s.id); sessions = store.list() },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionRow(s: CodeSession, onOpen: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpen).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) { Text("</>", fontFamily = FontFamily.Monospace, color = XarvisCyan, style = MaterialTheme.typography.labelMedium) }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title.ifBlank { "New session" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            Text("${s.subtitle} · ${ago(s.updatedAt)}", style = MaterialTheme.typography.labelSmall, color = XarvisMuted, maxLines = 1)
        }
        TextButton(onClick = onDelete) { Text("✕", color = XarvisMuted) }
    }
}

private fun ago(millis: Long): String {
    val d = System.currentTimeMillis() - millis
    return when {
        d < 60_000 -> "just now"
        d < 3_600_000 -> "${d / 60_000}m"
        d < 86_400_000 -> "${d / 3_600_000}h"
        else -> "${d / 86_400_000}d"
    }
}
