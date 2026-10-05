package com.xarvis.hands

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * XARVIS Hands — the Accessibility service.
 *
 * This is the DIAGNOSTIC version: it only observes. It proves two things on the S22, with no
 * automation whatsoever:
 *   1. that the service starts/connects ([onServiceConnected] sets [instance]), and
 *   2. that it actually receives accessibility events ([onAccessibilityEvent] counts them and
 *      records the last one).
 *
 * It performs NO gestures, NO taps, NO navigation, and touches NO other app (imo etc.). The real
 * tap-inside-apps automation comes in a later build, only after this chain of evidence is complete.
 */
class HandsService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "XARVIS Hands connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Observe only: count the event and remember what it was. Nothing is acted on.
        if (event == null) return
        eventCount++
        lastEventType = AccessibilityEvent.eventTypeToString(event.eventType)
        lastEventPackage = event.packageName?.toString() ?: "?"
        lastEventTime = System.currentTimeMillis()
    }

    override fun onInterrupt() {
        Log.i(TAG, "XARVIS Hands interrupted.")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "XarvisHands"

        /** Set while the service is turned on and connected; the app checks it to show "ready". */
        @Volatile
        var instance: HandsService? = null
            private set

        /** How many accessibility events the service has received since it connected. */
        @Volatile
        var eventCount: Long = 0L
            private set

        /** Details of the most recent event, for the diagnostic screen. */
        @Volatile
        var lastEventType: String = ""
            private set

        @Volatile
        var lastEventPackage: String = ""
            private set

        @Volatile
        var lastEventTime: Long = 0L
            private set

        fun isRunning(): Boolean = instance != null
    }
}
