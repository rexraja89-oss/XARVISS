package com.xarvis.ai.mission

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.xarvis.ai.BuildConfig
import com.xarvis.ai.R
import com.xarvis.ai.XarvisApp
import com.xarvis.ai.agent.LocalPhone
import com.xarvis.ai.hands.HandsBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Claude's missions (Rex, 24 Nov 2026): Claude writes `missions/<id>.txt` into Rex's private bridge repo; XARVIS looks
 * every [POLL_MS], runs a new one with its Hands ([MissionRunner]) and writes `reports/<id>/report.md` (+ `shot-N.jpg`)
 * back. Off until Rex turns it on in ☰ → 🛠 Claude's missions. Uses the GitHub key XARVIS Code already has.
 */
object MissionBridge {
    private const val PREFS = "mission"
    private const val POLL_MS = 30_000L
    /** While Claude is working with this phone (a mission in the last [BUSY_MS]): look every 3 s. */
    private const val FAST_POLL_MS = 3_000L
    private const val BUSY_MS = 15 * 60_000L
    @Volatile private var lastMission = 0L
    private const val UNLOCK_WAIT_MS = 10 * 60_000L
    private const val CHANNEL = "xarvis_mission"
    private const val NOTE_ID = 7500

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = Mutex()
    private val checking = Mutex()
    private var loop: Job? = null

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun on(c: Context) = prefs(c).getBoolean("on", false)
    fun repo(c: Context) = prefs(c).getString("repo", "") ?: ""
    fun setRepo(c: Context, full: String) = prefs(c).edit().putString("repo", full.trim()).remove("etag").putBoolean("announced", false).apply()

    fun setOn(c: Context, value: Boolean) {
        prefs(c).edit().putBoolean("on", value).putBoolean("announced", false).apply()
        if (value) start(c) else { loop?.cancel(); loop = null; _status.value = "Off" }
    }

