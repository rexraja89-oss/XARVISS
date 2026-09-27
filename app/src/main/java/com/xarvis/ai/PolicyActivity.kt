package com.xarvis.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.xarvis.ai.ui.ActivityLogScreen
import com.xarvis.ai.ui.PermissionsScreen
import com.xarvis.ai.ui.theme.XarvisTheme

/** ☰ → CONTROL: the Permissions screen, or the Activity log with [EXTRA_LOG]. */
class PolicyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val policy = (application as XarvisApp).core.policy
        val log = intent.getBooleanExtra(EXTRA_LOG, false)
        setContent {
            XarvisTheme {
                if (log) ActivityLogScreen(policy, onBack = ::finish) else PermissionsScreen(policy, onBack = ::finish)
            }
        }
    }

    companion object {
        const val EXTRA_LOG = "log"
    }
}
