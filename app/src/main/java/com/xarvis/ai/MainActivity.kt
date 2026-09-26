package com.xarvis.ai

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
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
        askBatteryExemptionOnce()
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
        } else {
            askBatteryExemptionOnce()
        }
    }

    /**
     * Battery saving can pause XARVIS's Wi-Fi while the screen is off, which drops linked
     * devices. Ask once to be exempt; if the user says no, don't nag on every launch.
     */
    @SuppressLint("BatteryLife") // a personal, sideloaded app whose whole job is staying reachable
    private fun askBatteryExemptionOnce() {
        if (!AlwaysOn.isEnabled(this)) return
        val power = getSystemService(PowerManager::class.java) ?: return
        if (power.isIgnoringBatteryOptimizations(packageName)) return
        val prefs = getSharedPreferences("service", MODE_PRIVATE)
        if (prefs.getBoolean(ASKED_BATTERY, false)) return
        prefs.edit().putBoolean(ASKED_BATTERY, true).apply()
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }.onFailure { Log.w(TAG, "Couldn't ask for the battery exemption", it) }
    }

    private companion object {
        const val TAG = "MainActivity"
        const val ASKED_BATTERY = "askedBatteryExemption"
    }
}
