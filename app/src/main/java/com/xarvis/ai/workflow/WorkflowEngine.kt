package com.xarvis.ai.workflow

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.xarvis.ai.device.DeviceCapabilityManager
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

    // Linking phones (exact commands)
    data object ListDevices : Step
    data class PairWith(val target: String) : Step
    data class PairCode(val code: String) : Step
    data class Unlink(val device: String) : Step
}

data class StepResult(val success: Boolean, val message: String)

/** Runs steps in order, stopping at the first failure. */
class WorkflowEngine(
    context: Context,
    private val device: DeviceCapabilityManager,
    private val link: DeviceLink,
    private val memorySync: MemorySync,
    private val contacts: ContactFinder,
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
            if (app == null || intent == null) {
                StepResult(false, "I couldn't find an app called \"${step.appName}\".")
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
