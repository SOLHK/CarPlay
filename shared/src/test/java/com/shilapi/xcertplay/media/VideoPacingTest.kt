package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPacingTest {
    private val ms = 1_000_000L
    private val clockOffset = 5_000_000_000L

    @Test fun wiredWarmupFits100MillisAtBothFrameRates() {
        for (fps in listOf(30, 60)) {
            val initial = VideoPacing.initialDelayMillis(fps, wiredTransport = true)
            assertEquals(if (fps == 30) 100 else 90, initial)
            val delay = PacingDelay(initial * ms, maxNanos = VideoPacing.maximumDelayNanos(true))
            val pacer = FramePacer()
            repeat(14) { index ->
                val sender = index * (1_000_000_000L / fps)
                val arrival = sender + clockOffset
                val local = pacer.localTime(sender, arrival)
                delay.onFrame(400 * ms)
                val target = VideoPacing.presentationTimeNanos(local, delay.nanos, arrival, true)
                assertEquals(initial * ms, delay.nanos)
                assertTrue(target - arrival <= 100 * ms)
            }
        }
    }

    @Test fun wiredClockCorrectionAndAdaptiveDelayShareOne100MillisBudget() {
        for (fps in listOf(30, 60)) {
            val interval = 1_000_000_000L / fps
            val delay = PacingDelay(VideoPacing.initialDelayMillis(fps, true) * ms,
                maxNanos = VideoPacing.maximumDelayNanos(true))
            val pacer = FramePacer()
            var sawCombinedDelayOverBudget = false
            repeat(700) { index ->
                // A small forward iPhone clock step keeps receiver arrivals monotonic, but the
                // established clock mapping temporarily places frames ahead of their arrival.
                val sender = index * interval + if (index >= 200) 80 * ms else 0L
                val arrival = index * interval + clockOffset
                val local = pacer.localTime(sender, arrival)
                delay.onFrame((80 + index % 170) * ms)
                assertTrue("adaptive delay at $fps fps", delay.nanos <= 100 * ms)
                if (local > 0L) {
                    val unbounded = local + delay.nanos
                    if (unbounded - arrival > 100 * ms) sawCombinedDelayOverBudget = true
                    val target = VideoPacing.presentationTimeNanos(local, delay.nanos, arrival, true)
                    assertTrue("frame $index at $fps fps held ${(target - arrival) / ms} ms",
                        target - arrival <= 100 * ms)
                    // Already slow decoding consumes the budget; it cannot earn another 100 ms.
                    val decodedAt = arrival + 140 * ms
                    assertTrue(target <= decodedAt)
                }
            }
            assertTrue("the regression must exercise positive clock offset plus smoothing",
                sawCombinedDelayOverBudget)
            assertEquals(100 * ms, delay.nanos)
        }
    }

    @Test fun wiredDecoderStartsWithinBudgetEvenIfCallerSuppliesOld140Millis() {
        val delay = PacingDelay(140 * ms, maxNanos = VideoPacing.maximumDelayNanos(true))
        assertEquals(100 * ms, delay.nanos)
    }

    @Test fun wirelessKeepsItsStartingDelayAnd200MillisAdaptiveLimit() {
        assertEquals(140, VideoPacing.initialDelayMillis(30, false))
        assertEquals(90, VideoPacing.initialDelayMillis(60, false))
        val delay = PacingDelay(140 * ms, maxNanos = VideoPacing.maximumDelayNanos(false))
        repeat(1_000) { delay.onFrame(400 * ms) }
        assertEquals(200 * ms, delay.nanos)
        val arrival = clockOffset
        val local = arrival + 80 * ms
        assertEquals(local + 200 * ms,
            VideoPacing.presentationTimeNanos(local, delay.nanos, arrival, false))
    }

    @Test fun missingWiredArrivalDoesNotScheduleAnUnboundedFutureFrame() {
        assertEquals(0L, VideoPacing.presentationTimeNanos(clockOffset, 100 * ms, 0L, true))
    }
}
