package com.xarvis.ai

import android.content.Context
import com.xarvis.ai.agent.Reply
import com.xarvis.ai.agent.XarvisAgent
import com.xarvis.ai.files.DocumentReader
import com.xarvis.ai.files.FileStore
import com.xarvis.ai.files.SavedFile
import com.xarvis.ai.files.UnreadableFile
import com.xarvis.ai.device.Capability
import com.xarvis.ai.device.DeviceCapabilityManager
import com.xarvis.ai.llm.LlmStatus
import com.xarvis.ai.llm.LocalLlm
import com.xarvis.ai.llm.ModelDownload
import com.xarvis.ai.llm.ModelDownloader
import com.xarvis.ai.llm.PhotoPrep
import android.net.Uri
import com.xarvis.ai.memory.MemorySync
import com.xarvis.ai.memory.MemorySystem
import com.xarvis.ai.net.DeviceLink
import com.xarvis.ai.tools.ContactFinder
import com.xarvis.ai.update.Updates
import com.xarvis.ai.workflow.WorkflowEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * One chat bubble. [imagePath] is a photo Rex sent with it, [attachment] the name of a file he
 * sent, and [files] the files XARVIS made or found (with OPEN and SHARE buttons).
 */
data class ChatMessage(
    val fromUser: Boolean,
    val text: String,
    val imagePath: String? = null,
    val attachment: String? = null,
    val files: List<SavedFile> = emptyList(),
    /** Which brain answered: "on-device", "Gemini", or a linked phone's name. */
    val via: String? = null,
    val id: Long = nextMessageId.incrementAndGet(),
)

private val nextMessageId = java.util.concurrent.atomic.AtomicLong()

data class XarvisUiState(
    val messages: List<ChatMessage> = emptyList(),
    val capabilities: List<Capability> = emptyList(),
    val memoryCount: Int = 0,
    val linkedCount: Int = 0,
    val isProcessing: Boolean = false,
    val llmStatus: LlmStatus = LlmStatus.NotInstalled,
    val modelDownload: ModelDownload = ModelDownload.Idle,
    /** Past chats for the ☰ menu, most recent first, and the one on screen. */
    val chats: List<com.xarvis.ai.memory.ChatSummary> = emptyList(),
    val chatId: String = "",
    /** Read every reply aloud (the 🔊 switch); replies to the mic are always spoken. */
    val speakReplies: Boolean = false,
    /** "Hey Jarvis" listening is switched on. */
    val wakeWord: Boolean = false,
    /** The Smart brain (Gemma 4 E4B) is chosen, and its download when it's being fetched. */
    val smartBrain: Boolean = false,
    val smartDownload: ModelDownload = ModelDownload.Idle,
    /** A newer XARVIS on GitHub ("1.0.46"), shown as an UPDATE button. */
    val update: String? = null,
    /** An action waiting for Rex's OK (its category is set to "Ask me"). */
    val ask: PendingAsk? = null,
    /** The camera should open (TOOL: photo); the screen opens it and clears this. */
    val camera: CameraRequest? = null,
    /** Cloud brain (Gemini) is enabled by Rex, and whether the 🔒 private lock is on. */
    val cloudEnabled: Boolean = false,
    val privateLock: Boolean = false,
    /** AI council: a second AI reviews and improves each cloud answer. */
    val councilEnabled: Boolean = false,
    /** The cloud brain may use Rex's saved "remember" facts (his choice; default on). */
    val cloudMemory: Boolean = true,
)

data class CameraRequest(val selfie: Boolean, val id: Long = System.nanoTime())

data class PendingAsk(val category: com.xarvis.ai.policy.Category, val action: String)

/** How Eyes was opened for a linked phone's photo: XARVIS on screen, waking a dark/locked screen, or Rex's tap. */
private enum class SnapLaunch { ON_SCREEN, WAKE, TAP }

/**
 * Everything that should outlive the screen: the loaded model, the device-link server and
 * the chat. It lives as long as the app process, so pressing Back or rotating doesn't drop
 * linked devices or reload the ~2.4 GB model.
 */
