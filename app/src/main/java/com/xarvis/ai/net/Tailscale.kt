package com.xarvis.ai.net

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log

/**
 * Keeps Tailscale on, so the linked phones reach each other away from home Wi-Fi (Rex kept
 * finding it off on the S22). An app can't switch another app's VPN on directly; Tailscale
 * listens for a "connect" broadcast for automation apps, which XARVIS sends when the tunnel is
 * down, and otherwise XARVIS shows a TURN ON button that opens Tailscale. The lasting fix is
 * Android's "Always-on VPN" for Tailscale (see the button's hint).
 */
object Tailscale {
    const val PACKAGE = "com.tailscale.ipn"
    private const val RECEIVER = "com.tailscale.ipn.IPNReceiver"
    private const val CONNECT = "com.tailscale.ipn.CONNECT_VPN"

    fun installed(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    /** Whether a VPN with a Tailscale (100.64.0.0/10) address is up. */
    fun connected(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        cm.allNetworks.any { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
                cm.getLinkProperties(n)?.linkAddresses.orEmpty().any { DeviceLink.isTailnet(it.address.hostAddress.orEmpty()) }
        }
    }.getOrDefault(false)

    /** Asks Tailscale to connect (quietly; it's a no-op if Tailscale ignores it). */
    fun requestConnect(context: Context) {
        runCatching {
            context.sendBroadcast(Intent(CONNECT).setClassName(PACKAGE, RECEIVER))
        }.onFailure { Log.w("XarvisTailscale", "Couldn't ask Tailscale to connect", it) }
    }

    /** Opens the Tailscale app, for Rex to tap Connect. */
    fun open(context: Context) {
        context.packageManager.getLaunchIntentForPackage(PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
    }
}
