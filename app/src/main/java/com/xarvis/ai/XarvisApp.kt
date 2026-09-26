package com.xarvis.ai

import android.app.Application

class XarvisApp : Application() {
    val core: XarvisCore by lazy { XarvisCore(this) }

    override fun onCreate() {
        super.onCreate()
        core // Start the link server and model load with the process, not the first screen.
    }
}
