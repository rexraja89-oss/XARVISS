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

    fun setSmartBrain(on: Boolean) = core.setSmartBrain(on)

    /** A past chat, or a new one when [id] is null. */
    fun openChat(id: String?) = core.openChat(id)

    fun downloadModel() = core.downloadModel()
}
