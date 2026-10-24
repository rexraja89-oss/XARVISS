package com.xarvis.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.xarvis.ai.ui.GameModScreen
import com.xarvis.ai.ui.theme.XarvisTheme

/** ☰ → CONTROL → XARVIS Mod: hand XARVIS a game's OBB/APK/ZIP and let the cloud open, edit and rebuild it. */
class GameModActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val core = (application as XarvisApp).core
        setContent {
            XarvisTheme {
                GameModScreen(
                    hasToken = core::githubHasKey,
                    token = core::githubKey,
                    cloudReady = core::cloudReady,
                    generate = core::generateCode,
                    onBack = ::finish,
                )
            }
        }
    }
}
