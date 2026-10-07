package com.shilapi.xcertplay

import android.location.Location
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class LocationFixPolicyTest {
    @Before fun reset() {
        LocationFixPolicy.reset()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
    }
    private fun fix(provider: String = "gps", ageMs: Long = 0, metres: Float = 3f) = Location(provider).apply {
        latitude = 31.2; longitude = 121.5; accuracy = metres
        time = 1700000000123; elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - ageMs * 1_000_000
    }
    private fun select(vararg fixes: Location) = LocationFixPolicy.select(fixes.toList(), SystemClock.elapsedRealtimeNanos())
    @Test fun selectsFreshAccurateFixAcrossGpsAndFused() {
        assertEquals("fused", select(fix(ageMs = 1000), fix("fused", 100, 3.8f))?.provider)
    }
    @Test fun withholdsStaleNetworkFutureAndUnknownAccuracy() {
        assertNull(select(fix(ageMs = 1501), fix("network"), fix(ageMs = -1), fix(metres = 11f), fix().apply { removeAccuracy() }))
    }
    @Test fun rejectsJumpButReacquiresAfterOutage() {
        assertNotNull(select(fix()))
        ShadowSystemClock.advanceBy(Duration.ofMillis(500))
        assertNull(select(fix().apply { longitude += 0.1 }))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
        assertNotNull(select(fix().apply { longitude += 0.1 }))
    }
    @Test fun nmeaPreservesUtcAndOmitsUnmeasuredValues() {
        val location = fix("fused")
        val nmea = requireNotNull(LocationFixPolicy.encode(location))
        val rows = nmea.trim().split("\r\n")
        val gga = rows[0].substringBefore('*').split(',')
        val rmc = rows[1].substringBefore('*').split(',')
        assertTrue(gga[1].endsWith(".123"))
        listOf(7, 8, 9, 11).forEach { assertEquals("", gga[it]) }
        listOf(7, 8).forEach { assertEquals("", rmc[it]) }
        rows.forEach { row ->
            val sum = row.substring(1).substringBefore('*').fold(0) { acc, char -> acc xor char.code }
            assertEquals(sum, row.substringAfter('*').toInt(16))
            assertTrue(row.length + 2 <= 82)
        }
        assertNull(LocationFixPolicy.encode(location))
    }
}
