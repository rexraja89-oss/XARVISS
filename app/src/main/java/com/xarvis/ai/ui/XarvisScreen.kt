package com.xarvis.ai.ui

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.Manifest
import android.provider.Settings
import com.xarvis.ai.service.WakeWord
import com.xarvis.ai.tools.PermissionGate
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.xarvis.ai.R
import com.xarvis.ai.files.DocumentReader
import com.xarvis.ai.files.SavedFile
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.material3.ButtonDefaults
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
    var document by rememberSaveable { mutableStateOf<Uri?>(null) }
    // Android's photo picker: no storage permission needed, Rex picks one photo.
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            photo = uri
            document = null
        }
    }
    // Android's file picker: Rex picks any file (PDF, Word, Excel, text...); no storage permission needed.
    val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            document = uri
            photo = null
        }
    }
    val context = LocalContext.current
    // The mic: Android's speech recognizer listens, and XARVIS answers aloud.
    var listening by remember { mutableStateOf(false) }
    // The "Hey Jarvis" switch needs the microphone permission first.
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) WakeWord.setEnabled(context, true)
        else android.widget.Toast.makeText(context, "\"Hey Jarvis\" needs the microphone permission.", android.widget.Toast.LENGTH_LONG).show()
    }
    // "Load my voice": the voice zip made by the training page.
    val pickVoice = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.loadOwnVoice(uri) { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val listen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        listening = false
        WakeWord.paused = false
        val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!heard.isNullOrBlank()) {
            viewModel.submit(heard, photo, document, spoken = true)
            photo = null
            document = null
        }
    }
    fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to XARVIS")
        try {
            WakeWord.paused = true // the recognizer gets the mic
            listen.launch(intent)
            listening = true
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "This phone has no speech recognizer (install the Google app).", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val documentName = remember(document) { document?.let { DocumentReader.displayName(context, it) } }
    var showAttach by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // Left (☰): settings. Right (💬 or a swipe from the right edge): chats.
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val chatsDrawer = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    LaunchedEffect(chatsDrawer.isOpen) { if (chatsDrawer.isOpen) viewModel.loadChats() }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    fun send() {
        viewModel.submit(input, photo, document)
        input = ""
        photo = null
        document = null
    }

    // Compose's drawer only opens from the start side, so the chats drawer is laid out right-to-left
    // (it then slides in from the right) and everything inside it is switched back to left-to-right.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
    ModalNavigationDrawer(
        drawerState = chatsDrawer,
        // Swiping opens chats; the settings menu opens with ☰ (two swipe drawers would fight over the gesture).
        gesturesEnabled = !drawer.isOpen,
        drawerContent = {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                ChatSessions(state.chats, state.chatId, enabled = !state.isProcessing) { id ->
                    viewModel.openChat(id)
                    drawerScope.launch { chatsDrawer.close() }
                }
            }
        },
    ) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            SettingsMenu(
                enabled = !state.isProcessing,
                wakeWord = state.wakeWord,
                onWakeWord = { on ->
                    if (!on) WakeWord.setEnabled(context, false)
                    else if (!WakeWord.available(context)) android.widget.Toast.makeText(
                        context, "\"Hey Jarvis\" doesn't work on this phone's Android version yet (it needs Android 13 or older).",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                    else if (PermissionGate.has(context, Manifest.permission.RECORD_AUDIO)) WakeWord.setEnabled(context, true)
                    else askMic.launch(Manifest.permission.RECORD_AUDIO)
                },
                onAssistantSettings = { openAssistantSettings(context) },
                onRecordVoice = { context.startActivity(Intent(context, com.xarvis.ai.RecordVoiceActivity::class.java)) },
                onLoadVoice = { pickVoice.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                ownVoices = viewModel::ownVoices,
                ownVoiceOn = viewModel::ownVoiceOn,
                onOwnVoice = viewModel::setOwnVoice,
                onPermissions = { context.startActivity(Intent(context, com.xarvis.ai.PolicyActivity::class.java)) },
                onActivityLog = {
                    context.startActivity(Intent(context, com.xarvis.ai.PolicyActivity::class.java).putExtra(com.xarvis.ai.PolicyActivity.EXTRA_LOG, true))
                },
                smartBrain = state.smartBrain, smartDownload = state.smartDownload,
                hasModel = state.llmStatus != LlmStatus.NotInstalled,
                onSmartBrain = viewModel::setSmartBrain,
                voiceLabel = viewModel::voiceLabel,
                onNextVoice = viewModel::nextVoice,
            )
        },
    ) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .imePadding()
            .padding(horizontal = 16.dp)
    ) {
        Header(
            state.isProcessing, state.memoryCount, state.linkedCount, state.llmStatus,
            speaker = state.speakReplies, onSpeaker = viewModel::toggleSpeaker,
            onChats = { drawerScope.launch { chatsDrawer.open() } },
        ) {
            drawerScope.launch { drawer.open() }
        }
        state.update?.let { version ->
            // One tap: Chrome downloads the new APK once; then Open, then Update.
            Button(
                onClick = { com.xarvis.ai.update.Updates.openDownload(context) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            ) { Text("UPDATE TO v$version") }
        }
        CapabilityRow(state.capabilities)
        if (state.llmStatus == LlmStatus.NotInstalled) ModelSetup(state.modelDownload, viewModel::downloadModel)

        // The code rain welcomes a new chat, then fades away with Rex's first command.
        val newChat = state.messages.none { it.fromUser }
        val rainAlpha by animateFloatAsState(if (newChat) 1f else 0f, tween(1800), label = "rain")
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (rainAlpha > 0.01f) CodeRain(Modifier.matchParentSize(), alpha = rainAlpha)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(state.messages) { i, message ->
                    MessageBubble(message, thinking = state.isProcessing && !message.fromUser && i == state.messages.lastIndex)
                }
            }
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
        if (documentName != null) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ic_file), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(6.dp))
                Text(
                    "$documentName attached. Ask about it, or just tap SEND.",
                    style = MaterialTheme.typography.labelSmall, color = XarvisCyan, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { document = null }) { Text("REMOVE") }
            }
        }
        state.ask?.let { AskCard(it, viewModel::answerAsk) }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlusButton(enabled = !state.isProcessing) { showAttach = true }
            Spacer(Modifier.size(8.dp))
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
            if (state.isProcessing) {
                // Stops XARVIS mid-reply, keeping what it wrote so far.
                Button(
                    onClick = viewModel::stop,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("STOP") }
            } else if (input.isBlank() && photo == null && document == null) {
                // Nothing typed or attached: the mic, like ChatGPT.
                MicButton(listening = listening, enabled = true, onClick = ::startListening)
            } else {
                Button(onClick = ::send) { Text("SEND") }
            }
        }
        if (showAttach) {
            AttachSheet(
                listOf(
                    AttachOption(R.drawable.ic_photo_lens, "Photo", "Ask about a picture from your gallery") {
                        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    AttachOption(R.drawable.ic_file, "File", "Read a PDF, Word, Excel or text file, or convert it") {
                        pickDocument.launch(arrayOf("*/*"))
                    },
                ),
                onDismiss = { showAttach = false },
            )
        }
    }
    }
    }
    }
    }
}

