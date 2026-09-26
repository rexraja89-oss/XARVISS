package com.xarvis.ai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.xarvis.ai.service.AlwaysOn
import com.xarvis.ai.service.XarvisService
import com.xarvis.ai.ui.XarvisScreen
import com.xarvis.ai.ui.theme.XarvisTheme

class MainActivity : ComponentActivity() {

    // Only needed so the "XARVIS is running" notification is visible; the service runs either way.
    // A notification posted before the grant is dropped, so re-post it once permission arrives.
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && AlwaysOn.isEnabled(this)) XarvisService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            XarvisTheme {
                XarvisScreen()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
