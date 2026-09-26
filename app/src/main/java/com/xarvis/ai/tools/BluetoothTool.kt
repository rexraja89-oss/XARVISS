package com.xarvis.ai.tools

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** "What's connected over Bluetooth?": whether Bluetooth is on, what's connected now, and what's paired. */
class BluetoothTool(context: Context) : DeviceTool {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(BluetoothManager::class.java)

    override val label = "Bluetooth"

    override fun matches(message: String) = TOPIC.containsMatchIn(message.lowercase())

    @SuppressLint("MissingPermission") // checked below; everything is also wrapped in runCatching
    override suspend fun read(message: String): String {
        val adapter = manager?.adapter ?: return "Bluetooth: this phone has no Bluetooth."
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!PermissionGate.has(appContext, CONNECT)) PermissionGate.request(CONNECT)
            if (!PermissionGate.has(appContext, CONNECT)) {
                return "Bluetooth: unavailable, because XARVIS isn't allowed to see nearby devices. " +
                    "The user can allow it when XARVIS asks, or in Settings > Apps > XARVIS > Permissions."
            }
        }
        if (!adapter.isEnabled) return "Bluetooth: turned off."

        val connected = connectedDevices(adapter)
        val paired = runCatching { adapter.bondedDevices.orEmpty() }.getOrDefault(emptySet())
            .filter { d -> connected.none { it.address == d.address } }
        return buildString {
            append("Bluetooth: on. ")
            append(
                if (connected.isEmpty()) "Nothing is connected right now."
                else "Connected now: " + connected.joinToString { describe(it) } + "."
            )
            if (paired.isNotEmpty()) append(" Paired but not connected: " + paired.joinToString { describe(it) } + ".")
        }
    }

    /** Devices connected through the common profiles (headphones, speakers, car kits, hearing aids, BLE). */
    @SuppressLint("MissingPermission")
    private suspend fun connectedDevices(adapter: BluetoothAdapter): List<BluetoothDevice> = coroutineScope {
        val profiles = buildList {
            add(BluetoothProfile.A2DP)
            add(BluetoothProfile.HEADSET)
            add(BluetoothProfile.HEARING_AID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(BluetoothProfile.LE_AUDIO)
        }
        val viaProfiles = profiles.map { async { connectedOn(adapter, it) } }.awaitAll().flatten()
        val viaGatt = runCatching { manager?.getConnectedDevices(BluetoothProfile.GATT).orEmpty() }.getOrDefault(emptyList())
        (viaProfiles + viaGatt).distinctBy { it.address }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectedOn(adapter: BluetoothAdapter, profile: Int): List<BluetoothDevice> =
        withTimeoutOrNull(PROXY_TIMEOUT_MS) {
            suspendCancellableCoroutine<List<BluetoothDevice>> { cont ->
                val listener = object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        val devices = runCatching { proxy.connectedDevices.orEmpty() }.getOrDefault(emptyList())
                        runCatching { adapter.closeProfileProxy(p, proxy) }
                        if (cont.isActive) cont.resume(devices)
                    }

                    override fun onServiceDisconnected(p: Int) {}
                }
                val started = runCatching { adapter.getProfileProxy(appContext, listener, profile) }.getOrDefault(false)
                if (!started && cont.isActive) cont.resume(emptyList())
            }
        } ?: emptyList()

    @SuppressLint("MissingPermission")
    private fun describe(d: BluetoothDevice): String {
        val name = runCatching { d.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: "unnamed device"
        val kind = when (runCatching { d.bluetoothClass?.majorDeviceClass }.getOrNull()) {
            android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO -> "audio"
            android.bluetooth.BluetoothClass.Device.Major.WEARABLE -> "wearable"
            android.bluetooth.BluetoothClass.Device.Major.PHONE -> "phone"
            android.bluetooth.BluetoothClass.Device.Major.COMPUTER -> "computer"
            android.bluetooth.BluetoothClass.Device.Major.PERIPHERAL -> "input device"
            else -> null
        }
        return if (kind != null) "$name ($kind)" else name
    }

    private companion object {
        const val CONNECT = Manifest.permission.BLUETOOTH_CONNECT
        const val PROXY_TIMEOUT_MS = 3_000L
        val TOPIC = Regex(
            """\b(bluetooth|blue tooth|earbuds?|earphones?|headphones?|headset|airpods|buds|""" +
                """speaker|smartwatch|smart watch|paired|connected to)\b"""
        )
    }
}