@Composable
private fun Header(
    isProcessing: Boolean, memoryCount: Int, linkedCount: Int, llmStatus: LlmStatus,
    speaker: Boolean, onSpeaker: () -> Unit, onChats: () -> Unit, onMenu: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // ☰: settings (brain, voice, control).
            Text(
                "☰", fontSize = 26.sp, color = XarvisCyan,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onMenu).padding(end = 12.dp, top = 2.dp, bottom = 2.dp),
            )
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
            // 💬: past chats and "New chat" (the right-hand menu).
            Text(
                "💬", fontSize = 22.sp,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onChats).padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
            )
        }
        val (label, color) = when (llmStatus) {
            LlmStatus.NotInstalled -> "AI MODEL: NOT INSTALLED" to XarvisMuted
            LlmStatus.Loading -> "AI MODEL: LOADING…" to XarvisPurple
            is LlmStatus.Ready -> "AI MODEL: GEMMA 4 ${llmStatus.model} · ${llmStatus.backend} · ON-DEVICE" to XarvisCyan
            is LlmStatus.Failed -> "AI MODEL: FAILED TO LOAD" to MaterialTheme.colorScheme.error
        }
        // The version leads this line so it is never cut off; it shows which update is installed.
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "v${com.xarvis.ai.BuildConfig.VERSION_NAME} · $label", style = MaterialTheme.typography.labelSmall, color = color,
                modifier = Modifier.weight(1f), maxLines = 1,
            )
            // 🔊 reads every reply aloud; 🔇 only replies to the mic.
            Text(
                if (speaker) "🔊" else "🔇", fontSize = 20.sp,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onSpeaker).padding(start = 8.dp),
            )
        }
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

