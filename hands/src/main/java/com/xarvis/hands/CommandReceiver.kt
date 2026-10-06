package com.xarvis.hands

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives a "call <contact> in imo" request from the main XARVIS app and hands it to the
 * Accessibility service to carry out. Protected by the signature permission in the manifest, so only
 * Rex's own same-key apps can reach it.
 *
 * If the service is turned on, the call starts immediately. If it isn't (Rex hasn't enabled XARVIS
 * Hands in Accessibility), the request is remembered and runs the moment the service connects, and
 * the Hands app is opened so Rex can turn it on.
 */
class CommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.xarvis.hands.action.CALL_IN_APP") return
        val contact = intent.getStringExtra("contact")?.trim().orEmpty()
        val app = intent.getStringExtra("app")?.trim().orEmpty().ifEmpty { "imo" }
        val video = intent.getBooleanExtra("video", false)
        if (contact.isEmpty()) return

        val service = HandsService.instance
        if (service != null) {
            HandsService.log("XARVIS asked: call '$contact' in $app${if (video) " (video)" else ""}.")
            service.startCallInApp(app, contact, video)
        } else {
            HandsService.pendingCall = Triple(app, contact, video)
            HandsService.log("XARVIS asked to call '$contact', but Hands is off — turn it on.")
            try {
                context.startActivity(
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e: Exception) {
                // can't open the screen; the pending call still runs once Hands is enabled
            }
        }
    }
}
