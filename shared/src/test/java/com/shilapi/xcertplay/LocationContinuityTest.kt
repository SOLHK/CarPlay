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
class LocationContinuityTest {
    @Before fun reset() { LocationFixPolicy.reset(); tick(10000) }
    private fun tick(ms: Long) = ShadowSystemClock.advanceBy(Duration.ofMillis(ms))
    private fun fix(provider: String = "gps", accuracy: Float = 3f) = Location(provider).apply {
        latitude = 31.2; longitude = 121.5; this.accuracy = accuracy
        time = 1700000000123; elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }
    private fun report(vararg f: Location) = LocationFixPolicy.report(f.toList())
    private fun assertVoid(nmea: String?) {
        val rows = requireNotNull(nmea).trim().split("\r\n")
        val gga = rows[0].substringBefore('*').split(',')
        val rmc = rows[1].substringBefore('*').split(',')
        assertEquals("0", gga[6]); assertEquals("V", rmc[2])
        listOf(2,3,4,5).forEach { assertEquals("", gga[it]) }
        listOf(3,4,5,6).forEach { assertEquals("", rmc[it]) }
        rows.forEach { row ->
            assertEquals(row.substringAfter('*').toInt(16), row.substring(1).substringBefore('*').fold(0) { a,c -> a xor c.code })
            assertTrue(row.length + 2 <= 82)
        }
    }
    @Test fun sentPreciseGpsDoesNotStarveNewValidFusedFix() {
        val gps = fix(accuracy = 1f); assertNotNull(report(gps)); tick(500)
        val fused = fix("fused", 5f)
        assertEquals("fused", LocationFixPolicy.select(listOf(gps, fused), SystemClock.elapsedRealtimeNanos())?.provider)
        assertNotNull(report(gps, fused))
    }
    @Test fun liveDuplicateDoesNotEmitAnInvalidFix() {
        val f = fix(); assertNotNull(report(f)); tick(1000); assertNull(report(f))
    }
    @Test fun expiredFixEmitsOneVoidPairThenWaitsForFreshData() {
        val f = fix(); assertNotNull(report(f)); tick(1501); assertVoid(report(f))
        repeat(10) { tick(1000); assertNull(report(f)) }
        assertTrue(requireNotNull(report(fix())).contains(",A,"))
    }
    @Test fun noFixAtStartupSendsNoInventedPosition() { assertNull(report()); assertNull(report(fix("network"))) }
    @Test fun qualityLossAndProviderRemovalInvalidateWithoutResendingCoordinates() {
        assertNotNull(report(fix())); tick(500); assertVoid(report(fix(accuracy = 11f)))
        tick(500); assertNotNull(report(fix())); assertVoid(report()); assertNull(report())
    }
    @Test fun rejectedJumpDoesNotProduceAnActivePosition() {
        assertNotNull(report(fix())); tick(500); assertVoid(report(fix().apply { longitude += 0.1 }))
    }
    @Test fun resetDoesNotCarryValidityOrDuplicateStateIntoNextSession() {
        val f = fix(); assertNotNull(report(f)); LocationFixPolicy.reset(); assertNull(report()); assertNotNull(report(f))
    }
    @Test fun twoHundredAlternatingSourcesDoNotLoseFreshUpdates() {
        repeat(200) {
            tick(500); val gps = fix(accuracy = 1f); assertNotNull(report(gps))
            tick(500); val fused = fix("fused", 5f); assertNotNull(report(gps, fused)); assertNull(report(gps, fused))
        }
        assertTrue(LocationFixPolicy.status().contains("失效报告=0"))
    }
}
