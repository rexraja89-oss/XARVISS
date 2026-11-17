package com.xarvis.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.UserManager
import com.xarvis.ai.net.Peer
import com.xarvis.ai.view.RemoteViewActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Restart my S22" from far away (17 Nov 2026; Rex: "there must be some way so even after restart before the
 * pattern is drawn XARVIS could wake and connect"). After a restart, Android keeps every app asleep until the first
 * unlock — except Direct Boot ones. XARVIS Remote is one: on the S22 it starts at LOCKED_BOOT_COMPLETED and tells
 * each controlling phone (link op "help", over the relay) that it's up and waiting for the pattern; on the benco
 * that's a notification that opens the S22's screen, where Rex draws the pattern himself.
 */
object RestartNews {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = false

    private const val CHANNEL = "xarvis_remote_restart"
    private const val TRY_FOR_MS = 2 * 60 * 60_000L
    private const val GAP_MS = 90_000L

    fun unlocked(context: Context): Boolean =
        context.getSystemService(UserManager::class.java)?.isUserUnlocked != false

    /** On the controlled phone, before its first unlock: keeps telling the controlling phones until each heard it. */
    fun announce(context: Context) {
        if (running) return
        running = true
        val app = context.applicationContext
        scope.launch {
            try {
                delay(10_000) // the network and the relay come up
                val link = RemoteApp.link(app)
                val told = HashSet<String>()
                val end = System.currentTimeMillis() + TRY_FOR_MS
                while (System.currentTimeMillis() < end && !unlocked(app)) {
                    val owners = link.pairedPeers().filter { it.kind == Peer.KIND_OWNER }
                    if (owners.isEmpty()) break
                    for (p in owners) {
                        if (p.id in told) continue
                        runCatching {
                            link.askForHelp(p, "🔄 ${link.deviceName} restarted and is waiting for your pattern. Tap here, then draw it on its screen.")
                            told += p.id
                        }
                    }
                    if (owners.all { it.id in told }) break
                    delay(GAP_MS)
                }
            } finally {
                running = false
            }
        }
    }

    /** On the controlling phone: the news, as a notification that opens that phone's screen. */
    fun show(context: Context, peer: Peer, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Your phone restarted", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A phone you control restarted and waits for its pattern"
            },
        )
        val open = PendingIntent.getActivity(
            context, peer.id.hashCode(),
            Intent(context, ViewerActivity::class.java).putExtra(RemoteViewActivity.EXTRA_PEER_ID, peer.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val body = text.ifBlank { "${peer.name} restarted and is waiting for your pattern." }
        runCatching {
            manager.notify(
                7600 + (peer.id.hashCode() and 0xFF),
                Notification.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_rotate)
                    .setContentTitle("🔄 ${peer.name} is back")
                    .setContentText(body)
                    .setStyle(Notification.BigTextStyle().bigText(body))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }
}
