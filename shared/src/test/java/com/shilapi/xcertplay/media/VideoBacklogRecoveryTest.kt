package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VideoBacklogRecoveryTest {
    private fun VideoBacklogRecovery.sample(now: Long, age: Long, span: Long, frames: Int = 3): Boolean =
        observe(now * MS, (now - age) * MS, frames, (now - age + span) * MS)

    @Test fun stableDelayedBacklogDoesNotRecoverAfterGrace() {
        val recovery = VideoBacklogRecovery()
        for (now in listOf(0L, 750L, 1500L, 3000L)) {
            assertFalse(recovery.sample(now, age = 400, span = 200))
        }
    }

    @Test fun aLargeConstantLiveBacklogCannotLeaveNavigationPermanentlyBehind() {
        val recovery = VideoBacklogRecovery()
        assertFalse(recovery.sample(1_000, age = 650, span = 500, frames = 15))
        assertFalse(recovery.sample(2_499, age = 650, span = 500, frames = 15))
        assertTrue(recovery.sample(2_500, age = 650, span = 500, frames = 15))
        assertFalse("recovery starts a new observation", recovery.sample(2_501, 650, 500, 15))
    }

    @Test fun delayedBurstsThatKeepCatchingUpDoNotResetTheDecoder() {
        val recovery = VideoBacklogRecovery()
        assertFalse(recovery.sample(1_000, age = 900, span = 800, frames = 24))
        assertFalse(recovery.sample(2_000, age = 800, span = 700, frames = 21))
        assertFalse(recovery.sample(3_000, age = 700, span = 600, frames = 18))
        assertFalse(recovery.sample(4_000, age = 600, span = 500, frames = 15))
        assertFalse(recovery.sample(5_000, age = 400, span = 300, frames = 9))
        assertFalse(recovery.sample(6_000, age = 100, span = 50, frames = 1))
    }

    @Test fun aFreshFrameOrStaticTailClearsPersistentLagObservation() {
        for (staticTail in listOf(false, true)) {
            val recovery = VideoBacklogRecovery()
            assertFalse(recovery.sample(1_000, 600, 400, 12))
            if (staticTail) {
                assertFalse(recovery.observe(2_000 * MS, 1_400 * MS, 0, null))
            } else {
                assertFalse(recovery.sample(2_000, 200, 100, 3))
            }
            assertFalse(recovery.sample(2_100, 600, 400, 12))
            assertFalse(recovery.sample(3_599, 600, 400, 12))
            assertTrue(recovery.sample(3_600, 600, 400, 12))
        }
    }

    @Test fun persistentRecoveryRequiresSubstantialAgeAndNewerFrameSpan() {
        for ((age, span) in listOf(499L to 300L, 600L to 249L)) {
            val recovery = VideoBacklogRecovery()
            assertFalse(recovery.sample(1_000, age, span))
            assertFalse(recovery.sample(3_000, age, span))
        }
        val recovery = VideoBacklogRecovery()
        assertFalse(recovery.sample(1_000, 500, 250))
        assertFalse(recovery.sample(2_499, 500, 250))
        assertTrue(recovery.sample(2_500, 500, 250))
    }

    @Test fun spanGrowthRequiresTheWholeGraceAndClearsTheEpisode() {
        val recovery = VideoBacklogRecovery()
        assertFalse(recovery.sample(0, age = 400, span = 200))
        assertFalse(recovery.sample(749, age = 400, span = 300))
        assertTrue(recovery.sample(750, age = 400, span = 300))
        assertFalse(recovery.sample(751, age = 400, span = 400))
        assertFalse(recovery.sample(1500, age = 600, span = 500))
        assertTrue(recovery.sample(1501, age = 600, span = 500))
    }

    @Test fun frameGrowthCanRecoverWithoutSpanGrowth() {
        val recovery = VideoBacklogRecovery()
        assertFalse(recovery.sample(0, age = 400, span = 200, frames = 3))
        assertFalse(recovery.sample(750, age = 400, span = 200, frames = 6))
        assertTrue(recovery.sample(751, age = 400, span = 200, frames = 7))
    }

    @Test fun staticTailNeverRecoversAndClearsPreviousBacklog() {
        val recovery = VideoBacklogRecovery()
        assertFalse(recovery.sample(0, age = 400, span = 200))
        assertFalse(recovery.observe(10_000 * MS, 0, 0, null))
        assertFalse(recovery.sample(10_001, age = 400, span = 400))
        assertFalse(recovery.sample(10_750, age = 600, span = 500))
    }

    @Test fun lowThresholdsResetButTheHysteresisBandPreservesObservation() {
        val recovery = VideoBacklogRecovery()
        assertFalse(recovery.sample(0, age = 400, span = 200))
        assertFalse(recovery.sample(500, age = 200, span = 80))
        assertTrue(recovery.sample(750, age = 400, span = 300))
        for ((age, span) in listOf(150L to 100L, 400L to 50L)) {
            recovery.reset()
            assertFalse(recovery.sample(0, age = 400, span = 200))
            assertFalse(recovery.sample(500, age, span))
            assertFalse(recovery.sample(750, age = 400, span = 300))
            assertFalse(recovery.sample(1499, age = 600, span = 400))
            assertTrue(recovery.sample(1500, age = 600, span = 400))
        }
    }

    @Test fun softAgeAndSpanBoundariesMustBothStartObservation() {
        val recovery = VideoBacklogRecovery()
        assertFalse(recovery.sample(0, age = 250, span = 100))
        assertFalse(recovery.sample(750, age = 251, span = 99))
        assertFalse(recovery.sample(751, age = 251, span = 100))
        assertFalse(recovery.sample(1500, age = 251, span = 200))
        assertTrue(recovery.sample(1501, age = 251, span = 200))
    }

    @Test fun hardAgeIsBoundedButNeedsNewerQueuedFrames() {
        val recovery = VideoBacklogRecovery()
        assertFalse(recovery.sample(0, age = 1499, span = 100))
        assertTrue(recovery.sample(1, age = 1500, span = 100))
        assertFalse(recovery.sample(2, age = 2000, span = 99))
        assertFalse(recovery.observe(2000 * MS, 0, 0, null))
    }

    @Test fun invalidOrFutureTimestampsDoNotInventBacklog() {
        val recovery = VideoBacklogRecovery()
        val invalid = listOf(
            Triple(501L, 1, 501L),
            Triple(100L, 1, 501L),
            Triple(100L, 1, 99L),
            Triple(100L, 1, null),
            Triple(100L, -1, 200L),
        )
        for ((current, frames, newest) in invalid) {
            assertFalse(recovery.sample(0, age = 400, span = 200))
            assertFalse(recovery.observe(500 * MS, current * MS, frames, newest?.times(MS)))
            assertFalse(recovery.sample(750, age = 400, span = 300))
            recovery.reset()
        }
        assertFalse(recovery.sample(1000, age = 400, span = 200))
        assertFalse(recovery.sample(999, age = 400, span = 300))
        assertFalse(recovery.sample(1750, age = 500, span = 400))
    }

    private companion object { const val MS = 1_000_000L }
}
