package com.shilapi.xcertplay.location

import com.shilapi.xcertplay.GnssTelemetry
import com.shilapi.xcertplay.LocationFixPolicy
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground Android location source for iAP2 LocationInformation.
 *
 * Only recent, accurate GPS/fused fixes pass the quality gate; network fixes are not sent.
 */
class AndroidCarPlayLocationProvider(
    context: Context,
) : Iap2LocationProvider {
    private val locationManager =
        context.applicationContext.getSystemService(LocationManager::class.java)
    private val stateLock = Any()
    private val preferredProviders = buildList {
        add(LocationManager.GPS_PROVIDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(LocationManager.FUSED_PROVIDER)
        }
    }.filter { it in locationManager.allProviders }
    private val latestFixes = ConcurrentHashMap<String, Location>()
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val provider = location.provider ?: return
            synchronized(stateLock) {
                if (!started || provider !in preferredProviders) return
                val previous = latestFixes[provider]
                if (previous != null && location.elapsedRealtimeNanos <= previous.elapsedRealtimeNanos) return
                latestFixes[provider] = Location(location)
                GnssTelemetry.onFix(location)
            }
        }

        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) {
            synchronized(stateLock) { latestFixes.remove(provider) }
        }
        override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) = Unit
    }

    private var started = false

    @SuppressLint("MissingPermission")
    override fun start(): Boolean = synchronized(stateLock) {
        if (started) {
            true
        } else {
            GnssTelemetry.start(locationManager)
            seedLastKnownLocations()
            var subscribedProviders = 0
            for (provider in preferredProviders) {
                try {
                    locationManager.requestLocationUpdates(
                        provider,
                        UPDATE_INTERVAL_MILLIS,
                        MIN_DISTANCE_METERS,
                        listener,
                        GnssTelemetry.looper(),
                    )
                    subscribedProviders++
                } catch (error: Exception) {
                    Log.w(TAG, "Could not subscribe to Android location provider $provider", error)
                }
            }
            started = subscribedProviders > 0
            if (!started) {
                GnssTelemetry.stop(locationManager)
                Log.w(TAG, "No Android location provider could be started")
            }
            started
        }
    }

    @SuppressLint("MissingPermission")
    private fun seedLastKnownLocations() {
        for (provider in preferredProviders) {
            val location = try {
                if (!locationManager.isProviderEnabled(provider)) continue
                locationManager.getLastKnownLocation(provider)
            } catch (error: Exception) {
                Log.w(TAG, "Could not read last known location from $provider", error)
                null
            } ?: continue
            latestFixes[provider] = location
            Log.i(
                TAG,
                "Seeded $provider location ageMs=${(System.currentTimeMillis() - location.time).coerceAtLeast(0)}",
            )
        }
    }

    override fun stop() = synchronized(stateLock) {
        if (!started) return@synchronized
        started = false
        try {
            locationManager.removeUpdates(listener)
        } catch (_: Exception) {
            // Already unregistered.
        } finally {
            GnssTelemetry.stop(locationManager)
            latestFixes.clear()
        }
    }

    override fun latestNmea(): String? = synchronized(stateLock) {
        if (!started) return@synchronized null
        runCatching { LocationFixPolicy.report(latestFixes.values.toList()) }
            .getOrElse { Log.w(TAG, "Could not encode location", it); null }
    }

    companion object {
        private const val TAG = "CarPlayLocation"
        private const val UPDATE_INTERVAL_MILLIS = 500L
        private const val MIN_DISTANCE_METERS = 0f
    }
}
