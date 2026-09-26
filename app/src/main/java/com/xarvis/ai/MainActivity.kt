package com.xarvis.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.xarvis.ai.ui.XarvisScreen
import com.xarvis.ai.ui.theme.XarvisTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            XarvisTheme {
                XarvisScreen()
            }
        }
    }
}
