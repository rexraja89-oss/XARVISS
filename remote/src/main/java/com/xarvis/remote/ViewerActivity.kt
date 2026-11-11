package com.xarvis.remote

import com.xarvis.ai.net.DeviceLink
import com.xarvis.ai.view.RemoteViewActivity

/** Controlling a linked phone: the very same screen as XARVIS's remote control, over XARVIS Remote's own link. */
class ViewerActivity : RemoteViewActivity() {
    override val link: DeviceLink get() = RemoteApp.link(this)

    // The other side is XARVIS Remote, which doesn't take files (XARVIS does).
    override val canSendFiles: Boolean = false
}