/** A message with its sender's picture: XARVIS on the left (animated while [thinking]), Rex on the right. */
@Composable
private fun MessageBubble(message: ChatMessage, thinking: Boolean) {
    val accent = if (message.fromUser) XarvisPurple else XarvisCyan
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!message.fromUser) {
            XarvisAvatar(thinking)
            Spacer(Modifier.size(8.dp))
        }
        Column(
            Modifier
                .weight(1f, fill = false)
                .widthIn(max = 300.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            message.imagePath?.let { path ->
                val bitmap = remember(path) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
                if (bitmap != null) {
                    Image(
                        bitmap, contentDescription = "Photo you sent",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(bottom = 6.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
            message.attachment?.let { name -> FileLine(name) }
            Text(
                message.text.ifEmpty { "thinking…" },
                style = MaterialTheme.typography.bodyMedium,
                color = if (message.text.isEmpty()) XarvisMuted else MaterialTheme.colorScheme.onSurface,
            )
            message.files.forEach { FileCard(it) }
        }
        if (message.fromUser) {
            Spacer(Modifier.size(8.dp))
            UserAvatar()
        }
    }
}

@Composable
private fun FileLine(name: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_file), contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(6.dp))
        Text(name, style = MaterialTheme.typography.bodyMedium, color = XarvisCyan)
    }
}

/** A file XARVIS made: its name, then OPEN (in the phone's viewer) and SHARE (WhatsApp, Gmail...). */
@Composable
private fun FileCard(file: SavedFile) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .border(1.dp, XarvisCyan.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        FileLine(file.name)
        Row {
            TextButton(onClick = { openFile(context, file) }) { Text("OPEN") }
            TextButton(onClick = { shareFile(context, file) }) { Text("SHARE") }
        }
    }
}

private fun openFile(context: Context, file: SavedFile) {
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(file.uri), file.mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startChooser(context, Intent.createChooser(view, "Open ${file.name}"))
}

private fun shareFile(context: Context, file: SavedFile) {
    val send = Intent(Intent.ACTION_SEND).setType(file.mime).putExtra(Intent.EXTRA_STREAM, Uri.parse(file.uri))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startChooser(context, Intent.createChooser(send, "Share ${file.name}"))
}

/**
 * The phone's default-apps settings, where XARVIS can be chosen as the digital assistant
 * (then the assistant button or gesture opens XARVIS Voice).
 */
private fun openAssistantSettings(context: Context) {
    val tries = listOf(
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in tries) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: Exception) {
            // try the next screen
        }
    }
}

private fun startChooser(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "No app on this phone can open it.", android.widget.Toast.LENGTH_SHORT).show()
    }
}
