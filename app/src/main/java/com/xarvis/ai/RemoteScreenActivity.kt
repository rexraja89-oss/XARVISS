package com.xarvis.ai

import com.xarvis.ai.net.DeviceLink
import com.xarvis.ai.view.RemoteViewActivity

/**
 * Remote control of the linked phone, like a remote-desktop app. The screen itself lives in :shared
 * ([RemoteViewActivity]) so the separate XARVIS Remote app shows exactly the same one.
 */
class RemoteScreenActivity : RemoteViewActivity() {
    override val link: DeviceLink get() = (application as XarvisApp).core.deviceLink

    companion object {
        /** The linked phone to control, by name (optional: else the first one that answers). */
        const val EXTRA_DEVICE = RemoteViewActivity.EXTRA_DEVICE
        /** Or by id (a family phone with XARVIS Helper, from ☰ → Help a family phone or its help notification). */
        const val EXTRA_PEER_ID = RemoteViewActivity.EXTRA_PEER_ID
    }
}
