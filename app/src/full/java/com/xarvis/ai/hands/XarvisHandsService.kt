package com.xarvis.ai.hands

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/** INSTALL TEST shell: only here to see whether the S22 installs XARVIS with an Accessibility service. */
class XarvisHandsService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
