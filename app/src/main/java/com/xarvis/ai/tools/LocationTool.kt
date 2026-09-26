package com.xarvis.ai.tools

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** "Where am I?": the phone's current position and, when a geocoder is available, its street address. */
class LocationTool(context: Context) {

    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)



    suspend fun read(): String {
        if (!PermissionGate.has(appContext, FINE)) PermissionGate.request(FINE, COARSE)
        val fine = PermissionGate.has(appContext, FINE)
        if (!fine && !PermissionGate.has(appContext, COARSE)) {
            return "Current location: unavailable, because XARVIS isn't allowed to use location. " +
                "Tap Allow when XARVIS asks, or turn it on in Settings > Apps > XARVIS > Permissions."
        }
        if (locationManager != null && !LocationManagerCompat.isLocationEnabled(locationManager)) {
            return "Current location: unavailable, because Location is turned off on this phone."
        }
        val location = currentLocation(fine)
            ?: return "Current location: unavailable, because the phone couldn't get a position fix right now."
        val coords = String.format(Locale.US, "%.5f, %.5f", location.latitude, location.longitude)
        val address = address(location)
        return if (address != null) "Current location: $address ($coords)"
        else "Current location: street address unknown ($coords)"
    }

    // ---- Position ----

    /** Google's fused provider first; the plain Android provider on phones without Play services. */
    @SuppressLint("MissingPermission") // checked in read()
    private suspend fun currentLocation(fine: Boolean): Location? {
        val fused = runCatching {
            val client = LocationServices.getFusedLocationProviderClient(appContext)
            val request = CurrentLocationRequest.Builder()
                .setPriority(if (fine) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .setMaxUpdateAgeMillis(MAX_AGE_MS)
                .setDurationMillis(FIX_TIMEOUT_MS)
                .build()
            val cancel = CancellationTokenSource()
            withTimeoutOrNull(FIX_TIMEOUT_MS + 2_000) {
                try {
                    client.getCurrentLocation(request, cancel.token).await()
                } finally {
                    cancel.cancel()
                }
            } ?: client.lastLocation.await()
        }.onFailure { Log.w(TAG, "Fused location failed, using the Android provider", it) }.getOrNull()
        return fused ?: platformLocation(fine)
    }

    @SuppressLint("MissingPermission")
    private suspend fun platformLocation(fine: Boolean): Location? {
        val lm = locationManager ?: return null
        val provider = listOf(
            LocationManager.GPS_PROVIDER.takeIf { fine },
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).filterNotNull().firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) } ?: return null
        val current = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            withTimeoutOrNull(FIX_TIMEOUT_MS) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    runCatching {
                        lm.getCurrentLocation(provider, signal, appContext.mainExecutor) { cont.resume(it) }
                    }.onFailure { if (cont.isActive) cont.resume(null) }
                }
            }
        } else {
            null
        }
        return current ?: runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
    }

    // ---- Address ----

    private suspend fun address(location: Location): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(appContext, Locale.getDefault())
        val found: Address? = withTimeoutOrNull(GEOCODE_TIMEOUT_MS) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine<Address?> { cont ->
                    geocoder.getFromLocation(location.latitude, location.longitude, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (cont.isActive) cont.resume(addresses.firstOrNull())
                        }

                        override fun onError(errorMessage: String?) {
                            Log.w(TAG, "Geocoder failed: $errorMessage")
                            if (cont.isActive) cont.resume(null)
                        }
                    })
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    runCatching { geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull() }
                        .getOrNull()
                }
            }
        }
        return found?.let(::describe)
    }

    private fun describe(a: Address): String? {
        a.getAddressLine(0)?.takeIf { it.isNotBlank() }?.let { return it }
        return listOfNotNull(a.thoroughfare, a.subLocality, a.locality, a.adminArea, a.countryName)
            .distinct().joinToString(", ").ifBlank { null }
    }

    private suspend fun <T> Task<T>.await(): T? = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { if (cont.isActive) cont.resume(it) }
        addOnFailureListener {
            Log.w(TAG, "Location request failed", it)
            if (cont.isActive) cont.resume(null)
        }
        addOnCanceledListener { if (cont.isActive) cont.resume(null) }
    }

    private companion object {
        const val TAG = "XarvisLocation"
        const val FINE = Manifest.permission.ACCESS_FINE_LOCATION
        const val COARSE = Manifest.permission.ACCESS_COARSE_LOCATION
        const val MAX_AGE_MS = 30_000L
        const val FIX_TIMEOUT_MS = 15_000L
        const val GEOCODE_TIMEOUT_MS = 10_000L
    }
}
