package com.xarvis.ai

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * XARVIS Remote's "Copy the relay login from XARVIS" (one tap instead of typing it): hands this phone's relay login
 * back and closes at once, with no screen. Only apps signed with Rex's own key may open it (the manifest requires
 * the signature permission com.xarvis.ai.permission.EYES), so nothing else on the phone can read the login.
 */
class RelayLoginExportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = runCatching { (application as XarvisApp).core.deviceLink.relay.config() }.getOrNull()
        if (cfg == null) {
            setResult(RESULT_CANCELED)
        } else {
            setResult(
                RESULT_OK,
                Intent().putExtra("host", cfg.host).putExtra("port", cfg.port).putExtra("user", cfg.user).putExtra("pass", cfg.pass),
            )
        }
        finish()
    }
}
