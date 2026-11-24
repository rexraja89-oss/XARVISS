package com.xarvis.ai.view

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets

/**
 * Android 15+ draws every screen of an app that targets it under the status bar and the navigation bar
 * ("edge-to-edge"); Android 16 no longer lets an app opt out. XARVIS's plain screens were made for Android 13, where
 * the system kept them clear of the bars, so on the newer system their top line and bottom buttons would sit under
 * the bars. This pads each screen's content by the bars (and the keyboard) on Android 15+ only — Android 13/14 phones
 * look exactly as before. Screens that already handle the bars themselves are listed in [skip].
 */
object BarInsets {
    fun install(app: Application, skip: Set<String> = emptySet()) {
        if (Build.VERSION.SDK_INT < 35) return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity.javaClass.name in skip) return
                pad(activity.findViewById(android.R.id.content) ?: return)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** Keeps [content] clear of the system bars, the camera cut-out and the keyboard. */
    fun pad(content: View) {
        if (Build.VERSION.SDK_INT < 35) return
        content.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsets.CONSUMED
        }
        content.requestApplyInsets()
    }
}