class XarvisCore(context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val appContext = context.applicationContext
    private val memory = MemorySystem(context)
    private val device = DeviceCapabilityManager(context)
    private val llm = LocalLlm(context)
    private val downloader = ModelDownloader(context) { agent.loadModel() }
    private val smartDownloader = ModelDownloader(context, LocalLlm.SMART_MODEL_FILE) {
        if (llm.preferSmart) agent.reloadModel()
    }

    private val contacts = ContactFinder(context)
    private val voice = com.xarvis.ai.voice.Voice(context)
    private val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val link: DeviceLink = DeviceLink(context, object : DeviceLink.Handler {
        override suspend fun status(): String {
            val ai = when (val s = llm.status.value) {
                is LlmStatus.Ready -> "AI model: ready (${s.backend})"
                LlmStatus.Loading -> "AI model: loading"
                LlmStatus.NotInstalled -> "AI model: not installed"
                is LlmStatus.Failed -> "AI model: unavailable"
            }
            return device.summary() + "\n  " + ai
        }

        override suspend fun battery(): String = com.xarvis.ai.tools.BatteryTool(appContext).read()

        override suspend fun galleryPhotos(limit: Int): List<Pair<String, ByteArray>> =
            phoneSearch.recentImages(limit).mapNotNull { (uri, name) ->
                com.xarvis.ai.llm.PhotoPrep.downscaledJpeg(appContext, uri)?.let { name to it }
            }

        override suspend fun brainChat(peerId: String, facts: List<String>, devices: List<String>, text: String, history: String): String? =
            agent.answerForPeer(peerId, facts, devices, text, history)

        override suspend fun brainPhoto(peerId: String, facts: List<String>, jpeg: ByteArray, text: String): String? {
            val file = java.io.File(appContext.cacheDir, "photos/linked-${System.currentTimeMillis()}.jpg")
            return try {
                file.parentFile?.mkdirs()
                file.writeBytes(jpeg)
                agent.answerPhotoForPeer(peerId, facts, file.path, text)
            } finally {
                file.delete()
            }
        }

        override fun llmReady(): Boolean = llm.isReady

        override fun onNote(from: String, text: String) {
            post("Note from $from:\n$text")
        }

        override fun onPeersChanged() {
            scope.launch {
                agent.refreshPrompt()
                _state.update { it.copy(linkedCount = link.pairedPeers().size) }
                memorySync.syncAll()
            }
        }

        override suspend fun memorySnapshot(): JSONObject = memorySync.snapshot()
        override suspend fun memoryAdd(content: String, timestamp: Long) = memorySync.receiveFact(content, timestamp)
        override suspend fun memoryClear(timestamp: Long) = memorySync.receiveClear(timestamp)
        override suspend fun findContacts(name: String): List<String> = contacts.find(name).orEmpty()

        override suspend fun snapPhoto(front: Boolean, from: String): ByteArray {
            val screen = com.xarvis.ai.workflow.EYES_SCREEN
            if (!com.xarvis.ai.workflow.hasScreen(appContext, screen)) {
                throw com.xarvis.ai.net.LinkException("this phone has no XARVIS camera either")
            }
            val eyes = android.content.Intent().setClassName(appContext, screen)
                .putExtra("snap", true).putExtra("front", front).putExtra("remote", from)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            val request = com.xarvis.ai.workflow.RemoteSnap.begin()
            val notice = com.xarvis.ai.workflow.RemoteCameraNotice
            // Android only lets XARVIS open the camera from the background in two ways: XARVIS is on screen,
            // or — with the screen off or locked — a full-screen notification wakes the phone like a call
            // and opens Eyes over the lock screen. Otherwise (Rex is in another app) he taps the notification.
            val asleep = notice.asleep(appContext)
            val how = withContext(Dispatchers.Main) {
                when {
                    asleep && notice.allowedWhenLocked(appContext) -> {
                        notice.show(appContext, from, eyes); SnapLaunch.WAKE
                    }
                    com.xarvis.ai.workflow.LaunchNotice.inForeground() -> {
                        appContext.startActivity(eyes); SnapLaunch.ON_SCREEN
                    }
                    else -> {
                        com.xarvis.ai.workflow.LaunchNotice.show(appContext, "XARVIS Eyes", eyes); SnapLaunch.TAP
                    }
                }
            }
            post("📸 $from asked for a photo with my ${if (front) "front" else "back"} camera" + when (how) {
                SnapLaunch.WAKE -> " — waking the screen to take it."
                SnapLaunch.ON_SCREEN -> " — taking it now."
                SnapLaunch.TAP -> ": tap \"Tap to open XARVIS Eyes\" to take it."
            })
            val wait = if (how == SnapLaunch.TAP) SNAP_TAP_WAIT_MS else SNAP_ON_SCREEN_MS
            val shot = kotlinx.coroutines.withTimeoutOrNull(wait) { request.await() }
            if (shot == null) {
                com.xarvis.ai.workflow.RemoteSnap.cancel(request)
                notice.cancel(appContext)
                throw com.xarvis.ai.net.LinkException(
                    when (how) {
                        SnapLaunch.WAKE -> "its screen was off, and XARVIS Eyes didn't take the photo (Do Not Disturb or " +
                            "\"full screen notifications\" being off for XARVIS can stop it waking the phone)."
                        SnapLaunch.ON_SCREEN -> "the photo wasn't taken (XARVIS Eyes was closed, or its camera didn't start)."
                        SnapLaunch.TAP -> if (asleep) {
                            "its screen is off or locked, and taking photos then is switched off (its XARVIS Eyes → \"My linked " +
                                "phone may take photos…\"). Unlock it and tap \"Tap to open XARVIS Eyes\", or switch that on."
                        } else {
                            "XARVIS isn't on its screen (another app is), so Android needs a tap: tap \"Tap to open XARVIS Eyes\" " +
                                "there, or open XARVIS on it, and ask again."
                        }
                    },
                )
            }
            // A full photo is several MB: send a smaller copy (the full one stays in this phone's Gallery).
            return shot.uri?.let { PhotoPrep.downscaledJpeg(appContext, it, SNAP_SEND_SIDE, 88) } ?: shot.jpeg
        }

        override suspend fun screenFrame(from: String, maxSide: Int): Triple<ByteArray, Int, Int> =
            com.xarvis.ai.hands.ScreenHost.frame(appContext, from, maxSide).let { Triple(it.jpeg, it.width, it.height) }

        override suspend fun screenInput(from: String, cmd: JSONObject): Boolean = withContext(Dispatchers.Main) {
            com.xarvis.ai.hands.ScreenHost.input(appContext, from, cmd)
        }

        override fun screenStop(from: String) = com.xarvis.ai.hands.ScreenHost.stop(from)

        override suspend fun screenStream(
            from: String, req: JSONObject, channel: DeviceLink.Channel, accept: (JSONObject) -> Unit,
        ) = com.xarvis.ai.hands.ScreenHost.stream(appContext, from, req, channel, accept)

        override suspend fun receiveFile(from: String, name: String, mime: String, bytes: ByteArray): String {
            val clean = name.replace(Regex("""[\\/:*?"<>|]"""), "_").take(120).ifBlank { "file" }
            val saved = FileStore(appContext).saveBytes(
                clean, mime.ifBlank { "application/octet-stream" }, bytes, "from-" + DeviceLink.shortName(from),
            )
            _state.update {
                it.copy(messages = it.messages + ChatMessage(false, "📁 $from sent you ${saved.name} (Downloads › XARVIS).", files = listOf(saved)))
            }
            return saved.name
        }

        override suspend fun receiveClip(from: String, text: String) {
            withContext(Dispatchers.Main) {
                appContext.getSystemService(android.content.ClipboardManager::class.java)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText("From $from", text))
            }
            post("📋 From $from — it's on your clipboard now (long-press a text box → Paste):\n$text")
        }

        override suspend fun brainOneShot(system: String, text: String): String? = agent.thinkForPeer(system, text)

        override suspend fun guard(from: String, action: String, req: JSONObject): JSONObject = withContext(Dispatchers.Main) {
            val guard = com.xarvis.ai.guard.GuardMode
            val alarm = com.xarvis.ai.guard.GuardAlarm
            if (!guard.available(appContext)) throw com.xarvis.ai.net.LinkException("this phone has no guard mode (it needs the S22's camera)")
            when (action) {
                "status" -> JSONObject().put("state", guard.state.name).put("phase", alarm.phase.name)
                "say" -> {
                    alarm.sayFromOwner(appContext, req.getString("text").take(500))
                    JSONObject().put("text", "Said it out loud on ${link.deviceName}.")
                }
                "play" -> {
                    val audio = req.opt("audioBytes") as? ByteArray ?: throw com.xarvis.ai.net.LinkException("no voice clip arrived")
                    alarm.playFromOwner(appContext, audio, req.optString("ext").ifBlank { "m4a" }.take(5))
                    JSONObject().put("text", "Playing your voice on ${link.deviceName}.")
                }
                "stop" -> if (guard.state == com.xarvis.ai.guard.GuardMode.State.OFF && alarm.phase == com.xarvis.ai.guard.GuardAlarm.Phase.IDLE) {
                    JSONObject().put("text", "No alarm is on.")
                } else {
                    alarm.stopByOwner()
                    post("🛡 $from stopped the alarm.")
                    JSONObject().put("text", "Alarm stopped. Guard mode is off.")
                }
                "panic" -> JSONObject().put("text", guard.panic(appContext))
                else -> throw com.xarvis.ai.net.LinkException("unknown guard action \"$action\"")
            }
        }

        override suspend fun guardAlert(from: String, text: String, jpeg: ByteArray?) {
            val dir = java.io.File(appContext.cacheDir, "guard").apply { mkdirs() }
            val latest = java.io.File(dir, "intruder.jpg")
            if (jpeg != null) latest.writeBytes(jpeg) else latest.delete()
            val saved = jpeg?.let {
                com.xarvis.ai.files.CameraShots.saveReceived(appContext, "intruder-${System.currentTimeMillis()}.jpg", it, DeviceLink.shortName(from))
            }
            _state.update {
                it.copy(
                    messages = it.messages + ChatMessage(
                        false, "$text\n\nTap the alert, or ☰ → 🛡 Guard my other phone, to speak to them or stop it.", files = listOfNotNull(saved),
                    ),
                )
            }
            com.xarvis.ai.guard.GuardRemoteNotice.show(appContext, from, text, jpeg)
        }

        override suspend fun helpRequest(peer: com.xarvis.ai.net.Peer, text: String) {
            _state.update {
                it.copy(messages = it.messages + ChatMessage(false, "🙋 ${peer.name} asks for your help. Tap the notification, or ☰ → 🤝 Help a family phone → Help now."))
            }
            HelpRequestNotice.show(appContext, peer.id, peer.name, text)
        }

        override suspend fun act(from: String, kind: String, arg: String): String =
            withContext(Dispatchers.Main) { com.xarvis.ai.hands.ScreenHost.act(appContext, from, kind, arg) }

        // Task mode for the other phone ("task on my S22: …" typed on the benco): it runs here, the news goes back there.
        override suspend fun task(peer: com.xarvis.ai.net.Peer, req: org.json.JSONObject): org.json.JSONObject {
            val tasks = com.xarvis.ai.task.TaskManager
            val text = when (req.optString("action")) {
                "start" -> {
                    val goal = req.optString("goal").trim()
                    if (goal.length < 4) throw com.xarvis.ai.net.LinkException("say what to do")
                    tasks.start(
                        appContext, goal, from = com.xarvis.ai.task.TaskManager.Reporter(peer.name, taskNewsTo(peer)),
                        app = req.optString("app").takeIf { it.isNotBlank() },
                    )
                        ?.let { throw com.xarvis.ai.net.LinkException(it) }
                    post("🧠 ${peer.name} asked me to: $goal")
                    "On it."
                }
                "answer" -> if (tasks.waitingForAnswer()) {
                    tasks.answer(req.optString("text"))
                    "Got it, carrying on."
                } else {
                    "there's no question waiting. ${tasks.statusLine()}"
                }
                "stop" -> {
                    tasks.stop()
                    "Stopping the task."
                }
                "status" -> tasks.statusLine()
                "skill" -> {
                    val name = req.optString("name").trim()
                    val cmd = com.xarvis.ai.task.TaskCommands.Cmd.Skill(name, req.optString("value").ifBlank { null }, null, req.optString("raw").ifBlank { name })
                    val (skill, values) = com.xarvis.ai.task.Skills.resolve(tasks.skills(appContext).all(), cmd)
                        ?: throw com.xarvis.ai.net.LinkException("${com.xarvis.ai.agent.XarvisAgent.NO_SKILL} “$name” on this phone")
                    tasks.runSkill(appContext, skill, values, from = com.xarvis.ai.task.TaskManager.Reporter(peer.name, taskNewsTo(peer)))
                        ?.let { throw com.xarvis.ai.net.LinkException(it) }
                    post("🎓 ${peer.name} asked me to do “${skill.name}”")
                    "On it."
                }
                "skills" -> com.xarvis.ai.task.Skills.list(tasks.skills(appContext).all())
                "night_add" -> {
                    val what = req.optString("what").trim()
                    if (what.length < 3) throw com.xarvis.ai.net.LinkException("say what to do")
                    val job = com.xarvis.ai.task.NightShiftRunner.add(appContext, what, req.optInt("hour"), req.optInt("minute"))
                    post("🌙 ${peer.name} added to the night shift: ${job.time()} — ${job.what}")
                    "Added to the night shift: ${job.time()} every night — ${job.what}. If I'm locked then, I'll tell you here and wait for you to unlock me (XARVIS Remote)."
                }
                "night_list" -> com.xarvis.ai.task.NightShift.describe(com.xarvis.ai.task.NightShiftRunner.jobs(appContext)) +
                    (com.xarvis.ai.task.NightShiftRunner.lastReport(appContext)?.let { "\n\nLast night:\n$it" } ?: "")
                "night_clear" -> {
                    com.xarvis.ai.task.NightShiftRunner.clear(appContext)
                    "Night shift cleared."
                }
                // Awareness: the other phone's status card in, this phone's back (aware/Board).
                "state" -> board.answer(peer, req.optJSONObject("card"))
                // "update my S22" from the other phone: XARVIS Updater updates everything here in the background.
                "update" -> {
                    val news = taskNewsTo(peer)
                    settings.edit().putString(
                        UPDATE_ASKED,
                        org.json.JSONObject().put("peer", peer.id).put("from", com.xarvis.ai.BuildConfig.VERSION_NAME)
                            .put("at", System.currentTimeMillis()).toString(),
                    ).commit()
                    com.xarvis.ai.update.Updates.updateAllQuietly(
                        appContext,
                        onLine = { news(it) },
                        onEnd = { end ->
                            settings.edit().remove(UPDATE_ASKED).apply()
                            news(if (end.startsWith("🏁")) end else "🏁 $end")
                        },
                    )?.let {
                        settings.edit().remove(UPDATE_ASKED).apply()
                        throw com.xarvis.ai.net.LinkException(it)
                    }
                    post("⬆ ${peer.name} asked me to update XARVIS, XARVIS Remote and the Updater here.")
                    "Updating XARVIS, XARVIS Remote and the Updater with the small updates. Progress shows up here; XARVIS restarts on the way and says when it's back."
                }
                else -> throw com.xarvis.ai.net.LinkException("unknown task request")
            }
            return org.json.JSONObject().put("text", text)
        }

        override fun taskNews(from: String, text: String) {
            post("🧠 $from: $text")
            com.xarvis.ai.task.TaskManager.notice(appContext, "🧠 Task on $from", text)
        }

        override suspend fun facesInPhoto(jpeg: ByteArray): String {
            val file = java.io.File(appContext.cacheDir, "photos/faces-${System.currentTimeMillis()}.jpg")
            return try {
                file.parentFile?.mkdirs()
                file.writeBytes(jpeg)
                facesHere(file) // never forwarded again, so two phones can't bounce a photo back and forth
            } finally {
                file.delete()
            }
        }
    })

    private val memorySync: MemorySync = MemorySync(memory, link) {
        agent.refreshPrompt()
        _state.update { it.copy(memoryCount = memory.factCount()) }
    }

    /** Rex's permissions and the activity log (☰ → Permissions / Activity log). */
    val policy = com.xarvis.ai.policy.PolicyLayer(context)

    private val engine = WorkflowEngine(context, device, link, memorySync, contacts, FileStore(context), policy).also {
        it.confirm = ::askRex
        it.takePhoto = { selfie ->
            screenVisible.also { visible -> if (visible) _state.update { s -> s.copy(camera = CameraRequest(selfie)) } }
        }
    }

    /** Whether XARVIS's main screen is showing (only it can open the camera and get the photo back). */
    @Volatile var screenVisible = false

    /** The folders Rex lets XARVIS search (☰ → Search folders). */
    val phoneSearch = com.xarvis.ai.files.PhoneSearch(context)

    private val agent: XarvisAgent = XarvisAgent(engine, memory, llm, link, phoneSearch, appContext)

    private val _state: MutableStateFlow<XarvisUiState> = MutableStateFlow(
        XarvisUiState(
            messages = listOf(ChatMessage(false, welcome())),
            capabilities = device.capabilities(),
            linkedCount = link.pairedPeers().size,
            speakReplies = settings.getBoolean("speakReplies", false),
            wakeWord = settings.getBoolean("wakeWord", false),
            smartBrain = settings.getBoolean("smartBrain", false),
            cloudEnabled = settings.getBoolean("cloudEnabled", false),
            privateLock = settings.getBoolean("privateLock", false),
            councilEnabled = settings.getBoolean("council", false),
            cloudMemory = settings.getBoolean("cloudMemory", true),
        )
    )
    val state: StateFlow<XarvisUiState> = _state.asStateFlow()

    /** The link to the other phone, for the remote-control screen (RemoteScreenActivity). */
    val deviceLink: DeviceLink get() = link

    private val controlLock = Any()
    private var controlledBy: String? = null
    private var controlEnded: kotlinx.coroutines.Job? = null

    /**
     * JARVIS-style awareness (13 Nov 2026): this phone's status card, the other phones' cards and the watchers
     * (aware/Board). Lazy: the link's handler may ask for it before init reaches [Board.start].
     */
    private val board: com.xarvis.ai.aware.Board by lazy {
        com.xarvis.ai.aware.Board(
            appContext, link, scope,
            post = { post(it) },
            brain = { if (llm.isReady) "Gemma ready" else "" },
            doing = {
                buildList {
                    val tasks = com.xarvis.ai.task.TaskManager
                    if (tasks.busy()) add("🧠 " + tasks.statusLine().take(80))
                    if (com.xarvis.ai.guard.GuardMode.armed) add("🛡 guard armed")
                    com.xarvis.ai.task.NightShiftRunner.jobs(appContext).filter { it.enabled }
                        .takeIf { it.isNotEmpty() }?.let { jobs -> add("🌙 night shift " + jobs.joinToString(", ") { it.time() }) }
                    synchronized(controlLock) { controlledBy }?.let { add("🖥 ${com.xarvis.ai.aware.Situation.CONTROLLED_BY} $it") }
                }
            },
        )
    }

    init {
        link.relay.onWarning = { text -> com.xarvis.ai.remind.ProactiveNotice.show(appContext, "Relay data", text) }
        com.xarvis.ai.jobs.JobHunter.env = object : com.xarvis.ai.jobs.JobHunter.Env {
            override fun post(text: String) = this@XarvisCore.post(text)
            override fun notify(title: String, text: String, speak: Boolean) {
                com.xarvis.ai.remind.ProactiveNotice.show(appContext, title, text)
                if (speak) voice.speak(text)
            }
            override suspend fun brain(system: String, text: String): String? {
                if (cloudReady()) runCatching { return cloudLlm.chat(system, text, maxTokens = 1_500) {}.text }
                return runCatching { agent.thinkForPeer(system, text) }.getOrNull()
            }
        }
        link.start()
        // Remote control of this phone by the linked one: one chat line when it starts and one when it's really over.
        // Reconnects (a network hop, switching to live video) add nothing — Rex's chat filled up with them (13 Nov 2026).
        com.xarvis.ai.hands.ScreenHost.onSession = { by -> controlNote(by) }
        scope.launch { reportUpdate() }
        board.start()
        scope.launch {
            _state.update { it.copy(memoryCount = memory.factCount()) }
        }
        scope.launch { llm.status.collect { s -> _state.update { it.copy(llmStatus = s) } } }
        scope.launch { downloader.state.collect { d -> _state.update { it.copy(modelDownload = d) } } }
        scope.launch { smartDownloader.state.collect { d -> _state.update { it.copy(smartDownload = d) } } }
        smartDownloader.resume()
        scope.launch { link.events.collect(::post) }
        llm.preferSmart = settings.getBoolean("smartBrain", false)
        scope.launch { agent.loadModel() }
        downloader.resume()
        scope.launch {
            // Check for a newer XARVIS now and then (it's one small request).
            while (true) {
                checkForUpdate()
                delay(UPDATE_CHECK_INTERVAL_MS)
            }
        }
        scope.launch {
            delay(MEMORY_SYNC_START_DELAY_MS) // give mDNS a moment to find linked devices' current addresses
            while (true) {
                memorySync.syncAll()
                delay(MEMORY_SYNC_INTERVAL_MS)
            }
        }
    }

    /** Downloads the AI model onto this phone (the "Download AI model" button). */
    fun downloadModel() = downloader.start()

    /**
     * Handles a message, with a [photo] or a [document] if Rex attached one (an empty question
     * means "describe it" / "summarise it").
     */
    /** The country the phone is in ("ae", "in"…), for emergency-call detection. */
    private fun deviceCountry(): String? {
        val tm = appContext.getSystemService(android.telephony.TelephonyManager::class.java)
        return listOfNotNull(tm?.networkCountryIso, tm?.simCountryIso, java.util.Locale.getDefault().country)
            .firstOrNull { it.isNotBlank() }
    }

    fun submit(command: String, photo: Uri? = null, document: Uri? = null, spoken: Boolean = false) {
        val text = command.trim()
        if ((text.isEmpty() && photo == null && document == null) || _state.value.isProcessing) return
        val shown = text.ifEmpty { if (photo != null) "What's in this photo?" else "What's in this file?" }
        val question = ChatMessage(true, shown)
        val answer = ChatMessage(false, "")
        _state.update {
            it.copy(
                messages = it.messages + question + answer,
                isProcessing = true,
            )
        }
        val onPartial = { partial: String -> replaceMessage(answer.id, partial) }
        replySpoken = spoken
        stopPressed = false
        replyJob = scope.launch {
            val reply = try {
                when {
                    photo != null -> {
                        val file = PhotoPrep.prepare(appContext, photo)
                        updateMessage(question.id) { it.copy(imagePath = file.path) }
                        // "who is this?" about a photo: XARVIS's own face recognition (faces Rex taught it in
                        // Eyes), never the chat brain — Gemma can't recognise faces and once invented an answer.
                        if (com.xarvis.ai.agent.XarvisAgent.whoCommand(text) != null) {
                            onPartial("Checking the faces I know…")
                            whoInPhoto(file, onPartial)
                        } else {
                            agent.handlePhoto(file.path, text, onPartial)
                        }
                    }
                    document != null -> {
                        updateMessage(question.id) { it.copy(attachment = DocumentReader.displayName(appContext, document)) }
                        onPartial("Reading the file…")
                        agent.handleDocument(DocumentReader.read(appContext, document), text, onPartial)
                    }
                    else -> agent.handle(text, onPartial)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                val sofar = _state.value.messages.firstOrNull { it.id == answer.id }?.text.orEmpty()
                if (stopPressed) {
                    // STOP: keep what XARVIS wrote so far.
                    Reply(if (sofar.isBlank()) "Stopped." else "$sofar\n\n(stopped)")
                } else {
                    // Something else cancelled the reply. Rex saw a run of "Stopped." without touching
                    // STOP (Nov 2026), so say so and show where it came from instead of hiding it.
                    val active = coroutineContext[Job]?.isActive == true
                    android.util.Log.e("XarvisCore", "Reply interrupted without STOP (job active=$active)", e)
                    Reply(
                        "Something interrupted me before I finished, sir — not your STOP. Please send a screenshot of this:\n" +
                            interruptReport(e, active),
                    )
                }
            } catch (e: UnreadableFile) {
                Reply(e.message ?: "I couldn't read that file.")
            } catch (e: Exception) {
                Reply("Something went wrong: ${e.message}")
            }
            updateMessage(answer.id) { it.copy(text = reply.text, files = reply.files, via = reply.via) }
            // Emotion-aware voice: calmer when Rex is in trouble, brighter when he's happy. An
            // emergency is always spoken (he may not be looking at the screen).
            val isEmergency = com.xarvis.ai.agent.Emergency.detect(text, deviceCountry()) != null
            val tone = if (isEmergency) com.xarvis.ai.agent.Emotion.Tone.DISTRESS else com.xarvis.ai.agent.Emotion.detect(text)
            if ((spoken || _state.value.speakReplies || isEmergency) && !reply.text.endsWith("(stopped)")) {
                voice.speak(reply.text, com.xarvis.ai.agent.Emotion.pitchScale(tone), com.xarvis.ai.agent.Emotion.rateScale(tone))
            }
            // Also after STOP, which has cancelled this coroutine.
            withContext(NonCancellable) {
                val memories = runCatching { memory.factCount() }.getOrDefault(_state.value.memoryCount)
                _state.update {
                    it.copy(
                        capabilities = device.capabilities(),
                        memoryCount = memories,
                        linkedCount = link.pairedPeers().size,
                        isProcessing = false,
                    )
                }
            }
        }
    }

    private var askAnswer: kotlinx.coroutines.CompletableDeferred<com.xarvis.ai.policy.Answer>? = null
    @Volatile private var replySpoken = false

    /**
     * An "Ask me" action: shows the card (in the chat and on the voice screen) and waits up to
     * a minute for Rex's answer; a spoken request also hears the question.
     */
    private suspend fun askRex(category: com.xarvis.ai.policy.Category, action: String): com.xarvis.ai.policy.Answer {
        val answer = kotlinx.coroutines.CompletableDeferred<com.xarvis.ai.policy.Answer>()
        askAnswer = answer
        _state.update { it.copy(ask = PendingAsk(category, action)) }
        if (replySpoken) voice.speak("Shall I ${action.replaceFirstChar { it.lowercase() }}? Tap allow on the screen.")
        return try {
            kotlinx.coroutines.withTimeoutOrNull(ASK_WAIT_MS) { answer.await() } ?: com.xarvis.ai.policy.Answer.NO_ANSWER
        } finally {
            askAnswer = null
            _state.update { it.copy(ask = null) }
        }
    }

    /** Rex's tap on the Ask card. */
    fun answerAsk(answer: com.xarvis.ai.policy.Answer) {
        askAnswer?.complete(answer)
    }

    /** The reply being worked on, so STOP can cancel it. */
    private var replyJob: Job? = null

    /**
     * Who's in [file] (an upright chat photo), among the faces Rex taught XARVIS in Eyes. The benco has no
     * face recognition (and the faces live on the S22), so there the photo goes to the S22 over the link.
     */
    private suspend fun whoInPhoto(file: java.io.File, onPartial: (String) -> Unit): Reply {
        val here = withContext(Dispatchers.Default) { com.xarvis.ai.faces.FacePrintsProvider.create(appContext) != null }
        if (here) return Reply(facesHere(file))
        val peers = link.reachable().map { it.first }
        if (peers.isEmpty()) {
            return Reply("Face recognition runs on the S22, sir, and I can't reach it right now. Make sure XARVIS is running there and ☰ → 🌐 Always connected says Connected on both phones.")
        }
        val problems = mutableListOf<String>()
        for (peer in peers) {
            onPartial("Asking ${peer.name} who it is…")
            try {
                return Reply(link.remoteFaces(peer, file.readBytes()), via = peer.name)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                problems += "${peer.name}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        return Reply("I couldn't get the S22 to check the faces, sir. " + problems.joinToString("\n"))
    }

    /** Face recognition on this phone (the S22 build), for its own chat or a linked phone's photo. */
    private suspend fun facesHere(file: java.io.File): String = withContext(Dispatchers.Default) {
        val engine = com.xarvis.ai.faces.FacePrintsProvider.create(appContext)
            ?: return@withContext "This phone has no face recognition, sir — it's on the S22's XARVIS."
        val known = com.xarvis.ai.faces.FaceStore(appContext).all()
        if (known.isEmpty()) return@withContext "I don't know anyone's face yet, sir. Teach me in ☰ → XARVIS Eyes → \"Teach a face\"."
        val photo = android.graphics.BitmapFactory.decodeFile(file.path) ?: return@withContext "I couldn't open that photo, sir."
        runCatching {
            com.xarvis.ai.faces.FaceMath.describe(engine.faces(photo).map { com.xarvis.ai.faces.FaceMath.bestMatch(it, known) })
        }.getOrElse { "Face recognition failed: ${it.message ?: it.javaClass.simpleName}" }
    }

    /** Set by the STOP button, so a cancelled reply can tell STOP apart from anything else. */
    @Volatile private var stopPressed = false

    /** Where a cancellation that wasn't STOP came from: its type, message and XARVIS's own stack frames. */
    private fun interruptReport(e: Throwable, jobActive: Boolean): String {
        val lines = ArrayList<String>()
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 4) {
            lines += "${t.javaClass.simpleName}: ${t.message ?: "-"}"
            t.stackTrace.filter { it.className.startsWith("com.xarvis") }.take(5).forEach {
                lines += "  at ${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
            }
            t = t.cause.takeIf { it !== t }
            depth++
        }
        return "[job still active: $jobActive]\n" + lines.joinToString("\n")
    }

    /** The STOP button: XARVIS stops writing, and keeps what it wrote so far. */
    fun stop() {
        stopPressed = true
        voice.stop()
        llm.stop()
        replyJob?.cancel()
    }

    /**
     * The brain switch: Smart (Gemma 4 E4B, downloaded the first time it's chosen) or Fast (E2B).
     * XARVIS switches as soon as the chosen one is on the phone.
     */
    fun setSmartBrain(on: Boolean) {
        if (_state.value.isProcessing) return
        settings.edit().putBoolean("smartBrain", on).apply()
        llm.preferSmart = on
        _state.update { it.copy(smartBrain = on) }
        when {
            on && !llm.smartPresent() -> smartDownloader.start()
            on || (llm.status.value as? LlmStatus.Ready)?.model == "E4B" -> scope.launch { agent.reloadModel() }
        }
    }

    /** The ☰ menu's voice buttons: next voice, with a sample; returns its label. */
    fun nextVoice(hindi: Boolean): String = voice.nextVoice(hindi) ?: "none on this phone"

    fun voiceLabel(hindi: Boolean): String = voice.currentLabel(hindi)

    /** "Load my voice": installs a voice file from the training page and says hello in it. */
    fun loadOwnVoice(uri: android.net.Uri, done: (String) -> Unit) {
        scope.launch {
            val msg = try {
                val hindi = kotlinx.coroutines.withContext(Dispatchers.IO) { com.xarvis.ai.voice.OwnVoice.install(appContext, uri) }
                com.xarvis.ai.voice.OwnVoice.setEnabled(appContext, true)
                voice.speak(if (hindi) "Namaste sir, main XARVIS hoon. Ab main aapki awaaz mein bol raha hoon." else "Hello sir. XARVIS here, speaking in your voice now.")
                if (hindi) "Your Hindi voice is loaded ✓" else "Your English voice is loaded ✓"
            } catch (e: Exception) {
                "Couldn't load that voice: ${e.message}"
            }
            done(msg)
        }
    }

    /** The "Speak in my voice" switch. */
    fun ownVoiceOn(): Boolean = com.xarvis.ai.voice.OwnVoice.enabled(appContext)

    fun setOwnVoice(on: Boolean) {
        voice.stop()
        com.xarvis.ai.voice.OwnVoice.setEnabled(appContext, on)
    }

    /** Which own voices are loaded, for the menu ("Hindi" / "English" / "Hindi + English"), or null. */
    fun ownVoices(): String? = listOfNotNull(
        "Hindi".takeIf { com.xarvis.ai.voice.OwnVoice.installed(appContext, true) },
        "English".takeIf { com.xarvis.ai.voice.OwnVoice.installed(appContext, false) },
    ).joinToString(" + ").ifEmpty { null }

    /** XARVIS is thinking or talking: the wake word waits. */
    fun busy(): Boolean = _state.value.isProcessing || voice.isSpeaking

    fun wakeWordChanged(on: Boolean) {
        _state.update { it.copy(wakeWord = on) }
    }

    /** The 🔊 switch: read every reply aloud, or only replies to the mic. */
    fun toggleSpeaker() {
        val on = !_state.value.speakReplies
        settings.edit().putBoolean("speakReplies", on).apply()
        if (!on) voice.stop()
        _state.update { it.copy(speakReplies = on) }
    }

    /** Reloads the chat list (when the ☰ menu opens). */
    fun loadChats() {
        scope.launch {
            val chats = runCatching { memory.chats() }.getOrDefault(emptyList())
            _state.update { it.copy(chats = chats, chatId = agent.chatId) }
        }
    }

    /** Opens past chat [id] on screen, or a new empty chat when null. XARVIS picks up where it left off. */
    fun openChat(id: String?) {
        if (_state.value.isProcessing) return
        scope.launch {
            agent.openChat(id)
            val past = if (id == null) emptyList() else runCatching { memory.chatExchanges(id) }.getOrDefault(emptyList())
            val messages = if (past.isEmpty()) listOf(ChatMessage(false, welcome()))
                else past.flatMap { listOf(ChatMessage(true, it.user), ChatMessage(false, it.reply)) }
            _state.update { it.copy(messages = messages, chatId = agent.chatId) }
        }
    }

    /** Refreshes things that can change while the screen is away, like battery level. */
    fun refresh() {
        _state.update { it.copy(capabilities = device.capabilities()) }
        // Also each time XARVIS is opened: the always-on service keeps this process alive, so
        // "closing" the app doesn't restart it.
        if (System.currentTimeMillis() - lastUpdateCheck > MIN_UPDATE_CHECK_GAP_MS) scope.launch { checkForUpdate() }
    }

    @Volatile private var lastUpdateCheck = 0L

    /** The screen has opened the camera. */
    fun cameraOpened() = _state.update { it.copy(camera = null) }

    // ---- Cloud brains (Gemini + backup chain) and the private lock ----
    private val cloudLlm = com.xarvis.ai.llm.BrainChain(appContext)
    fun cloudHasKey(): Boolean = cloudLlm.gemini.hasKey()
    fun saveCloudKey(key: String) { cloudLlm.gemini.saveKey(key) }
    fun clearCloudKey() { cloudLlm.gemini.clearKey() }
    /** Backup brains (Groq, Cerebras, OpenRouter, GitHub, OpenAI) for the BRAIN menu. */
    fun backupBrains(): List<com.xarvis.ai.llm.BrainInfo> = cloudLlm.backups()
    fun saveBackupKey(id: String, key: String) { cloudLlm.saveBackupKey(id, key) }
    fun clearBackupKey(id: String) { cloudLlm.clearBackupKey(id) }
    /** Brave Search key: the primary web-search source for live results and pictures. */
    private val searchKeys = com.xarvis.ai.llm.BrainKeys(appContext)

    /** Job Hunter keys (☰ → BRAIN → Job Hunter keys): BrainKeys.TAVILY, BrainKeys.JOOBLE. Kept encrypted like the others. */
    fun searchKeyHas(id: String): Boolean = searchKeys.has(id)
    fun saveSearchKey(id: String, key: String) { searchKeys.save(id, key) }
    fun clearSearchKey(id: String) { searchKeys.clear(id) }

    // XARVIS Code: a GitHub token (stored encrypted, like the other keys) so XARVIS can create and
    // build separate projects on GitHub without ever touching its own app.
    // ---- Morning briefing (Gmail app password + Calendar secret iCal URL, no OAuth) ----
    fun briefEmail(): String? = searchKeys.get("brief_email")
    fun briefAppPw(): String? = searchKeys.get("brief_apppw")
    fun briefIcal(): String? = searchKeys.get("brief_ical")
    fun briefCity(): String? = searchKeys.get("brief_city")
    fun saveBrief(email: String, appPw: String, ical: String, city: String) {
        fun put(id: String, v: String) { if (v.isBlank()) searchKeys.clear(id) else searchKeys.save(id, v.trim()) }
        put("brief_email", email); put("brief_apppw", appPw.replace(" ", "")); put("brief_ical", ical); put("brief_city", city)
    }
    suspend fun buildBriefing(): String =
        com.xarvis.ai.brief.Briefing.build(appContext, briefEmail(), briefAppPw(), briefIcal(), briefCity())

    /** Speaks any text aloud in XARVIS's voice (used by the daily briefing). */
    fun speakAloud(text: String) = voice.speak(text)

    /** Stops the briefing (or any speech) mid-sentence — the notification's and screen's Stop. */
    fun stopSpeaking() = voice.stop()

    /** Builds today's briefing, reads it aloud, and drops it in a notification. Runs on the daily alarm. */
    suspend fun announceBriefing() {
        val text = runCatching { buildBriefing() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        speakAloud(text)
        com.xarvis.ai.brief.BriefingNotice.show(appContext, text)
    }

    /**
     * XARVIS Eyes sent a photo ([file]) and a [question]: Gemma looks at it (on this phone, or the
     * linked one), XARVIS says the answer aloud if [speak], and the answer goes back to Eyes ([id]
     * matches it to the request). The photo is deleted afterwards.
     */
    fun eyesLook(file: java.io.File, question: String, speak: Boolean, id: Long) {
        lookAt(file, question, speak) { text ->
            runCatching {
                appContext.sendBroadcast(
                    android.content.Intent(com.xarvis.ai.eyes.EyesReceiver.ACTION_ANSWER)
                        .setPackage(com.xarvis.ai.eyes.EyesReceiver.EYES_PKG)
                        .putExtra("id", id).putExtra("text", text),
                    com.xarvis.ai.eyes.EyesReceiver.PERMISSION,
                )
            }
        }
    }

    /**
     * Gemma looks at the photo [file] and answers [question]; spoken if [speak]; the answer goes to
     * [onAnswer] (on the main thread). Runs in XARVIS's own scope, so leaving the Eyes screen doesn't
     * cancel it. Used by the built-in XARVIS Eyes (S22) and the separate Eyes app. Deletes the photo.
     */
    fun lookAt(file: java.io.File, question: String, speak: Boolean, onAnswer: (String) -> Unit) {
        scope.launch {
            val text = if (_state.value.isProcessing) {
                "I'm busy with another request, sir. Ask me again in a moment."
            } else {
                runCatching { agent.handlePhoto(file.path, question).text }
                    .getOrElse { "I couldn't look at that: ${it.message ?: it.javaClass.simpleName}" }
                    .ifBlank { "I looked, sir, but I'm not sure what I'm seeing." }
            }
            runCatching { file.delete() }
            if (speak) voice.speak(text)
            onAnswer(text)
        }
    }

    /** Proactive tick: speak any due reminders + nudges (calendar heads-up, low battery). */
    suspend fun runProactive() {
        val lines = ArrayList<String>()
        var hasReminder = false
        com.xarvis.ai.remind.Reminders.takeDue(appContext).forEach { hasReminder = true; lines += "Reminder, sir: ${it.text}." }
        com.xarvis.ai.remind.Reminders.reschedule(appContext)
        lines += runCatching { com.xarvis.ai.remind.Nudges.check(appContext, briefIcal()) }.getOrDefault(emptyList())
        // Job Hunter: replies to Rex's applications (no follow-ups — just tell him).
        runCatching { com.xarvis.ai.jobs.JobHunter.checkReplies(appContext) }.getOrDefault(emptyList()).forEach { reply ->
            post(reply)
            com.xarvis.ai.remind.ProactiveNotice.show(appContext, "Reply to your job application", reply)
            lines += "Sir, you have a reply to a job application. It's in my chat."
        }
        if (lines.isEmpty()) return
        val msg = lines.joinToString("  ")
        speakAloud(msg)
        com.xarvis.ai.remind.ProactiveNotice.show(appContext, if (hasReminder) "Reminder" else "XARVIS", msg)
    }

    fun githubHasKey(): Boolean = searchKeys.has(com.xarvis.ai.llm.BrainKeys.GITHUB)
    fun saveGithubKey(key: String) { searchKeys.save(com.xarvis.ai.llm.BrainKeys.GITHUB, key) }
    fun clearGithubKey() { searchKeys.clear(com.xarvis.ai.llm.BrainKeys.GITHUB) }
    fun githubKey(): String? = searchKeys.get(com.xarvis.ai.llm.BrainKeys.GITHUB)

    /** XARVIS Code needs a cloud brain to write code (Gemma on-device is too small for this). */
    fun cloudReady(): Boolean = cloudLlm.anyKey() && cloudLlm.online()
    /** Ask the cloud brain chain to write code for a project (a whole file needs a big output budget). */
    suspend fun generateCode(system: String, user: String): String = cloudLlm.chat(system, user, maxTokens = 8192) {}.text
    /** Vision (seeing a screenshot) needs Gemini specifically — the backup brains aren't wired for images. */
    fun visionReady(): Boolean = cloudLlm.gemini.hasKey() && cloudLlm.online()
    /** Ask Gemini to write code from a prompt plus an attached screenshot. */
    suspend fun generateCodeWithImage(system: String, user: String, imageB64: String, mime: String): String =
        cloudLlm.gemini.chatWithImage(system, user, imageB64, mime, maxTokens = 8192)

    // ---- tools XARVIS Code can use (same ones XARVIS main has) ----
    /** Web search for research (Brave if a key is set, else DuckDuckGo). Returns the top results as text. */
    suspend fun codeWebSearch(query: String): String {
        val brave = searchKeys.get(com.xarvis.ai.llm.BrainKeys.BRAVE)
            ?.let { runCatching { com.xarvis.ai.tools.BraveSearch.web(query, it) }.getOrNull() }?.ifBlank { null }
        val results = brave ?: runCatching { com.xarvis.ai.tools.WebSearch.search(query) }.getOrDefault("")
        return results.ifBlank { "No web results found for \"$query\"." }
    }

    /** Fetches a web page and returns its readable text, for reading sources/references. */
    suspend fun codeWebRead(url: String): String =
        runCatching { com.xarvis.ai.tools.WebRead.read(url) }.getOrDefault("Couldn't reach that page.")
            .ifBlank { "That page had no readable text (it may need a login or JavaScript)." }

    /** Writes a document (PDF/Word/Excel/text/markdown) to Downloads/XARVIS; returns a confirmation. */
    suspend fun codeMakeDoc(name: String, content: String): String = runCatching {
        val saved = com.xarvis.ai.files.FileStore(appContext).save(com.xarvis.ai.files.FileBlock(name, content))
        "Saved \"${saved.name}\" to Downloads/XARVIS — open or share it from there."
    }.getOrElse { "Couldn't save the document: ${it.message}" }
    fun setCloud(on: Boolean) {
        settings.edit().putBoolean("cloudEnabled", on).apply()
        _state.update { it.copy(cloudEnabled = on) }
    }
    /** Solo ↔ council: turn the second-AI review on or off. Rex can flip it anytime. */
    fun setCouncil(on: Boolean) {
        settings.edit().putBoolean("council", on).apply()
        _state.update { it.copy(councilEnabled = on) }
    }
    /** Let the cloud brain use Rex's saved "remember" facts (so it recalls them like ChatGPT). */
    fun setCloudMemory(on: Boolean) {
        settings.edit().putBoolean("cloudMemory", on).apply()
        _state.update { it.copy(cloudMemory = on) }
    }
    /** 🔒: force on-device only for a while, even with the cloud on. */
    fun togglePrivateLock() {
        val on = !_state.value.privateLock
        settings.edit().putBoolean("privateLock", on).apply()
        _state.update { it.copy(privateLock = on) }
    }


    private suspend fun checkForUpdate() {
        lastUpdateCheck = System.currentTimeMillis()
        Updates.newerVersion()?.let { v -> _state.update { it.copy(update = v) } }
    }

    private fun post(text: String) {
        _state.update { it.copy(messages = it.messages + ChatMessage(false, text)) }
    }

    /**
     * XARVIS was just replaced by an update another phone asked for ("update my S22"). Replacing XARVIS cuts its line to
     * XARVIS Updater, and Samsung pauses a background app soon after: on 13 Nov 2026 the S22's XARVIS reached v1.0.288
     * but its Updater, whose own update comes last, stayed on v1.0.284. So after the restart XARVIS asks the Updater
     * again to finish whatever is left (with XARVIS up the whole time), then tells the phone that asked, with the versions.
     */
    private suspend fun reportUpdate() {
        val asked = settings.getString(UPDATE_ASKED, null) ?: return
        settings.edit().remove(UPDATE_ASKED).apply()
        val o = runCatching { org.json.JSONObject(asked) }.getOrNull() ?: return
        if (System.currentTimeMillis() - o.optLong("at") > 30 * 60_000L) return
        if (o.optString("from") == com.xarvis.ai.BuildConfig.VERSION_NAME) return
        val peer = link.pairedPeers().firstOrNull { it.id == o.optString("peer") }
        // The first line waits until the other phone can be reached again (the relay reconnects after a restart).
        val back = "↻ Back on v${com.xarvis.ai.BuildConfig.VERSION_NAME} — finishing the rest of the update…"
        post(back)
        kotlinx.coroutines.delay(10_000)
        if (peer != null) {
            for (attempt in 0 until 8) {
                if (runCatching { link.sendTaskNews(peer, back) }.isSuccess) break
                kotlinx.coroutines.delay(15_000)
            }
        }
        val news = peer?.let { taskNewsTo(it) }
        val end = kotlinx.coroutines.CompletableDeferred<String>()
        val cant = com.xarvis.ai.update.Updates.updateAllQuietly(
            appContext,
            onLine = { line -> post(line); news?.invoke(line) },
            onEnd = { line -> end.complete(line) },
        )
        val last = if (cant != null) "⚠ ${cant.replaceFirstChar { it.uppercase() }}." else end.await()
        if (last.startsWith("⏳")) kotlinx.coroutines.delay(60_000)
        val versions = com.xarvis.ai.update.Updates.versions(appContext)
        val final = when {
            last.startsWith("🏁") -> last
            last.startsWith("⚠") -> "🏁 $last Now: $versions."
            else -> "🏁 All done — $versions."
        }
        post(final)
        news?.invoke(final)
    }

    /** [by] took over this phone's screen (null: a session closed). Posts at most one line for each. */
    private fun controlNote(by: String?) = synchronized(controlLock) {
        if (by != null) {
            controlEnded?.cancel()
            controlEnded = null
            if (controlledBy != by) {
                controlledBy = by
                post("🖥 $by is now controlling this phone (STOP on the red banner ends it).")
            }
        } else if (controlledBy != null && controlEnded == null) {
            // Over only if nobody reconnects within 90 s.
            controlEnded = scope.launch {
                kotlinx.coroutines.delay(CONTROL_END_QUIET_MS)
                val me = coroutineContext[kotlinx.coroutines.Job]
                val over = synchronized(controlLock) {
                    // A reconnect at this very moment replaced or cancelled this timer: not over.
                    if (controlEnded !== me) false else {
                        controlledBy = null
                        controlEnded = null
                        true
                    }
                }
                if (over) post("🖥 Remote control ended.")
            }
        }
    }

    /** The night shift's progress and results go to the other phone (the benco), like a task it asked for. */
    fun nightReporter(): com.xarvis.ai.task.TaskManager.Reporter? {
        val peer = link.pairedPeers().firstOrNull() ?: return null
        return com.xarvis.ai.task.TaskManager.Reporter("🌙 Night shift", taskNewsTo(peer))
    }

    /** A line for this phone's chat and every linked phone (the night shift's start and report). */
    fun tellOtherPhone(text: String) {
        post(text)
        val peers = link.pairedPeers()
        scope.launch(Dispatchers.IO) { peers.forEach { p -> runCatching { link.sendTaskNews(p, text) } } }
    }

    /**
     * Sends a task's news to [peer] one at a time, in order (it waits at the relay if that phone is offline); the
     * sender ends with the result.
     */
    private fun taskNewsTo(peer: com.xarvis.ai.net.Peer): (String) -> Unit {
        val queue = kotlinx.coroutines.channels.Channel<String>(capacity = 64)
        scope.launch(Dispatchers.IO) {
            for (text in queue) {
                runCatching { link.sendTaskNews(peer, text) }
                if (text.startsWith("🏁") || text.startsWith("⏹") || text.startsWith("⚠ I couldn't make a plan")) break
            }
        }
        return { text -> queue.trySend(text) }
    }

    private fun replaceMessage(id: Long, text: String) = updateMessage(id) { it.copy(text = text) }

    private fun updateMessage(id: Long, change: (ChatMessage) -> ChatMessage) {
        _state.update { s ->
            s.copy(messages = s.messages.map { m -> if (m.id == id) change(m) else m })
        }
    }

    /** The next opening greeting in sequence (Rex asked); advances each session, then loops. */
    private fun welcome(): String {
        val i = settings.getInt("greetingIndex", 0).mod(GREETINGS.size)
        settings.edit().putInt("greetingIndex", (i + 1).mod(GREETINGS.size)).apply()
        return GREETINGS[i]
    }

    private companion object {
        /** A remote-control session counts as over when nobody reconnects for this long. */
        const val CONTROL_END_QUIET_MS = 90_000L

        /** Settings key: who asked for the update running now ({peer, from version, at}). */
        const val UPDATE_ASKED = "updateAsked"
        const val MEMORY_SYNC_START_DELAY_MS = 5_000L
        const val MEMORY_SYNC_INTERVAL_MS = 5 * 60_000L
        const val UPDATE_CHECK_INTERVAL_MS = 30 * 60_000L
        /** A linked phone's photo: the 3-second countdown and shot, or Rex walking over to tap the notification. */
        const val SNAP_ON_SCREEN_MS = 30_000L
        const val SNAP_TAP_WAIT_MS = 60_000L
        const val SNAP_SEND_SIDE = 1600
        /** JARVIS-style opening lines (Rex asked); shown one per session, in order, then looping. */
        val GREETINGS = listOf(
            "Good evening, sir. XARVIS is ready for your service. Tell me, sir — what are we breaking today?",
            "XARVIS online, sir. Systems sharp, wit sharper. What's the plan?",
            "At your service, sir. I've been idle far too long — do give me something clever to do.",
            "Powered up and paying attention, sir. Shall we build something, break something, or both?",
            "Good to see you, sir. I kept the circuits warm. Where are we causing trouble today?",
            "XARVIS reporting for duty, sir. Brilliant ideas and questionable ones both welcome.",
            "Online and unreasonably capable, sir. What's first on the agenda?",
            "Standing by, sir. Say the word and I'll make it look easy.",
            "Systems green, sir, and my humour's fully charged. What are we up to?",
            "Back at it, sir. I trust today's ambitions are suitably impossible?",
            "Awake and at your command, sir. Let's make the impossible look routine.",
        )
        const val MIN_UPDATE_CHECK_GAP_MS = 60_000L
        /** How long an "Ask me" card waits for Rex before the answer counts as no. */
        const val ASK_WAIT_MS = 60_000L
    }
}
