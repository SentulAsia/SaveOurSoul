package org.saveoursoul.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

class LocationProvider(private val context: Context) {
    private val fused = LocationServices.getFusedLocationProviderClient(context)

    private fun granted() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** A fresh GPS fix if one comes within [timeoutMs], else the last known location, else null. Never blocks an SOS for long. */
    @SuppressLint("MissingPermission")
    suspend fun bestFix(timeoutMs: Long = 8_000): Location? {
        if (!granted()) return null
        val cts = CancellationTokenSource()
        val fresh = runCatching {
            withTimeoutOrNull(timeoutMs) { fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token).await() }
        }.getOrNull()
        cts.cancel()
        return fresh ?: runCatching { fused.lastLocation.await() }.getOrNull()
    }

    /** Whatever the OS already knows, almost instantly — used so the very first frame is never delayed by GPS. */
    @SuppressLint("MissingPermission")
    suspend fun quickFix(): Location? {
        if (!granted()) return null
        return runCatching { withTimeoutOrNull(1_500) { fused.lastLocation.await() } }.getOrNull()
    }

    fun batteryPct(): Int? =
        context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
}
