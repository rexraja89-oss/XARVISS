package com.xarvis.ai.workflow

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import com.xarvis.ai.device.AppNames
import com.xarvis.ai.device.DeviceCapabilityManager
import com.xarvis.ai.files.FileBlock
import com.xarvis.ai.files.FileStore
import com.xarvis.ai.files.SavedFile
import com.xarvis.ai.memory.MemorySync
import com.xarvis.ai.net.DeviceLink
import com.xarvis.ai.net.Peer
import com.xarvis.ai.tools.BatteryTool
import com.xarvis.ai.tools.BluetoothTool
import com.xarvis.ai.tools.ContactFinder
import com.xarvis.ai.tools.LocationTool
import java.text.DateFormat
import java.util.Date

sealed interface Step {
    // Gemma's tools
    data class Respond(val text: String) : Step
    data object ReportTime : Step
    data class Remember(val fact: String) : Step
    data class LaunchApp(val appName: String) : Step
    data class FindContact(val name: String) : Step
    data object Location : Step
    data object Battery : Step
    data object BluetoothStatus : Step
    data class Bluetooth(val on: Boolean) : Step
    data class Wifi(val on: Boolean) : Step
    /** [direct] places the call at once (Rex typed "call ..."); otherwise the dialer opens. */
    data class Call(val target: String, val direct: Boolean = false) : Step
    data class WhatsApp(val target: String, val text: String?) : Step
    data class Sms(val target: String, val text: String) : Step
    data class Flashlight(val on: Boolean) : Step
    data class Alarm(val hour: Int, val minute: Int, val label: String?) : Step
    data class Timer(val seconds: Int) : Step
    data class Search(val query: String) : Step
    data class ShowMap(val place: String?) : Step
    /** Read Wikipedia about [query] and give the facts back to Gemma (handled by the agent). */
    data class Lookup(val query: String) : Step
    /** Search for [query] inside [app] ("find in Gmail: Adarsh"), rather than on the web. */
    data class FindInApp(val app: String, val query: String) : Step
    /** Give [text] to [app] as shared text, e.g. a question typed into ChatGPT, ready to send. */
    data class AskApp(val app: String, val text: String) : Step
    /** Save a file Gemma wrote (PDF, Word, Excel, text) to Downloads/XARVIS. */
    data class MakeFile(val block: FileBlock) : Step
    /** Open real job listings for [titles] on [site] (LinkedIn unless named), in [place] (worldwide unless named). */
    data class Jobs(val titles: String, val place: String? = null, val site: String? = null) : Step
    /** Search earlier chats for [query] and give them back to Gemma (handled by the agent). */
    data class Recall(val query: String) : Step
    /** List everything XARVIS remembers. */
    data object ListMemories : Step
    /** Turn the file Rex attached into [format] (pdf, docx, xlsx, txt...); the agent fills it in. */
    data class ConvertFile(val format: String) : Step
    /** Show files XARVIS made earlier whose names match [query], to open or share again. */
    data class ShowFiles(val query: String) : Step

    // Linking phones (exact commands)
    data object ListDevices : Step
    data class PairWith(val target: String) : Step
    data class PairCode(val code: String) : Step
    data class Unlink(val device: String) : Step
}

data class StepResult(val success: Boolean, val message: String, val files: List<SavedFile> = emptyList())

