package com.xarvis.ai.workflow

import android.content.ActivityNotFoundException
import android.content.Context
import com.xarvis.ai.device.DeviceCapabilityManager
import com.xarvis.ai.memory.MemorySync
import com.xarvis.ai.net.DeviceLink
import com.xarvis.ai.net.Peer
import com.xarvis.ai.tools.ContactFinder
import java.text.DateFormat
import java.util.Date

sealed interface Step {
    // Gemma's tools
    data class Respond(val text: String) : Step
    data object ReportTime : Step
    data class Remember(val fact: String) : Step
    data class LaunchApp(val appName: String) : Step
    data class FindContact(val name: String) : Step

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
