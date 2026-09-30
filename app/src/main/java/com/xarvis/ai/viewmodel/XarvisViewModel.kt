package com.xarvis.ai.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.xarvis.ai.XarvisApp
import com.xarvis.ai.XarvisUiState
import kotlinx.coroutines.flow.StateFlow

/** The screen's window onto [com.xarvis.ai.XarvisCore], which outlives it. */
class XarvisViewModel(application: Application) : AndroidViewModel(application) {

    private val core = (application as XarvisApp).core

    val state: StateFlow<XarvisUiState> = core.state

    init {
        core.refresh()
    }

    fun submit(command: String, photo: android.net.Uri? = null, document: android.net.Uri? = null, spoken: Boolean = false) =
        core.submit(command, photo, document, spoken)

    fun toggleSpeaker() = core.toggleSpeaker()

    fun stop() = core.stop()

    fun loadChats() = core.loadChats()

    fun nextVoice(hindi: Boolean) = core.nextVoice(hindi)

    fun voiceLabel(hindi: Boolean) = core.voiceLabel(hindi)

    fun turnOnTailscale() = core.turnOnTailscale()

    fun cloudHasKey() = core.cloudHasKey()
    fun saveCloudKey(key: String) = core.saveCloudKey(key)
    fun clearCloudKey() = core.clearCloudKey()
    fun backupBrains() = core.backupBrains()
    fun saveBackupKey(id: String, key: String) = core.saveBackupKey(id, key)
    fun clearBackupKey(id: String) = core.clearBackupKey(id)
    fun setCloud(on: Boolean) = core.setCloud(on)
    fun setCouncil(on: Boolean) = core.setCouncil(on)
    fun togglePrivateLock() = core.togglePrivateLock()

    fun cameraOpened() = core.cameraOpened()

    fun answerAsk(answer: com.xarvis.ai.policy.Answer) = core.answerAsk(answer)

    fun loadOwnVoice(uri: android.net.Uri, done: (String) -> Unit) = core.loadOwnVoice(uri, done)

    fun ownVoiceOn() = core.ownVoiceOn()

    fun setOwnVoice(on: Boolean) = core.setOwnVoice(on)

    fun ownVoices() = core.ownVoices()

    fun setSmartBrain(on: Boolean) = core.setSmartBrain(on)

    /** A past chat, or a new one when [id] is null. */
    fun openChat(id: String?) = core.openChat(id)

    fun downloadModel() = core.downloadModel()
}
