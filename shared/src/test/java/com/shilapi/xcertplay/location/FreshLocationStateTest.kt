package com.shilapi.xcertplay.location

import android.location.Location
import com.shilapi.xcertplay.transport.NmeaLocationEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class FreshLocationStateTest {
    private var nowNanos = 100_000_000_000L
    private val state = FreshLocationState(3_000) { nowNanos }

    @Test
    fun newestMonotonicFixWinsEvenWhenProviderWallClocksDisagree() {
        state.update(fix("gps", ageMillis = 1_000, latitude = 20.0).apply { time += 60_000 })
        state.update(fix("network", latitude = 21.0))

        assertEquals("2100.0000", gga(state.latestNmea())[2])
    }

    @Test
    fun equalAcquisitionTimesChooseReportedAccuracyRegardlessOfCallbackOrder() {
        val coarse = fix("network", latitude = 20.0).apply { accuracy = 500f }
        val precise = fix("gps", latitude = 21.0).apply { accuracy = 5f }
        for (samples in listOf(listOf(coarse, precise), listOf(precise, coarse))) {
            state.clear()
            samples.forEach(state::update)
            assertEquals("2100.0000", gga(state.latestNmea())[2])
        }
    }

    @Test
    fun sameProviderCanRefineAnUnsentSampleButCannotReissueAnAlreadySentTimestamp() {
        val coarse = fix(latitude = 20.0).apply { accuracy = 50f }
        val precise = fix(latitude = 21.0).apply { accuracy = 5f }
        for (samples in listOf(listOf(coarse, precise), listOf(precise, coarse))) {
            state.clear()
            samples.forEach(state::update)
            assertEquals("2100.0000", gga(state.latestNmea())[2])
            state.update(fix(latitude = 22.0).apply { accuracy = 1f })
            assertNull(state.latestNmea())
        }
    }

    @Test
    fun lateCallbackCannotReplaceANewerFixFromTheSameProvider() {
        state.update(fix(latitude = 21.0))
        state.update(fix(ageMillis = 500, latitude = 20.0).apply { time += 60_000 })

        assertEquals("2100.0000", gga(state.latestNmea())[2])
    }

    @Test
    fun oldOrFutureElapsedTimestampsAreRejectedDespiteFreshLookingUtc() {
        state.update(fix(ageMillis = 3_001).apply { time += 86_400_000 })
        state.update(fix("network", ageMillis = -1))

        assertNull(state.latestNmea())
    }

    @Test
    fun duplicateFixIsNotReplayedOrMarkedLostDuringTheGracePeriod() {
        state.update(fix())
        assertNotNull(state.latestNmea())

        repeat(3) {
            nowNanos += 1_000_000_000L
            assertNull(state.latestNmea())
        }
        nowNanos++
        assertEquals(NmeaLocationEncoder.unavailable(), state.latestNmea())
        assertNull(state.latestNmea())
    }

    @Test
    fun aFreshFixResumesAfterOneLossNotificationWithoutReplayingHistory() {
        state.update(fix())
        assertNotNull(state.latestNmea())
        nowNanos += 4_000_000_000L
        assertEquals(NmeaLocationEncoder.unavailable(), state.latestNmea())
        assertNull(state.latestNmea())

        state.update(fix(latitude = 22.0))
        assertEquals("2200.0000", gga(state.latestNmea())[2])
        assertNull(state.latestNmea())
    }

    @Test
    fun removingNewestProviderCannotFallBackToAnOlderRoadPosition() {
        state.update(fix("network", ageMillis = 500, latitude = 20.0))
        state.update(fix("gps", latitude = 21.0))
        assertEquals("2100.0000", gga(state.latestNmea())[2])
        state.remove("gps")

        assertEquals(NmeaLocationEncoder.unavailable(), state.latestNmea())
        assertNull(state.latestNmea())
        nowNanos += 500_000_000L
        state.update(fix("network", latitude = 22.0))
        assertEquals("2200.0000", gga(state.latestNmea())[2])
    }

    @Test
    fun mutableCallbackObjectCannotChangeAnAcceptedSample() {
        val location = fix(latitude = 21.0)
        state.update(location)
        location.latitude = 22.0
        location.elapsedRealtimeNanos += 1_000_000_000L

        assertEquals("2100.0000", gga(state.latestNmea())[2])
    }

    @Test
    fun invalidCoordinateOrMissingAccuracyDoesNotBecomeAValidNmeaFix() {
        val invalid = listOf(
            fix().apply { latitude = Double.NaN },
            fix().apply { longitude = 181.0 },
            fix().apply { removeAccuracy() },
            fix().apply { accuracy = Float.NaN },
            fix().apply { time = 0 },
        )
        for (location in invalid) {
            state.update(location)
            assertNull(state.latestNmea())
        }
    }

    @Test
    fun newerValidSampleIsNotStarvedByAPreviouslyReportedAccurateSample() {
        state.update(fix("gps").apply { accuracy = 1f })
        assertNotNull(state.latestNmea())
        nowNanos += 500_000_000L
        state.update(fix("network", latitude = 22.0).apply { accuracy = 15f })

        assertEquals("2200.0000", gga(state.latestNmea())[2])
    }

    @Test
    fun utcMillisecondsSurviveHalfSecondSamples() {
        state.update(fix().apply { time = 1_799_136_000_125L })
        assertEquals("080000.125", gga(state.latestNmea())[1])
        nowNanos += 500_000_000L
        state.update(fix().apply { time = 1_799_136_000_625L })

        assertEquals("080000.625", gga(state.latestNmea())[1])
    }

    @Test
    fun stopAndSeparateInstancesDoNotShareDuplicateOrValidityState() {
        val location = fix()
        state.update(location)
        assertNotNull(state.latestNmea())
        state.clear()
        assertNull(state.latestNmea())
        state.update(location)
        assertNotNull(state.latestNmea())

        val other = FreshLocationState(3_000) { nowNanos }
        other.update(location)
        assertNotNull(other.latestNmea())
    }

    private fun fix(
        provider: String = "gps",
        ageMillis: Long = 0,
        latitude: Double = 21.0,
    ) = Location(provider).apply {
        this.latitude = latitude
        longitude = 114.0
        accuracy = 5f
        time = 1_799_136_000_125L - ageMillis
        elapsedRealtimeNanos = nowNanos - ageMillis * 1_000_000L
    }

    private fun gga(nmea: String?): List<String> {
        assertNotNull(nmea)
        val fields = nmea!!.substringBefore("\r\n").substringBefore('*').split(',')
        assertEquals("\$GPGGA", fields[0])
        assertTrue(fields.size >= 15)
        assertEquals("1", fields[6])
        return fields
    }
}
