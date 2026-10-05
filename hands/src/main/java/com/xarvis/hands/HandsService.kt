package com.xarvis.hands

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * XARVIS Hands — the Accessibility service.
 *
 * This is the INSTALL-TEST version: it is only here so the S22 is asked to install an app that holds
 * the Accessibility permission. It performs no automation yet. Once we know the S22 accepts it, this
 * is where the real work goes — finding a button by its label and tapping it (e.g. the call button in
 * imo or WhatsApp) through [dispatchGesture]/[performGlobalAction], only for things Rex asks for and
 * watches. It will never auto-submit payments, passwords, OTPs or signatures.
 */
class HandsService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "XARVIS Hands connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Nothing yet — the install test only needs the service to exist and connect.
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

        fun isRunning(): Boolean = instance != null
    }
}
