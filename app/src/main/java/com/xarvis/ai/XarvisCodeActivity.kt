package com.xarvis.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.xarvis.ai.ui.XarvisCodeScreen
import com.xarvis.ai.ui.theme.XarvisTheme

/** ☰ → XARVIS Code: connect GitHub and build separate projects (never XARVIS's own app). */
class XarvisCodeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val core = (application as XarvisApp).core
        setContent {
            XarvisTheme {
                XarvisCodeScreen(
                    hasToken = core::githubHasKey,
                    saveToken = core::saveGithubKey,
                    clearToken = core::clearGithubKey,
                    token = core::githubKey,
                    onBack = ::finish,
                )
            }
        }
    }
}
