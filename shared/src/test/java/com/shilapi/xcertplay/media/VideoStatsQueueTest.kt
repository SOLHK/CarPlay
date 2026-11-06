package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VideoStatsQueueTest {
    @Test fun oldFramesFinishingInAReceiveIdleWindowAreReportedWithoutLeakingIntoTheNextWindow() {
        var now = 1_000_000_000L
        val stats = VideoStats(label = " test", nanoTime = { now })
        stats.onReceived(100)
        now += 5_000_000_000L
        assertNotNull(stats.logIfDue())

        stats.onQueueLatency(650_000_000L)
        stats.onDecodeLatency(10_000_000L)
        stats.onRendered()
        now += 5_000_000_000L
        val draining = requireNotNull(stats.logIfDue())
        assertTrue(draining, draining.contains("queueMax=650ms"))

        stats.onReceived(100)
        stats.onQueueLatency(2_000_000L)
        now += 5_000_000_000L
        val fresh = requireNotNull(stats.logIfDue())
        assertTrue(fresh, fresh.contains("queueMax=2ms"))
        assertFalse(fresh, fresh.contains("queueMax=650ms"))
    }

    @Test fun failedInputAndRecoveryRemainVisibleEvenWithoutAnotherReceivedFrame() {
        var now = 1_000_000_000L
        val stats = VideoStats(label = " test", nanoTime = { now })
        stats.onQueueLatency(700_000_000L)
        stats.onRecovery()
        now += 5_000_000_000L
        val line = requireNotNull(stats.logIfDue())
        assertTrue(line, line.contains("recoveries=1"))
        assertTrue(line, line.contains("queueMax=700ms"))
        now += 5_000_000_000L
        assertNull("a completely idle window stays quiet", stats.logIfDue())
    }
}
