package com.shilapi.xcertplay.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.HandlerThread
import android.util.Log
import com.shilapi.xcertplay.transport.Iap2LocationProvider

/**
 * Foreground Android location source for iAP2 LocationInformation.
 *
 * The freshest usable fix from GPS, fused, or network location is sent once.
 * Location callbacks must not wait behind rendering or settings work on the main looper.
 */
class AndroidCarPlayLocationProvider(
    context: Context,
    preferredLocationAgeMillis: Long = DEFAULT_PREFERRED_LOCATION_AGE_MILLIS,
) : Iap2LocationProvider {
    private val locationManager =
        context.applicationContext.getSystemService(LocationManager::class.java)
    private val stateLock = Any()
    private val preferredProviders = buildList {
        add(LocationManager.GPS_PROVIDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(LocationManager.FUSED_PROVIDER)
        }
        add(LocationManager.NETWORK_PROVIDER)
    }.filter { it in locationManager.allProviders }
    // All subscription and fix state is guarded by stateLock. Each subscription gets a distinct
    // listener so a late callback/removal from a stopped session cannot affect its replacement.
    private val fixes = FreshLocationState(preferredLocationAgeMillis)
    private val disabledProviders = mutableSetOf<String>()
    private var subscription: Subscription? = null

    private fun createListener() = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val provider = location.provider ?: return
            synchronized(stateLock) {
                if (subscription?.listener !== this || provider !in preferredProviders ||
                    provider in disabledProviders) return
                fixes.update(location)
            }
        }

        override fun onProviderEnabled(provider: String) {
            synchronized(stateLock) {
                if (subscription?.listener === this) disabledProviders.remove(provider)
            }
        }

        override fun onProviderDisabled(provider: String) {
            synchronized(stateLock) {
                if (subscription?.listener !== this) return
                disabledProviders.add(provider)
                fixes.remove(provider)
            }
        }

        override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) = Unit
    }

    @SuppressLint("MissingPermission")
    override fun start(): Boolean = synchronized(stateLock) {
        if (subscription != null) {
            true
        } else {
            val thread = HandlerThread("CarPlayLocation").apply { start() }
            val next = Subscription(createListener(), thread)
            subscription = next
            seedLastKnownLocations()
            var subscribedProviders = 0
            for (provider in preferredProviders) {
                try {
                    locationManager.requestLocationUpdates(
                        provider,
                        UPDATE_INTERVAL_MILLIS,
                        MIN_DISTANCE_METERS,
                        next.listener,
                        thread.looper,
                    )
                    subscribedProviders++
                } catch (error: Exception) {
                    fixes.remove(provider)
                    Log.w(TAG, "Could not subscribe to Android location provider $provider", error)
                }
            }
            if (subscribedProviders == 0) {
                subscription = null
                fixes.clear()
                disabledProviders.clear()
                release(next)
                Log.w(TAG, "No Android location provider could be started")
            }
            subscribedProviders > 0
        }
    }

    @SuppressLint("MissingPermission")
    private fun seedLastKnownLocations() {
        for (provider in preferredProviders) {
            val location = try {
                // This is only a snapshot for seeding. The provider may be enabled before the
                // listener is registered, so only live callbacks may latch the disabled state.
                if (!locationManager.isProviderEnabled(provider)) continue
                locationManager.getLastKnownLocation(provider)
            } catch (error: Exception) {
                Log.w(TAG, "Could not read last known location from $provider", error)
                null
            } ?: continue
            fixes.update(location)
        }
    }

    override fun stop() {
        val previous = synchronized(stateLock) {
            val previous = subscription ?: return
            subscription = null
            fixes.clear()
            disabledProviders.clear()
            previous
        }
        release(previous)
    }

    private fun release(previous: Subscription) {
        try {
            locationManager.removeUpdates(previous.listener)
        } catch (_: Exception) {
            // Already unregistered.
        } finally {
            // Late queued callbacks see a different subscription and do nothing. Do not join the
            // callback thread while holding stateLock or block the iAP2 close path on its exit.
            previous.thread.quitSafely()
        }
    }

    override fun latestNmea(): String? = synchronized(stateLock) {
        if (subscription == null) return@synchronized null
        try {
            fixes.latestNmea()
        } catch (error: Exception) {
            Log.w(TAG, "Could not encode Android location as NMEA", error)
            null
        }
    }

    private class Subscription(val listener: LocationListener, val thread: HandlerThread)

    companion object {
        private const val TAG = "CarPlayLocation"
        private const val UPDATE_INTERVAL_MILLIS = 500L
        private const val MIN_DISTANCE_METERS = 0f
        // Allow a normal 1 Hz receiver two missed updates without repeatedly toggling validity.
        // After three seconds notify the phone of fix loss, rather than replaying an old road position.
        private const val DEFAULT_PREFERRED_LOCATION_AGE_MILLIS = 3_000L
    }
}
