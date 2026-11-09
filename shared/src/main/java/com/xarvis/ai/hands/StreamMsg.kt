package com.xarvis.ai.hands

/** The message types on a remote-control stream (DeviceLink.Channel), host → viewer and back. */
object StreamMsg {
    const val VIDEO_CONFIG = 1 // H.264 codec setup (host → viewer)
    const val VIDEO_FRAME = 2  // one H.264 frame
    const val JPEG = 3         // a still picture (slow mode, no screen-sharing permission)
    const val INPUT = 4        // key / text / gesture JSON, as ScreenHost.input (viewer → host)
    const val TOUCH = 5        // a live finger: {"a":"down"|"move"|"up","x":0–1,"y":0–1}
    const val PING = 6         // 8 bytes of the viewer's clock, sent back as PONG (shows the delay)
    const val PONG = 7
    const val KEY_FRAME = 8    // viewer: its decoder just started, send a full picture now
    const val INFO = 9         // host → viewer JSON, e.g. {"switch":"video"} once smooth mode is allowed
    const val BYE = 10
}
