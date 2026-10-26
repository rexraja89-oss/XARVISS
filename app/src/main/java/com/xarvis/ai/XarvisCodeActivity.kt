package com.xarvis.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.xarvis.ai.code.CodeSessionStore
import com.xarvis.ai.ui.XarvisCodeHome
import com.xarvis.ai.ui.XarvisCodeScreen
import com.xarvis.ai.ui.theme.XarvisTheme

/** ☰ → XARVIS Code: a home of past sessions, each opening the Claude-Code-style coding agent. */
class XarvisCodeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val core = (application as XarvisApp).core
        setContent {
            XarvisTheme {
                val ctx = LocalContext.current
                val store = remember { CodeSessionStore(ctx) }
                var sessionId by remember { mutableStateOf<String?>(null) }

                if (sessionId == null) {
                    XarvisCodeHome(
                        store = store,
                        onNew = { sessionId = "s" + System.currentTimeMillis() },
                        onOpen = { sessionId = it },
                        onBack = ::finish,
                    )
                } else {
                    XarvisCodeScreen(
                        hasToken = core::githubHasKey,
                        saveToken = core::saveGithubKey,
                        clearToken = core::clearGithubKey,
                        token = core::githubKey,
                        cloudReady = core::cloudReady,
                        generate = core::generateCode,
                        visionReady = core::visionReady,
                        generateWithImage = core::generateCodeWithImage,
                        sessionId = sessionId!!,
                        store = store,
                        onBack = { sessionId = null },
                    )
                }
            }
        }
    }
}
