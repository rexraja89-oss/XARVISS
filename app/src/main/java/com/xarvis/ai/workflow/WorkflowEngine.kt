package com.xarvis.ai.workflow

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.xarvis.ai.device.DeviceCapabilityManager
import com.xarvis.ai.memory.MemorySync
import com.xarvis.ai.memory.MemorySystem
import com.xarvis.ai.net.DeviceLink
import com.xarvis.ai.net.Peer
import com.xarvis.ai.service.AlwaysOn
import java.text.DateFormat
import java.util.Date

sealed interface Step {
    data class Respond(val text: String) : Step
    data class LaunchApp(val appName: String) : Step
    data class OpenUrl(val url: String, val description: String) : Step
    data class Remember(val fact: String) : Step
    data class Recall(val query: String?) : Step
    data object ReportDevice : Step
    data object ReportTime : Step
    data object ClearMemory : Step

    // Linked devices
    data object ListDevices : Step
    data class PairWith(val target: String) : Step
    data class PairCode(val code: String) : Step
    data class RemoteStatus(val device: String) : Step
    data class SendNote(val device: String, val text: String) : Step
    data class Unlink(val device: String) : Step
    data class SetAddress(val device: String, val host: String, val port: Int) : Step
    data class SetAlwaysOn(val enabled: Boolean) : Step

    // Phone actions. [direct] calls place the call at once; others open the dialer.
    data class Call(val target: String, val direct: Boolean) : Step
    data class WhatsApp(val target: String, val text: String?) : Step
    data class Sms(val target: String, val text: String) : Step
    data class Flashlight(val on: Boolean) : Step
    data class Alarm(val hour: Int, val minute: Int, val label: String?) : Step
    data class Timer(val seconds: Int) : Step
    data class Bluetooth(val on: Boolean) : Step
    data class Wifi(val on: Boolean) : Step
}

data class Workflow(val command: String, val steps: List<Step>)

data class StepResult(val success: Boolean, val message: String)

/** Executes a workflow's steps in order, stopping at the first failure. */
class WorkflowEngine(
    context: Context,
    private val memory: MemorySystem,
    private val device: DeviceCapabilityManager,
    private val link: DeviceLink,
    private val memorySync: MemorySync,
) {
    private val appContext = context.applicationContext
    private val phone = PhoneActions(appContext)

    suspend fun execute(workflow: Workflow): List<StepResult> {
        val results = mutableListOf<StepResult>()
        for (step in workflow.steps) {
            val result = run(step)
            results += result
            if (!result.success) break
        }
        return results
    }

    private suspend fun run(step: Step): StepResult = when (step) {
        is Step.Respond -> StepResult(true, step.text)

        is Step.LaunchApp -> {
            val app = device.findApp(step.appName)
            val intent = app?.let { device.launchIntent(it) }
            if (app == null || intent == null) {
                StepResult(false, "I couldn't find an app called \"${step.appName}\".")
            } else {
                startActivity(intent, "Opening ${app.label}.")
            }
        }

        is Step.OpenUrl -> startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(step.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            step.description,
        )

        is Step.Remember -> {
            if (memorySync.remember(step.fact)) {
                StepResult(true, "Got it. I'll remember: ${step.fact}")
            } else {
                StepResult(true, "I already know that: ${step.fact}")
            }
        }

        is Step.Recall -> {
            val facts = memory.recallFacts(step.query)
            if (facts.isEmpty()) {
                StepResult(true, if (step.query == null) "I don't have any memories yet."
                else "I don't remember anything about \"${step.query}\".")
            } else {
                StepResult(true, "Here's what I remember:\n" + facts.joinToString("\n") { "  - ${it.content}" })
            }
        }

        Step.ReportDevice -> StepResult(true, device.summary())

        Step.ReportTime -> StepResult(
            true,
            DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT).format(Date()),
        )

        Step.ClearMemory -> {
            memorySync.forgetEverything()
            StepResult(true, if (link.pairedPeers().isEmpty()) "Memory wiped." else "Memory wiped here and on your linked devices.")
        }

        Step.ListDevices -> StepResult(true, link.describeDevices())
        is Step.PairWith -> linkStep { link.startPairing(step.target) }
        is Step.PairCode -> linkStep { link.finishPairing(step.code) }
        is Step.RemoteStatus -> withPeer(step.device) { StepResult(true, "${it.name}:\n${link.remoteStatus(it)}") }
        is Step.SendNote -> withPeer(step.device) {
            link.sendNote(it, step.text)
            StepResult(true, "Sent to ${it.name}.")
        }
        is Step.Unlink -> withPeer(step.device) {
            link.unpair(it)
            StepResult(true, "Unlinked ${it.name}.")
        }
        is Step.SetAlwaysOn -> {
            AlwaysOn.set(appContext, step.enabled)
            StepResult(
                true,
                if (step.enabled) "I'll keep running in the background so your linked devices can always reach me."
                else "Background mode off. Linked devices can reach me only while the app is open.",
            )
        }
        is Step.SetAddress -> withPeer(step.device) {
            link.setAddress(it, step.host, step.port)
            StepResult(true, "I'll reach ${it.name} at ${step.host}:${step.port}.")
        }

        is Step.Call -> phone.call(step.target, step.direct)
        is Step.WhatsApp -> phone.whatsApp(step.target, step.text)
        is Step.Sms -> phone.sms(step.target, step.text)
        is Step.Flashlight -> phone.flashlight(step.on)
        is Step.Alarm -> phone.alarm(step.hour, step.minute, step.label)
        is Step.Timer -> phone.timer(step.seconds)
        is Step.Bluetooth -> phone.bluetooth(step.on)
        is Step.Wifi -> phone.wifi(step.on)
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

    private fun startActivity(intent: Intent, successMessage: String): StepResult = try {
        appContext.startActivity(intent)
        StepResult(true, successMessage)
    } catch (e: ActivityNotFoundException) {
        StepResult(false, "Nothing on this device can handle that.")
    }
}