/** Runs steps in order, stopping at the first failure. */
class WorkflowEngine(
    context: Context,
    private val device: DeviceCapabilityManager,
    private val link: DeviceLink,
    private val memorySync: MemorySync,
    private val contacts: ContactFinder,
    private val files: FileStore,
) {
    private val appContext = context.applicationContext
    private val phone = PhoneActions(appContext)
    private val location = LocationTool(appContext)
    private val battery = BatteryTool(appContext)
    private val bluetoothInfo = BluetoothTool(appContext)

    suspend fun execute(steps: List<Step>): List<StepResult> {
        val results = mutableListOf<StepResult>()
        for (step in steps) {
            val result = run(step)
            results += result
            if (!result.success) break
        }
        return results
    }

    private suspend fun run(step: Step): StepResult = when (step) {
        is Step.Respond -> StepResult(true, step.text)

        Step.ReportTime -> StepResult(true, DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT).format(Date()))

        is Step.Remember ->
            if (memorySync.remember(step.fact)) StepResult(true, "Got it. I'll remember: ${step.fact}")
            else StepResult(true, "I already know that: ${step.fact}")

        is Step.LaunchApp -> {
            val app = device.findApp(step.appName)
            val intent = app?.let { device.launchIntent(it) }
            val web = AppNames.webApp(step.appName)
            if (app == null && web != null) {
                openUrl(web, null, "Opening ${step.appName} (${Uri.parse(web).host}).")
            } else if (app == null || intent == null) {
                val similar = AppNames.similar(step.appName, device.launchableApps())
                StepResult(
                    false,
                    "I couldn't find an app called \"${step.appName}\" on this phone." +
                        if (similar.isNotEmpty()) " Did you mean: ${similar.joinToString()}?" else " Is it installed?",
                )
            } else {
                try {
                    appContext.startActivity(intent)
                    StepResult(true, "Opening ${app.label}.")
                } catch (e: ActivityNotFoundException) {
                    StepResult(false, "${app.label} couldn't be opened.")
                }
            }
        }

        is Step.FindContact -> findContact(step.name)
        is Step.FindInApp -> findInApp(step.app, step.query)
        is Step.AskApp -> askApp(step.app, step.text)
        is Step.Lookup -> StepResult(true, "") // done by the agent before the reply is shown
        is Step.Recall -> StepResult(true, "") // done by the agent before the reply is shown
        is Step.Jobs -> {
            val site = JobSites.pick(step.site)
            val app = device.findApp(site.appName)
            openUrl(
                JobSites.url(site, step.titles, step.place), app?.packageName,
                "Opening ${site.label} jobs for \"${step.titles}\" ${step.place?.let { "in $it" } ?: "worldwide"}. " +
                    "Tap a job to see it, and Apply (or Easy Apply) to apply; I can't press it for you yet.",
            )
        }
        Step.ListMemories -> {
            val facts = memorySync.facts()
            if (facts.isEmpty()) StepResult(true, "I don't remember anything yet. Tell me something to keep, e.g. \"remember my car is white\".")
            else StepResult(
                true,
                "I remember ${facts.size} things you told me (shared with your linked phone):\n" +
                    facts.joinToString("\n") { "• $it" } +
                    "\n\nI also remember our conversations, even after XARVIS restarts: ask me about anything we talked about.",
            )
        }
        is Step.ConvertFile -> StepResult(false, "Attach the file first: tap + and choose File, then ask me to convert it.")
        is Step.MakeFile -> try {
            val saved = files.save(step.block)
            StepResult(true, "I made ${saved.name} (saved in Downloads › XARVIS). Tap OPEN or SHARE below.", listOf(saved))
        } catch (e: Exception) {
            StepResult(false, "I couldn't save ${step.block.name}: ${e.message ?: e.javaClass.simpleName}")
        }
        is Step.ShowFiles -> {
            val found = files.find(step.query)
            when {
                found.isNotEmpty() -> StepResult(true, "Here ${if (found.size == 1) "it is" else "they are"}. Tap OPEN or SHARE.", found)
                step.query.isBlank() -> StepResult(false, "I haven't made any files yet. Ask me, e.g. \"make a PDF of my shopping list\".")
                else -> StepResult(
                    false,
                    "I can only find files I made myself, and none matches \"${step.query}\". For any other file, " +
                        "tap + and choose File, then search for it there (e.g. \"CV\").",
                )
            }
        }
        Step.Location -> StepResult(true, location.read())
        Step.Battery -> StepResult(true, battery.read())
        Step.BluetoothStatus -> StepResult(true, bluetoothInfo.read())
        is Step.Bluetooth -> phone.bluetooth(step.on)
        is Step.Wifi -> phone.wifi(step.on)
        is Step.Call -> phone.call(step.target, step.direct)
        is Step.ShowMap -> try {
            val uri = step.place?.let { "geo:0,0?q=${Uri.encode(it)}" } ?: "geo:0,0"
            appContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            StepResult(true, step.place?.let { "Opening the map for $it." } ?: "Opening the map.")
        } catch (e: ActivityNotFoundException) {
            StepResult(false, "There's no maps app on this phone.")
        }
        is Step.WhatsApp -> phone.whatsApp(step.target, step.text)
        is Step.Sms -> phone.sms(step.target, step.text)
        is Step.Flashlight -> phone.flashlight(step.on)
        is Step.Alarm -> phone.alarm(step.hour, step.minute, step.label)
        is Step.Timer -> phone.timer(step.seconds)
        is Step.Search -> try {
            appContext.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(step.query)}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            StepResult(true, "Searching for \"${step.query}\".")
        } catch (e: ActivityNotFoundException) {
            StepResult(false, "There's no web browser on this phone.")
        }

        Step.ListDevices -> StepResult(true, link.describeDevices())
        is Step.PairWith -> linkStep { link.startPairing(step.target) }
        is Step.PairCode -> linkStep { link.finishPairing(step.code) }
        is Step.Unlink -> withPeer(step.device) {
            link.unpair(it)
            StepResult(true, "Unlinked ${it.name}.")
        }
    }

    /**
     * Searches inside an app: the app's own search if it accepts one from other apps, or its
     * search web page (which Android opens in the app); otherwise opens the app with the words
     * copied, ready to paste into its search box.
     */
    private fun findInApp(appName: String, query: String): StepResult {
        val app = device.findApp(appName)
        val q = Uri.encode(query)
        val page = when (app?.packageName ?: AppNames.normalize(appName)) {
            "com.linkedin.android", "linkedin" -> "https://www.linkedin.com/search/results/all/?keywords=$q"
            "com.google.android.apps.docs", "drive" -> "https://drive.google.com/drive/search?q=$q"
            "com.github.android", "github" -> "https://github.com/search?q=$q"
            "com.google.android.youtube", "youtube" -> "https://www.youtube.com/results?search_query=$q"
            else -> null
        }
        if (page != null) return openUrl(page, app?.packageName, "Searching ${app?.label ?: appName} for \"$query\".")
        if (app == null) return StepResult(false, "I couldn't find an app called \"$appName\" on this phone.")
        try {
            appContext.startActivity(
                Intent(Intent.ACTION_SEARCH).setPackage(app.packageName).putExtra(SearchManager.QUERY, query)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return StepResult(true, "Searching ${app.label} for \"$query\".")
        } catch (e: Exception) {
            // This app doesn't take searches from other apps.
        }
        val launch = device.launchIntent(app) ?: return StepResult(false, "${app.label} couldn't be opened.")
        appContext.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("search", query))
        return try {
            appContext.startActivity(launch)
            StepResult(true, "Opened ${app.label}. It doesn't accept searches from other apps, so I copied \"$query\": tap its search box and paste.")
        } catch (e: ActivityNotFoundException) {
            StepResult(false, "${app.label} couldn't be opened.")
        }
    }

    /**
     * Shares [text] to the app, which is how other apps hand text to ChatGPT, Gemini, WhatsApp
     * and similar: it opens with the text typed in and Rex taps send. Apps that don't take
     * shared text are opened with the text copied, ready to paste.
     */
    private fun askApp(appName: String, text: String): StepResult {
        val app = device.findApp(appName) ?: return StepResult(false, "I couldn't find an app called \"$appName\" on this phone.")
        try {
            appContext.startActivity(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    .setPackage(app.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return StepResult(true, "I've put your message into ${app.label}. Tap send there.")
        } catch (e: Exception) {
            // This app doesn't take shared text.
        }
        val launch = device.launchIntent(app) ?: return StepResult(false, "${app.label} couldn't be opened.")
        appContext.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("message", text))
        return try {
            appContext.startActivity(launch)
            StepResult(true, "Opened ${app.label} and copied your message: tap its text box, paste, and send.")
        } catch (e: ActivityNotFoundException) {
            StepResult(false, "${app.label} couldn't be opened.")
        }
    }

    /** Opens [url], in [pkg]'s app when it's given and can, otherwise wherever Android sends it. */
    private fun openUrl(url: String, pkg: String?, success: String): StepResult {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg != null) {
            try {
                appContext.startActivity(Intent(intent).setPackage(pkg))
                return StepResult(true, success)
            } catch (e: ActivityNotFoundException) {
                // that app doesn't open these links; use the browser
            }
        }
        return try {
            appContext.startActivity(intent)
            StepResult(true, success)
        } catch (e: ActivityNotFoundException) {
            StepResult(false, "There's no web browser on this phone.")
        }
    }

    /** This phone's contacts first; if nobody matches, the linked phones' contacts. */
    private suspend fun findContact(name: String): StepResult {
        val here = contacts.find(name)
        if (!here.isNullOrEmpty()) return StepResult(true, here.joinToString("\n"))
        val elsewhere = link.pairedPeers().flatMap { p ->
            runCatching { link.remoteContacts(p, name) }.getOrDefault(emptyList()).map { "$it (saved on ${p.name})" }
        }
        if (elsewhere.isNotEmpty()) return StepResult(true, elsewhere.joinToString("\n"))
        return if (here == null) {
            StepResult(false, "I need permission to read your contacts. Tap Allow when I ask, or turn it on in Settings > Apps > XARVIS > Permissions.")
        } else {
            StepResult(false, "I couldn't find \"$name\" in your contacts.")
        }
    }

    private suspend fun linkStep(block: suspend () -> String): StepResult = try {
        StepResult(true, block())
    } catch (e: Exception) {
        StepResult(false, "Device link failed: ${e.message ?: e.javaClass.simpleName}")
    }

    private suspend fun withPeer(name: String, block: suspend (Peer) -> StepResult): StepResult {
        val peer = link.findPeer(name)
            ?: return StepResult(false, "I'm not linked to a device called \"$name\". Type \"devices\" to see linked devices.")
        return try {
            block(peer)
        } catch (e: Exception) {
            StepResult(false, "Couldn't reach ${peer.name}: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
