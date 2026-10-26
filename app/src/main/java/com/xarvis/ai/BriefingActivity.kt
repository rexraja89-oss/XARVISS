package com.xarvis.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.xarvis.ai.ui.BriefingScreen
import com.xarvis.ai.ui.theme.XarvisTheme

/** ☰ → Morning briefing: connect Gmail + Calendar and test the spoken briefing. */
class BriefingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val core = (application as XarvisApp).core
        setContent {
            XarvisTheme {
                BriefingScreen(
                    email = core::briefEmail, appPw = core::briefAppPw, ical = core::briefIcal, city = core::briefCity,
                    save = core::saveBrief,
                    buildBriefing = core::buildBriefing,
                    onBack = ::finish,
                )
            }
        }
    }
}
