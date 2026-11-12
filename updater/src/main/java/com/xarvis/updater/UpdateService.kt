package com.xarvis.updater

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * "Update everything" without a screen, for XARVIS (Rex on the benco: "update my S22"). Only XARVIS may bind it: the
 * service needs XARVIS's own signature permission (com.xarvis.ai.permission.EYES). XARVIS binds, sends [MSG_UPDATE_ALL]
 * with a Messenger for the answers; each milestone comes back as [MSG_LINE] and the end as [MSG_DONE] (text in
 * [KEY_TEXT]). XARVIS's binding keeps this app running meanwhile; when XARVIS itself is replaced the work carries on,
 * and when the Updater replaces itself (always last) this app ends — XARVIS reports the versions after that.
 */
class UpdateService : Service() {
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what != MSG_UPDATE_ALL) return
            val reply = msg.replyTo ?: return
            start(reply)
        }
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun start(reply: Messenger) {
        val app = applicationContext
        scope.launch {
            val end = runCatching { UpdateEngine.updateAll(app) { line -> send(reply, MSG_LINE, line) } }
                .getOrElse { "⚠ XARVIS Updater stopped: ${it.message ?: it.javaClass.simpleName}" }
            send(reply, MSG_DONE, end)
        }
    }

    private fun send(to: Messenger, what: Int, text: String) {
        runCatching { to.send(Message.obtain(null, what).apply { data = Bundle().apply { putString(KEY_TEXT, text) } }) }
    }

    companion object {
        const val MSG_UPDATE_ALL = 1
        const val MSG_LINE = 2
        const val MSG_DONE = 3
        const val KEY_TEXT = "text"

        /** Outlives the service object: the work goes on after XARVIS (the one bound) is replaced. */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
