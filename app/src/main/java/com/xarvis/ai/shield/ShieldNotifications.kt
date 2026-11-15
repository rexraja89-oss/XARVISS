package com.xarvis.ai.shield

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.xarvis.ai.hands.HandsBridge

/**
 * Notification access for XARVIS Shield on a phone without XARVIS Hands (the benco): Rex switches it on in
 * Settings → Notification access → XARVIS. Each new notification goes the same way as Hands' (Board → Shield).
 */
class ShieldNotifications : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val e = n.extras ?: return
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        // On the S22 Hands already passes these on; the Shield ignores a repeat within 10 minutes.
        HandsBridge.notification?.invoke(sbn.packageName, title, text)
    }
}
