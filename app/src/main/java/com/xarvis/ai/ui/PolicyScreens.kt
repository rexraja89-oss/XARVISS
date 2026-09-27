package com.xarvis.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xarvis.ai.policy.AuditRow
import com.xarvis.ai.policy.Category
import com.xarvis.ai.policy.PolicyLayer
import com.xarvis.ai.policy.PolicyRules
import com.xarvis.ai.ui.theme.XarvisCyan
import com.xarvis.ai.ui.theme.XarvisMuted
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun PolicyPage(title: String, subtitle: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(16.dp)) {
        Row {
            TextButton(onClick = onBack) { Text("‹ BACK", color = XarvisCyan) }
        }
        Text(title, style = MaterialTheme.typography.titleLarge, color = XarvisCyan)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = XarvisMuted)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

/** Every category with its level picker (Allow / Ask me / Off). */
@Composable
fun PermissionsScreen(policy: PolicyLayer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val levels by policy.levels.collectAsState()
    LaunchedEffect(Unit) { policy.load() }
    PolicyPage(
        "PERMISSIONS",
        "Allow: XARVIS just does it. Ask me: it shows a card and waits for your OK. Off: it won't.",
        onBack,
    ) {
        LazyColumn {
            items(Category.entries) { c ->
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Text(c.title, style = MaterialTheme.typography.bodyLarge)
                    Text(c.description, style = MaterialTheme.typography.labelSmall, color = XarvisMuted)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val current = levels[c] ?: PolicyRules.defaultLevel(c)
                        PolicyRules.choices(c).forEach { l ->
                            FilterChip(
                                selected = current == l,
                                onClick = { scope.launch { policy.setLevel(c, l) } },
                                label = { Text(l.label) },
                            )
                        }
                    }
                }
                HorizontalDivider(color = XarvisMuted.copy(alpha = 0.2f))
            }
        }
    }
}

/** Everything XARVIS did, newest first, with a category filter. */
@Composable
fun ActivityLogScreen(policy: PolicyLayer, onBack: () -> Unit) {
    var filter by remember { mutableStateOf<Category?>(null) }
    val rows by remember(filter) { policy.audit(filter) }.collectAsState(initial = emptyList())
    val time = remember { SimpleDateFormat("d MMM, HH:mm:ss", Locale.getDefault()) }
    PolicyPage("ACTIVITY LOG", "What XARVIS did (or was stopped from doing). Passwords and codes are never saved here.", onBack) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All") })
            Category.entries.forEach { c ->
                FilterChip(selected = filter == c, onClick = { filter = c }, label = { Text(c.title) })
            }
        }
        Spacer(Modifier.height(8.dp))
        if (rows.isEmpty()) {
            Text("Nothing yet.", style = MaterialTheme.typography.bodyMedium, color = XarvisMuted, modifier = Modifier.padding(top = 24.dp))
        }
        LazyColumn {
            items(rows, key = { it.id }) { row -> AuditItem(row, time.format(Date(row.time))) }
        }
    }
}

@Composable
private fun AuditItem(row: AuditRow, time: String) {
    val category = runCatching { Category.valueOf(row.category).title }.getOrDefault(row.category)
    val color = when (row.result) {
        "done" -> XarvisCyan
        "failed", "blocked" -> Color(0xFFFF5252)
        else -> Color(0xFFFFB74D)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(time, style = MaterialTheme.typography.labelSmall, color = XarvisMuted)
            Text(category, style = MaterialTheme.typography.labelSmall, color = XarvisMuted)
            Text(row.result.uppercase(), style = MaterialTheme.typography.labelSmall, color = color)
        }
        Text(row.action, style = MaterialTheme.typography.bodyMedium)
        Text("${row.target} · permission: ${row.level}", style = MaterialTheme.typography.labelSmall, color = XarvisMuted)
        row.failure?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Color(0xFFFF5252)) }
    }
    HorizontalDivider(color = XarvisMuted.copy(alpha = 0.2f))
}
