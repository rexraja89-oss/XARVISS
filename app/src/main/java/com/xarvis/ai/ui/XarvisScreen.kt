package com.xarvis.ai.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.heightIn
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
import com.xarvis.ai.llm.ModelDownload
import com.xarvis.ai.ui.theme.XarvisCyan
import com.xarvis.ai.ui.theme.XarvisMuted
import com.xarvis.ai.ui.theme.XarvisPurple
import com.xarvis.ai.ChatMessage
import com.xarvis.ai.viewmodel.XarvisViewModel

@Composable
fun XarvisScreen(viewModel: XarvisViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var photo by rememberSaveable { mutableStateOf<Uri?>(null) }
    // Android's photo picker: no storage permission needed, Rex picks one photo.
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) photo = uri
    }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    fun send() {
        viewModel.submit(input, photo)
        input = ""
        photo = null
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
        if (state.llmStatus == LlmStatus.NotInstalled) ModelSetup(state.modelDownload, viewModel::downloadModel)

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.messages) { MessageBubble(it) }
        }

        if (photo != null) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Photo attached. Ask about it, or just tap SEND.",
                    style = MaterialTheme.typography.labelSmall, color = XarvisCyan, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { photo = null }) { Text("REMOVE") }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !state.isProcessing,
            ) { Text("📷", style = MaterialTheme.typography.titleLarge) }
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
            Button(onClick = ::send, enabled = (input.isNotBlank() || photo != null) && !state.isProcessing) {
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
                Text("ONLINE · $memoryCount MEM · $linkedCount LINKED", style = MaterialTheme.typography.labelSmall, color = XarvisMuted)
            }
        }
        val (label, color) = when (llmStatus) {
            LlmStatus.NotInstalled -> "AI MODEL: NOT INSTALLED" to XarvisMuted
            LlmStatus.Loading -> "AI MODEL: LOADING…" to XarvisPurple
            is LlmStatus.Ready -> "AI MODEL: GEMMA 4 E2B · ${llmStatus.backend} · ON-DEVICE" to XarvisCyan
            is LlmStatus.Failed -> "AI MODEL: FAILED TO LOAD" to MaterialTheme.colorScheme.error
        }
        // The version leads this line so it is never cut off; it shows which update is installed.
        Text(
            "v${com.xarvis.ai.BuildConfig.VERSION_NAME} · $label", style = MaterialTheme.typography.labelSmall, color = color,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Shown while this phone has no AI model: a button to download it, then its progress. */
@Composable
private fun ModelSetup(download: ModelDownload, onDownload: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .border(1.dp, XarvisPurple, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        when (download) {
            is ModelDownload.Running -> {
                val gb = { bytes: Long -> "%.2f GB".format(bytes / 1_000_000_000.0) }
                val fraction = if (download.total > 0) download.done.toFloat() / download.total else 0f
                Text(
                    "Downloading AI model… ${(fraction * 100).toInt()}%" +
                        if (download.total > 0) " (${gb(download.done)} of ${gb(download.total)})" else "",
                    style = MaterialTheme.typography.bodyMedium, color = XarvisCyan,
                )
                Spacer(Modifier.size(8.dp))
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = XarvisCyan)
                Spacer(Modifier.size(6.dp))
                Text("You can leave XARVIS; the download carries on.", style = MaterialTheme.typography.labelSmall, color = XarvisMuted)
            }
            is ModelDownload.Waiting -> Text(
                "AI model download paused: ${download.why}. It continues by itself.",
                style = MaterialTheme.typography.bodyMedium, color = XarvisPurple,
            )
            else -> {
                if (download is ModelDownload.Failed) {
                    Text("The download failed: ${download.why}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.size(6.dp))
                }
                Text(
                    "This phone has no AI model yet. Download Gemma 4 (about 2.4 GB, on Wi-Fi or mobile data).",
                    style = MaterialTheme.typography.bodyMedium, color = XarvisCyan,
                )
                Spacer(Modifier.size(8.dp))
                Button(onClick = onDownload) {
                    Text(if (download is ModelDownload.Failed) "TRY AGAIN" else "DOWNLOAD AI MODEL")
                }
            }
        }
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
            message.imagePath?.let { path ->
                val bitmap = remember(path) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
                if (bitmap != null) {
                    Image(
                        bitmap, contentDescription = "Photo you sent",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(vertical = 6.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
            Text(
                message.text.ifEmpty { "thinking…" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
