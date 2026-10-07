package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class AudioTailDrainTest {
    private val start = 10_000_000_000L
    private val deadline = start + AudioTailDrain.TIMEOUT_NS

    @Test fun waitsForFirstAsynchronousOutputAfterTeardown() {
        assertFalse(AudioTailDrain.complete(start + 10_000_000L, deadline, 0, true, 0))
        assertTrue(AudioTailDrain.complete(start + 100_000_000L, deadline, 0, true, 0))
    }

    @Test fun waitsForBothCompressedAndPcmTails() {
        assertFalse(AudioTailDrain.complete(start + 500_000_000L, deadline, start, false, 0))
        assertFalse(AudioTailDrain.complete(start + 500_000_000L, deadline, start, true, 960))
        assertTrue(AudioTailDrain.complete(start + 500_000_000L, deadline, start, true, 0))
    }

    @Test fun lateDecoderOutputRestartsGracePeriod() {
        val last = start + 400_000_000L
        assertFalse(AudioTailDrain.complete(last + 50_000_000L, deadline, last, true, 0))
        assertTrue(AudioTailDrain.complete(last + 100_000_000L, deadline, last, true, 0))
    }

    @Test fun brokenVendorTrackCannotHoldWorkerForever() {
        assertTrue(AudioTailDrain.complete(deadline, deadline, start, false, 100_000))
        assertFalse(AudioTailDrain.complete(deadline, 0, start, true, 0))
    }
}
