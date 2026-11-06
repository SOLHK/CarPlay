package com.shilapi.xcertplay.location

import android.location.Location
import android.os.SystemClock
import com.shilapi.xcertplay.transport.CarPlayLocationFix
import com.shilapi.xcertplay.transport.NmeaLocationEncoder
import java.util.concurrent.TimeUnit

/** Per-subscription position state. The caller serializes access; no state is shared across cars. */
internal class FreshLocationState(
    maxAgeMillis: Long,
    private val elapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    init {
        require(maxAgeMillis > 0) { "Location age limit must be positive" }
    }

    private val maxAgeNanos = TimeUnit.MILLISECONDS.toNanos(maxAgeMillis)
    private val latestFixes = mutableMapOf<String, Location>()
    private var lastReportedNanos = -1L
    private var reportedValid = false

    fun update(location: Location) {
        val provider = location.provider ?: return
        if (!usable(location, elapsedRealtimeNanos())) return
        val previous = latestFixes[provider]
        if (previous != null) {
            if (location.elapsedRealtimeNanos < previous.elapsedRealtimeNanos) return
            if (location.elapsedRealtimeNanos == previous.elapsedRealtimeNanos &&
                (location.elapsedRealtimeNanos <= lastReportedNanos || location.accuracy >= previous.accuracy)) return
        }
        // Framework Location is mutable. Retaining it would let a later mutation silently replace
        // the accepted coordinate/time without passing the freshness and ordering checks again.
        latestFixes[provider] = Location(location)
    }

    fun remove(provider: String) {
        latestFixes.remove(provider)
    }

    fun clear() {
        latestFixes.clear()
        lastReportedNanos = -1L
        reportedValid = false
    }

    fun latestNmea(): String? {
        val now = elapsedRealtimeNanos()
        val fix = latestFixes.values.asSequence()
            .filter { usable(it, now) }
            // A fused/network sample can share GPS's acquisition time. Prefer the reported
            // tighter accuracy only for a tie; a precise old fix must never starve a newer one.
            .maxWithOrNull(compareBy<Location> { it.elapsedRealtimeNanos }.thenByDescending { it.accuracy })
        if (fix != null && fix.elapsedRealtimeNanos > lastReportedNanos) {
            val encoded = NmeaLocationEncoder.encode(
                CarPlayLocationFix(
                    latitudeDegrees = fix.latitude,
                    longitudeDegrees = fix.longitude,
                    altitudeMeters = fix.altitude.takeIf { fix.hasAltitude() },
                    bearingDegrees = fix.bearing.takeIf { fix.hasBearing() }?.toDouble(),
                    speedMetersPerSecond = fix.speed.takeIf { fix.hasSpeed() }?.toDouble(),
                    accuracyMeters = fix.accuracy.takeIf { fix.hasAccuracy() }?.toDouble(),
                    timestampMillis = fix.time,
                ),
            )
            lastReportedNanos = fix.elapsedRealtimeNanos
            reportedValid = true
            return encoded
        }
        // A duplicate still within the grace period is not a new observation and not a lost fix.
        if (fix != null && fix.elapsedRealtimeNanos == lastReportedNanos) return null
        if (!reportedValid) return null
        reportedValid = false
        return NmeaLocationEncoder.unavailable()
    }

    private fun usable(location: Location, nowNanos: Long): Boolean {
        val sampleNanos = location.elapsedRealtimeNanos
        // Location.time can jump or use different clocks for GPS and network. Android guarantees
        // an elapsed-realtime timestamp for LocationManager fixes, so use that for age and order.
        if (sampleNanos < 0 || sampleNanos > nowNanos || nowNanos - sampleNanos > maxAgeNanos) return false
        return location.time > 0 && location.latitude.isFinite() && location.latitude in -90.0..90.0 &&
            location.longitude.isFinite() && location.longitude in -180.0..180.0 &&
            location.hasAccuracy() && location.accuracy.isFinite() && location.accuracy >= 0f
    }
}
