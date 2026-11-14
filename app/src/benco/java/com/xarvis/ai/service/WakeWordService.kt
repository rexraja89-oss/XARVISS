package com.xarvis.ai.service

import android.content.Context

/**
 * The benco's XARVIS has no microphone permission (the benco refuses to install it with one), so it
 * can't listen for "Xarvis" itself: the small XARVIS Ears app does, and talks to XARVIS through
 * ears/EarsLinkService. This stands in for the S22 build's service (src/full); it's never started,
 * since WakeWord.available is false without the permission.
 */
object WakeWordService {
    fun start(@Suppress("UNUSED_PARAMETER") context: Context) {}

    fun stop(@Suppress("UNUSED_PARAMETER") context: Context) {}
}
