package com.xarvis.ai.tools

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Lets tools ask for a runtime permission from anywhere. Android can only show the
 * permission dialog from a screen, so MainActivity attaches itself while it exists;
 * without it (e.g. XARVIS is only running in the background) requests just return.
 */
object PermissionGate {

    fun interface Requester {
        fun request(permissions: Array<String>, onResult: () -> Unit)
    }

    @Volatile private var requester: Requester? = null

    fun attach(r: Requester) {
        requester = r
    }

    fun detach(r: Requester) {
        if (requester === r) requester = null
    }

    fun has(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Shows the permission dialog for [permissions] and waits for the user's answer. */
    suspend fun request(vararg permissions: String) {
        val r = requester ?: return
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    r.request(arrayOf(*permissions)) { if (cont.isActive) cont.resume(Unit) }
                }
            }
        }
    }

    private const val REQUEST_TIMEOUT_MS = 60_000L
}
