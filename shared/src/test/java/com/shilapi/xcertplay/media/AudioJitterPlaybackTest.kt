package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

/** Deterministic PCM delivery model, not a claim about Android's mixer or radio. */
class AudioJitterPlaybackTest {
    private fun starvationMillis(plan: MediaAudioBuffer.Plan, frameBytes: Int,
        burstMillis: Int, delayedBurstMillis: Int = 0): Int {
        val bytesPerMs = 48 * frameBytes
        val capacity = plan.trackBufferBytes / bytesPerMs
        val threshold = plan.startBytes / bytesPerMs
        var pending = 0
        var hardware = 0
        var playing = false
        var starved = 0
        repeat(10_000) { time ->
            val cycle = time % (burstMillis * 3)
            if (cycle == 0 || cycle == burstMillis ||
                cycle == burstMillis * 2 + delayedBurstMillis) pending += burstMillis
            val written = minOf(pending, capacity - hardware)
            pending -= written
            hardware += written
            if (hardware >= threshold) playing = true
            if (playing) {
                if (hardware > 0) hardware-- else starved++
            }
        }
        return starved
    }

    @Test fun default100msDoesNotClaimToHide600msDeliveryGaps() {
        val plan = MediaAudioBuffer.plan(true, 48_000, 2, 15_376, MediaAudioBuffer.DEFAULT_MILLIS)
        assertEquals(19_200, plan.startBytes)
        assertTrue(starvationMillis(plan, 4, 400, 200) > 0)
    }

    @Test fun largerMusicPresetCoversLonger600msBursts() {
        val fixed = MediaAudioBuffer.plan(true, 48_000, 2, 15_376, 1000)
        assertEquals(0, starvationMillis(fixed, 4, 600))
    }

    @Test fun repeatedUnderrunRecoveryPreservesEveryPcmFrame() {
        val progress = AudioBufferProgress(4)
        var head = 0
        repeat(200) {
            progress.written(19_200)
            assertFalse(progress.shouldRebuffer(true, true, true, head))
            head += 4_800
            // Incoming compressed packets may already be waiting; hardware is still empty.
            assertTrue(progress.shouldRebuffer(true, true, true, head))
            assertFalse(progress.shouldRebuffer(true, false, true, head))
            progress.written(19_200)
            assertEquals(19_200L, progress.queuedBytes(head))
            assertFalse(progress.shouldRebuffer(true, true, true, head))
            head += 4_800
        }
        assertEquals(0L, progress.queuedBytes(head))
    }
}
