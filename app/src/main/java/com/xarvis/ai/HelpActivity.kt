package com.xarvis.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.xarvis.ai.ui.HelpScreen
import com.xarvis.ai.ui.theme.XarvisTheme

/** ☰ → "What can I do?": a plain-language tour of XARVIS's capabilities. */
class HelpActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { XarvisTheme { HelpScreen(onBack = ::finish) } }
    }
}