    /** The last few lines of what happened (newest first). */
    fun history(c: Context): List<String> = runCatching {
        val a = JSONArray(prefs(c).getString("log", "[]"))
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    private fun log(c: Context, line: String) {
        val stamp = SimpleDateFormat("d MMM HH:mm", Locale.US).format(Date())
        val list = listOf("$stamp · $line") + history(c)
        prefs(c).edit().putString("log", JSONArray(list.take(20)).toString()).apply()
        _status.value = line
    }

    /** Starts the look-every-30-seconds loop (from XarvisCore at start-up, and when Rex switches it on). */
    fun start(context: Context) {
        val c = context.applicationContext
        if (!on(c) || loop?.isActive == true) return
        lastMission = System.currentTimeMillis() // just switched on / restarted (e.g. after an update): Claude is likely waiting
        loop = scope.launch {
            while (on(c)) {
                runCatching { checkNow(c) }.onFailure { _status.value = "⚠ ${it.message}" }
                delay(if (System.currentTimeMillis() - lastMission < BUSY_MS) FAST_POLL_MS else POLL_MS)
            }
        }
    }

    /** "Check now" from the screen: runs in XARVIS's own scope, so leaving the screen doesn't cut a mission short. */
    fun checkInBackground(context: Context) {
        val c = context.applicationContext
        scope.launch { runCatching { checkNow(c) }.onFailure { _status.value = "⚠ ${it.message}" } }
    }

    /** Looks for new missions now and runs them (one look at a time); a short line about what happened. */
    suspend fun checkNow(context: Context): String = checking.withLock { look(context) }

    private suspend fun look(context: Context): String = withContext(Dispatchers.IO) {
        val c = context.applicationContext
        val core = (c as XarvisApp).core
        val token = core.githubKey() ?: return@withContext "No GitHub key yet: connect GitHub in ☰ → XARVIS Code first.".also { _status.value = it }
        // No repo typed (the grey example looked filled in, 24 Nov 2026): use <your GitHub name>/xarvis-bridge.
        if (repo(c).isBlank()) {
            com.xarvis.ai.code.XarvisCode.verify(token).data?.takeIf { it.isNotBlank() }?.let { setRepo(c, "$it/${BridgeRepo.DEFAULT_NAME}") }
        }
        val (owner, name) = Mission.split(repo(c)) ?: return@withContext "Set the bridge repo first (owner/name).".also { _status.value = it }
        val gh = BridgeRepo(token, owner, name)
        announce(c, gh)
        val listed = gh.list("missions", prefs(c).getString("etag", null))
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        if (listed == null) return@withContext "Checked $stamp: nothing new.".also { _status.value = it }
        val (files, etag) = listed
        val done = prefs(c).getStringSet("done", emptySet()) ?: emptySet()
        var ran = 0
        for (file in files.sorted()) {
            val id = Mission.missionId(file) ?: continue
            if (id in done) continue
            val mission = Mission.parse(gh.text("missions/$file"))
            if (!forThisPhone(mission.phone)) continue
            markDone(c, id)
            if (gh.exists("reports/$id/report.md")) continue // answered before (e.g. XARVIS was reinstalled)
            running.withLock { runOne(c, gh, id, mission) }
            ran++
        }
        prefs(c).edit().putString("etag", etag).apply()
        (if (ran == 0) "Checked $stamp: nothing new." else "Checked $stamp: ran $ran mission${if (ran > 1) "s" else ""}.").also { _status.value = it }
    }

    private fun markDone(c: Context, id: String) {
        val done = (prefs(c).getStringSet("done", emptySet()) ?: emptySet()).toMutableSet()
        done += id
        prefs(c).edit().putStringSet("done", done.toList().takeLast(300).toSet()).apply()
    }

    /** No PHONE line = the S22 (the phone with Hands built in); otherwise the phone named. */
    private fun forThisPhone(phone: String): Boolean {
        val p = phone.lowercase().trim()
        if (p.isEmpty()) return HandsBridge.builtIn
        val full = HandsBridge.builtIn
        return when {
            Regex("""s22|galaxy|ultra|samsung""").containsMatchIn(p) -> full
            Regex("""benco|v91""").containsMatchIn(p) -> !full
            p == "any" || p == "both" -> true
            else -> android.os.Build.MODEL.lowercase().contains(p)
        }
    }

    private fun phoneName(c: Context): String =
        Settings.Global.getString(c.contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL

    /** Once per switch-on or version: status/<phone>.md says the bridge is alive, so Claude can see it before sending. */
    private fun announce(c: Context, gh: BridgeRepo) {
        val key = "announced"
        if (prefs(c).getBoolean(key, false) && prefs(c).getString("announcedVersion", "") == BuildConfig.VERSION_NAME) return
        gh.checkRepo()?.let { throw IllegalStateException(it) }
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val hands = if (HandsBridge.service != null) "on" else "OFF (missions will fail until it's on)"
        gh.put(
            "status/${BuildConfig.FLAVOR}.md",
            "# ${phoneName(c)}\n\n- XARVIS ${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR} build)\n- Missions on since $now, checked every ${POLL_MS / 1000} s (every ${FAST_POLL_MS / 1000} s for ${BUSY_MS / 60_000} min after a mission)\n- XARVIS Hands: $hands\n".toByteArray(),
            "XARVIS on ${phoneName(c)}: missions on",
        )
        prefs(c).edit().putBoolean(key, true).putString("announcedVersion", BuildConfig.VERSION_NAME).apply()
        log(c, "Bridge connected to ${gh.owner}/${gh.repo}")
    }

    private suspend fun runOne(c: Context, gh: BridgeRepo, id: String, mission: Mission) {
        val at = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val phone = phoneName(c)
        log(c, "▶ Mission $id: ${mission.title}")
        notify(c, "🛠 Claude's mission: ${mission.title}", "Running ${mission.steps.size} steps. The ✱ banner's STOP ends it.")
        lastMission = System.currentTimeMillis()
        val runningSha = runCatching { gh.put("reports/$id/report.md", "# Mission $id: ${mission.title}\n\n⏳ Running on $phone since $at…\n".toByteArray(), "Mission $id: started", fresh = true) }.getOrNull()

        val report = prepare(c)?.let { why ->
            MissionRunner.Report(mission, false, why, listOf("⏸ $why"), emptyList(), 0)
        } ?: run {
            val wake = wakeScreen(c)
            try {
                MissionRunner(LocalPhone(c, phone), object : MissionRunner.Io {
                    override suspend fun pause(ms: Long) = delay(ms)
                    override fun now() = System.currentTimeMillis()
                    override fun progress(line: String) {
                        _status.value = line
                        notify(c, "🛠 Claude's mission: ${mission.title}", line)
                    }
                    override suspend fun crashes() = CrashLog.read(c)
                    override suspend fun download(path: String): String = withContext(Dispatchers.IO) { saveFromRepo(c, gh, path) }
                    override suspend fun upload(what: String, nth: Int): String = withContext(Dispatchers.IO) { MediaUpload.upload(c, gh, id, what, nth) }
                }).run(mission)
            } finally {
                runCatching { wake?.release() }
            }
        }

        for (l in report.looks) {
            l.jpeg?.let { runCatching { gh.put("reports/$id/shot-${l.n}.jpg", it, "Mission $id: screenshot ${l.n}", fresh = true) } }
        }
        gh.put("reports/$id/report.md", MissionRunner.markdown(report, id, phone, BuildConfig.VERSION_NAME, at).toByteArray(), "Mission $id: ${if (report.finished) "done" else "stopped"}", sha = runningSha)
        lastMission = System.currentTimeMillis()
        val line = "${if (report.finished) "🏁" else "⏹"} Mission $id: ${report.summary}"
        log(c, line)
        notify(c, "🛠 Claude's mission: ${mission.title}", "${report.summary}. The report is on GitHub for Claude.", done = true)
    }

    /**
     * A file from the bridge repo onto the phone: pictures into the Gallery (Pictures/XARVIS/Missions, so a
     * "choose a picture" screen finds them), anything else into Downloads/XARVIS/Missions.
     */
    private suspend fun saveFromRepo(c: Context, gh: BridgeRepo, path: String): String {
        val bytes = gh.bytes(path)
        val name = path.substringAfterLast('/')
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = when (ext) {
            "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; "gif" -> "image/gif"
            "mp4" -> "video/mp4"; "txt", "md" -> "text/plain"; "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
        if (mime.startsWith("image/") || mime.startsWith("video/")) {
            val folder = (if (mime.startsWith("image/")) android.os.Environment.DIRECTORY_PICTURES else android.os.Environment.DIRECTORY_MOVIES) + "/XARVIS/Missions"
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, folder)
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = if (mime.startsWith("image/")) android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI else android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val uri = c.contentResolver.insert(collection, values) ?: throw IllegalStateException("Android wouldn't make the file")
            c.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IllegalStateException("couldn't write the file")
            c.contentResolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return "saved $name (${bytes.size / 1024} KB) to $folder"
        }
        val saved = com.xarvis.ai.files.FileStore(c).saveBytes(name, mime, bytes, subfolder = "Missions")
        return "saved ${saved.name} (${bytes.size / 1024} KB) to Downloads/XARVIS/Missions"
    }

    /** Null when the phone is ready; else why the mission can't run (Hands off, still locked after 10 min). */
    private suspend fun prepare(c: Context): String? {
        if (HandsBridge.service == null && runCatching { com.xarvis.ai.hands.ExternalHands.get(c) }.getOrNull() == null) {
            return "XARVIS Hands is off on this phone, so I can't tap anything. Switch it on in ☰ → XARVIS Hands."
        }
        // Wait for a busy XARVIS (a task or skill running) rather than fight it for the screen.
        var waited = 0L
        while (com.xarvis.ai.task.TaskManager.busy() && waited < UNLOCK_WAIT_MS) { delay(5_000); waited += 5_000 }
        if (com.xarvis.ai.task.TaskManager.busy()) return "XARVIS was busy with a task for 10 minutes; try the mission again later."
        val keyguard = c.getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) {
            wakeScreen(c)?.let { w -> delay(1_500); runCatching { w.release() } }
            notify(c, "🔒 Unlock your phone for Claude's mission", "A mission is waiting. Unlock the phone and it starts by itself (I never enter your pattern).", high = true)
            val until = System.currentTimeMillis() + UNLOCK_WAIT_MS
            while (keyguard.isKeyguardLocked && System.currentTimeMillis() < until) delay(2_000)
            if (keyguard.isKeyguardLocked) return "The phone stayed locked for 10 minutes (I never enter the pattern)."
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen(c: Context): PowerManager.WakeLock? = runCatching {
        c.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "xarvis:mission")
            .apply { acquire(MissionRunner.MAX_MS + 60_000) }
    }.getOrNull() // the benco build has no WAKE_LOCK: its screen must already be on

    private fun notify(c: Context, title: String, text: String, done: Boolean = false, high: Boolean = false) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        val channel = if (high) "${CHANNEL}_ask" else CHANNEL
        nm.createNotificationChannel(
            NotificationChannel(channel, if (high) "Claude's missions: needs you" else "Claude's missions", if (high) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            c, NOTE_ID, Intent(c, MissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        runCatching {
            nm.notify(
                if (high) NOTE_ID + 1 else NOTE_ID,
                NotificationCompat.Builder(c, channel)
                    .setSmallIcon(R.drawable.ic_stat_xarvis)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(open)
                    .setOngoing(!done && !high)
                    .setAutoCancel(done || high)
                    .setPriority(if (high) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
                    .build(),
            )
        }
    }
}
