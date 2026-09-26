package com.xarvis.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xarvis.ai.device.Capability
import com.xarvis.ai.llm.LlmStatus
import com.xarvis.ai.ui.theme.XarvisCyan
import com.xarvis.ai.ui.theme.XarvisMuted
import com.xarvis.ai.ui.theme.XarvisPurple
import com.xarvis.ai.ChatMessage
import com.xarvis.ai.viewmodel.XarvisViewModel

@Composable
fun XarvisScreen(viewModel: XarvisViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    fun send() {
        viewModel.submit(input)
        input = ""
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .imePadding()
            .padding(horizontal = 16.dp)
    ) {
        Header(state.isProcessing, state.memoryCount, state.linkedCount, state.llmStatus)
        CapabilityRow(state.capabilities)

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.messages) { MessageBubble(it) }
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Command…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
            Spacer(Modifier.size(8.dp))
            Button(onClick = ::send, enabled = input.isNotBlank() && !state.isProcessing) {
                Text("SEND")
            }
        }
    }
}

@Composable
private fun Header(isProcessing: Boolean, memoryCount: Int, linkedCount: Int, llmStatus: LlmStatus) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("XARVIS", style = MaterialTheme.typography.titleLarge, color = XarvisCyan)
            Spacer(Modifier.weight(1f))
            if (isProcessing) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = XarvisPurple)
                Spacer(Modifier.size(8.dp))
                Text("PROCESSING", style = MaterialTheme.typography.labelSmall, color = XarvisPurple)
            } else {
                Box(Modifier.size(8.dp).background(XarvisCyan, CircleShape))
                Spacer(Modifier.size(8.dp))
                Text("ONLINE · $memoryCount MEM · $linkedCount LINKED · B${com.xarvis.ai.BuildConfig.VERSION_CODE}", style = MaterialTheme.typography.labelSmall, color = XarvisMuted)
            }
        }
        val (label, color) = when (llmStatus) {
            LlmStatus.NotInstalled -> "AI MODEL: NOT INSTALLED" to XarvisMuted
            LlmStatus.Loading -> "AI MODEL: LOADING…" to XarvisPurple
            is LlmStatus.Ready -> "AI MODEL: GEMMA 4 E2B · ${llmStatus.backend} · ON-DEVICE" to XarvisCyan
            is LlmStatus.Failed -> "AI MODEL: FAILED TO LOAD" to MaterialTheme.colorScheme.error
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun CapabilityRow(capabilities: List<Capability>) {
    LazyRow(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(capabilities) { cap ->
            val color = if (cap.available) XarvisCyan else XarvisMuted
            Text(
                text = if (cap.detail.isEmpty()) cap.name else "${cap.name} ${cap.detail}",
                style = MaterialTheme.typography.labelSmall,
                color = color,
                modifier = Modifier
                    .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val accent = if (message.fromUser) XarvisPurple else XarvisCyan
    Box(Modifier.fillMaxWidth(), contentAlignment = if (message.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            Text(
                if (message.fromUser) "YOU" else "XARVIS",
                style = MaterialTheme.typography.labelSmall,
                color = accent,
            )
            Text(
                message.text.ifEmpty { "thinking…" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
