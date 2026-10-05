package com.xarvis.ai.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * "XARVIS Hands" — the Accessibility service that will let XARVIS tap and type inside other apps
 * for Rex (e.g. open imo, find a contact, start the call), only for actions he asks for and watches.
 *
 * This is the INSTALL-TEST stub: it is declared so we can confirm the S22 still installs the app
 * with an Accessibility service present, before building the real automation. It does nothing yet.
 */
class XarvisA11y : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}
