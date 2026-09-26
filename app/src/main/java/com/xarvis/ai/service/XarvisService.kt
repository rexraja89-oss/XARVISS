package com.xarvis.ai.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.xarvis.ai.MainActivity
import com.xarvis.ai.R
import com.xarvis.ai.XarvisApp
import com.xarvis.ai.XarvisUiState
import com.xarvis.ai.llm.LlmStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps XARVIS's process alive so linked devices can always reach it, even when the app
 * isn't open. Shows a small ongoing notification (required for foreground services) with
 * the link and AI status and a Stop button.
 */
class XarvisService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching = false

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AlwaysOn.set(this, false)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val core = (application as XarvisApp).core
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(core.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        if (!watching) {
            watching = true
            val manager = getSystemService(NotificationManager::class.java)
            scope.launch {
                core.state.map { it.linkedCount to it.llmStatus }.distinctUntilChanged().collect {
                    manager.notify(NOTIFICATION_ID, buildNotification(core.state.value))
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(state: XarvisUiState): android.app.Notification {
        createChannel(this)
        val linked = when (state.linkedCount) {
            0 -> "No linked devices"
            1 -> "Linked with 1 device"
            else -> "Linked with ${state.linkedCount} devices"
        }
        val ai = when (state.llmStatus) {
            is LlmStatus.Ready -> "AI ready"
            LlmStatus.Loading -> "AI loading"
            else -> if (state.linkedCount > 0) "AI via linked device" else "AI off"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, XarvisService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_xarvis)
            .setContentTitle("XARVIS is running")
            .setContentText("$linked · $ai")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "XarvisService"
        private const val CHANNEL_ID = "xarvis_running"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.xarvis.ai.STOP"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, XarvisService::class.java))
            } catch (e: Exception) {
                // Android refuses background starts in some states; the next app launch starts it.
                Log.w(TAG, "Couldn't start the background service now", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, XarvisService::class.java))
        }

        private fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, "XARVIS running", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while XARVIS stays available to your linked devices"
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
