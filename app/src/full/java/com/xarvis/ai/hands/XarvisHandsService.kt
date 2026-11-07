package com.xarvis.ai.hands

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/** INSTALL TEST (test-eyes): Hands reduced to an empty shell, to see if the camera part installs on its own. */
class XarvisHandsService : AccessibilityService(), HandsBridge.Hands {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun startCallInApp(app: String, contact: String, video: Boolean) {}
    override fun cancelTask() {}
}
