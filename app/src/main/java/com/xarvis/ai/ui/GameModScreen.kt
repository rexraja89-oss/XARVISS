package com.xarvis.ai.ui

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.xarvis.ai.code.GameModPipeline
import com.xarvis.ai.code.XarvisCode
import com.xarvis.ai.ui.theme.XarvisCyan
import com.xarvis.ai.ui.theme.XarvisMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ☰ → CONTROL → XARVIS Mod: hand XARVIS an offline game's file (OBB / APK / ZIP). XARVIS couriers it to a
 * PRIVATE GitHub repo, a cloud workflow opens it and reports what's inside, the brain edits the editable
 * files on the phone, and a second workflow rebuilds the file for you to put back. It reports every limit
 * in plain words and never pretends. The main XARVIS is untouched.
 */
@Composable
fun GameModScreen(
    hasToken: () -> Boolean,
    token: () -> String?,
    cloudReady: () -> Boolean,
    generate: suspend (String, String) -> String,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var login by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val log = remember { mutableStateListOf<String>() }
    var picked by remember { mutableStateOf<File?>(null) }
    var pickedName by remember { mutableStateOf<String?>(null) }
    val editable = remember { mutableStateListOf<String>() }
    var selected by remember { mutableStateOf<String?>(null) }
    var change by remember { mutableStateOf("") }

    fun say(line: String) { log.add(line) }

    LaunchedEffect(Unit) {
        if (hasToken() && login == null) {
            busy = true; say("Checking your GitHub connection…")
            val r = withContext(Dispatchers.IO) { XarvisCode.verify(token() ?: "") }
            if (r.ok) { login = r.data; say("Connected as ${r.data}.") } else say("Couldn't connect: ${r.message}")
            busy = false
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                val name = withContext(Dispatchers.IO) { queryName(ctx, uri) }
                val dest = File(ctx.cacheDir, name)
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
                }
                picked = dest; pickedName = name
                say("Loaded ${name} (${dest.length() / 1024} KB). Ready to send to GitHub.")
            } catch (e: Exception) { say("Couldn't read that file: ${e.message}") } finally { busy = false }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 14.dp).verticalScroll(rememberScrollState())) {
        TextButton(onClick = onBack) { Text("‹ Back", color = XarvisCyan) }
        Text("XARVIS Mod", style = MaterialTheme.typography.headlineSmall, color = XarvisCyan)
        Text(
            "Give me your offline game's file (.obb, .apk or .zip). I'll open it in the cloud, tell you what's " +
                "inside and what can be changed, edit it, and rebuild it for you to put back. Your game data stays " +
                "in a PRIVATE repo. First copy the file into a folder you can reach (e.g. Downloads).",
            style = MaterialTheme.typography.bodySmall, color = XarvisMuted, modifier = Modifier.padding(vertical = 8.dp),
        )

        if (login == null) {
            Text(
                "Connect GitHub first in ☰ → CONTROL → XARVIS Code (same free token). Then come back here.",
                style = MaterialTheme.typography.bodyMedium, color = XarvisMuted, modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            Button(enabled = !busy, onClick = { pick.launch(arrayOf("*/*")) }, modifier = Modifier.padding(top = 4.dp)) {
                Text(if (pickedName == null) "Pick the game file" else "Pick a different file")
            }

            if (picked != null) {
                Button(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            try { inspect(picked!!, login!!, token() ?: "", editable, ::say) }
                            catch (e: Exception) { say("Something went wrong: ${e.message}") }
                            finally { busy = false }
                        }
                    },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Send to GitHub & open it") }
            }

            if (editable.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = XarvisMuted.copy(alpha = 0.3f))
                Text("Editable files — tap one, say what to change:", style = MaterialTheme.typography.labelMedium, color = XarvisCyan)
                editable.forEach { f ->
                    val sel = selected == f
                    Text(
                        (if (sel) "● " else "○ ") + f,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (sel) XarvisCyan else XarvisMuted,
                        modifier = Modifier.fillMaxWidth().clickable { selected = f }.padding(vertical = 6.dp),
                    )
                }
                OutlinedTextField(
                    value = change, onValueChange = { change = it },
                    label = { Text("What to change (e.g. set coins to 9999)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Button(
                    enabled = !busy && selected != null && change.isNotBlank(),
                    onClick = {
                        scope.launch {
                            busy = true
                            try { applyChange(ctx, selected!!, change.trim(), pickedName!!, login!!, token() ?: "", cloudReady, generate, ::say) }
                            catch (e: Exception) { say("Something went wrong: ${e.message}") }
                            finally { busy = false }
                        }
                    },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Edit & rebuild the file") }
            }
        }

        if (busy) {
            Row(Modifier.padding(top = 12.dp)) {
                CircularProgressIndicator(Modifier.padding(end = 10.dp))
                Text("Working…", color = XarvisMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (log.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = XarvisMuted.copy(alpha = 0.3f))
            Text(
                log.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                color = XarvisMuted, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            )
        }
    }
}

/** Uploads the file, fires the inspect workflow, waits for it, and reads back the report. */
private suspend fun inspect(file: File, owner: String, token: String, editable: MutableList<String>, say: (String) -> Unit) {
    editable.clear()
    say("Making sure the private workspace exists…")
    withContext(Dispatchers.IO) { XarvisCode.createRepo(token, GameModPipeline.REPO, "XARVIS game mods", private = true) }
    // Push the pipeline files if the inspect workflow isn't there yet.
    val have = withContext(Dispatchers.IO) { XarvisCode.getFile(token, owner, GameModPipeline.REPO, ".github/workflows/${GameModPipeline.INSPECT_WORKFLOW}") }
    if (!have.ok) {
        say("Installing the cloud tools (first time only)…")
        for ((path, content) in GameModPipeline.files()) {
            val r = withContext(Dispatchers.IO) { XarvisCode.putFile(token, owner, GameModPipeline.REPO, path, content, "add $path") }
            if (!r.ok) { say("Couldn't install $path: ${r.message}"); return }
        }
        delay(4000) // let GitHub register the new workflows
    }
    say("Uploading ${file.name} to GitHub…")
    val up = withContext(Dispatchers.IO) { XarvisCode.uploadReleaseAsset(token, owner, GameModPipeline.REPO, GameModPipeline.TAG_IN, file) }
    if (!up.ok) { say("Upload failed: ${up.message}"); return }
    val before = runId(owner, token)
    say("Opening it in the cloud…")
    var fired = false
    repeat(6) {
        val d = withContext(Dispatchers.IO) { XarvisCode.dispatchWorkflow(token, owner, GameModPipeline.REPO, GameModPipeline.INSPECT_WORKFLOW, mapOf("asset" to file.name)) }
        if (d.ok) { fired = true; return@repeat }
        delay(4000)
    }
    if (!fired) { say("Couldn't start the cloud job. Try again in a minute."); return }
    if (!waitForRun(owner, token, before, say)) return
    val inside = withContext(Dispatchers.IO) { XarvisCode.getFile(token, owner, GameModPipeline.REPO, "INSIDE.md") }
    if (!inside.ok || inside.data == null) { say("The job finished but I couldn't read the report."); return }
    say("")
    say(inside.data!!.trim())
    if (GameModPipeline.isSealed(inside.data!!)) return
    editable.addAll(GameModPipeline.editableFiles(inside.data!!))
    if (editable.isEmpty()) say("\nNothing hand-editable this time — see the ideas above.")
}

/** Edits the chosen extracted file with the brain, pushes it back, repacks, and saves the result to Downloads. */
private suspend fun applyChange(
    ctx: Context, fileInZip: String, request: String, originalName: String, owner: String, token: String,
    cloudReady: () -> Boolean, generate: suspend (String, String) -> String, say: (String) -> Unit,
) {
    if (!cloudReady()) { say("Turn a cloud brain on in ☰ → BRAIN first — I need it to edit the file."); return }
    val path = "extracted/$fileInZip"
    say("Reading $fileInZip…")
    val cur = withContext(Dispatchers.IO) { XarvisCode.getFile(token, owner, GameModPipeline.REPO, path) }
    if (!cur.ok || cur.data == null) { say("Couldn't read $fileInZip: ${cur.message}"); return }
    say("Making the change…")
    val sys = "You edit a single game data/config file. I give you the file and a change to make. Reply with ONLY " +
        "the complete edited file and nothing else — no explanation, no markdown fences. Keep the exact same format " +
        "(JSON stays valid JSON, etc.); change only what's needed for: $request"
    val edited = try { withContext(Dispatchers.IO) { generate(sys, "Change: $request\n\nFile ($fileInZip):\n${cur.data}") } }
    catch (e: Exception) { say("The brain couldn't do it: ${e.message}"); return }
    val clean = stripFences(edited)
    if (clean.isBlank()) { say("The brain returned nothing usable."); return }
    val push = withContext(Dispatchers.IO) { XarvisCode.putFile(token, owner, GameModPipeline.REPO, path, clean, "edit $fileInZip: $request") }
    if (!push.ok) { say("Couldn't save the edit: ${push.message}"); return }
    val before = runId(owner, token)
    say("Rebuilding the game file in the cloud…")
    var fired = false
    repeat(6) {
        val d = withContext(Dispatchers.IO) { XarvisCode.dispatchWorkflow(token, owner, GameModPipeline.REPO, GameModPipeline.REPACK_WORKFLOW, mapOf("asset" to originalName)) }
        if (d.ok) { fired = true; return@repeat }
        delay(4000)
    }
    if (!fired) { say("Couldn't start the rebuild. Try again in a minute."); return }
    if (!waitForRun(owner, token, before, say)) return
    val outName = "modified-$originalName"
    say("Downloading $outName to your Downloads/XARVIS folder…")
    val ok = withContext(Dispatchers.IO) { saveToDownloads(ctx, outName, token, owner) }
    if (ok) say("Done. The modified file is in Downloads/XARVIS as $outName.\nCopy it back into the game's folder to use it. If the game refuses it, it has an integrity check and this route is closed for that game.")
    else say("The rebuild finished but I couldn't download the file. It's in the repo's \"output\" release.")
}

private suspend fun runId(owner: String, token: String): Long =
    withContext(Dispatchers.IO) { XarvisCode.latestRun(token, owner, GameModPipeline.REPO).data?.substringAfterLast("|")?.toLongOrNull() ?: 0L }

/** Polls until a NEW workflow run (id past [before]) reaches "completed", then reports success/failure. */
private suspend fun waitForRun(owner: String, token: String, before: Long, say: (String) -> Unit): Boolean {
    repeat(90) { // ~7.5 min
        delay(5000)
        val r = withContext(Dispatchers.IO) { XarvisCode.latestRun(token, owner, GameModPipeline.REPO) }
        val parts = r.data?.split("|") ?: return@repeat
        val status = parts.getOrNull(0); val conclusion = parts.getOrNull(1); val id = parts.getOrNull(2)?.toLongOrNull() ?: 0L
        if (id <= before) return@repeat // still the old run
        if (status == "completed") {
            if (conclusion == "success") return true
            say("The cloud job failed (${conclusion}). Open the repo's Actions tab to see why."); return false
        }
    }
    say("The cloud job is taking too long. Check the repo's Actions tab."); return false
}

private fun saveToDownloads(ctx: Context, name: String, token: String, owner: String): Boolean = runCatching {
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/XARVIS")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val resolver = ctx.contentResolver
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
    var ok = false
    resolver.openOutputStream(uri)?.use { out ->
        ok = XarvisCode.downloadAsset(token, owner, GameModPipeline.REPO, GameModPipeline.TAG_OUT, name, out).ok
    }
    resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    if (!ok) resolver.delete(uri, null, null)
    ok
}.getOrDefault(false)

private fun stripFences(raw: String): String {
    var s = raw.trim()
    Regex("```[a-zA-Z]*\\s*([\\s\\S]*?)```").find(s)?.let { s = it.groupValues[1].trim() }
    return s
}

private fun queryName(ctx: Context, uri: android.net.Uri): String {
    var name = "game.obb"
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) c.getString(i)?.let { name = it }
    }
    return name
}
