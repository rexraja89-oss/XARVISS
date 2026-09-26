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

    fun submit(command: String) = core.submit(command)
}
