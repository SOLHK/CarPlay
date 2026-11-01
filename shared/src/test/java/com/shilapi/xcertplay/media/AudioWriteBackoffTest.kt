package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class AudioWriteBackoffTest {
    @Test fun aFullPlayingTrackBacksOffButNeverExceedsEightMillis() {
        assertEquals(listOf(2L, 4L, 8L, 8L), (1..4).map { AudioWriteBackoff.delayMillis(it, 19_200, 192_000, true) })
    }
    @Test fun startupAndEmptyTrackKeepTheOriginalPromptRetry() {
        assertEquals(2L, AudioWriteBackoff.delayMillis(20, 19_200, 192_000, false))
        assertEquals(2L, AudioWriteBackoff.delayMillis(20, 0, 192_000, true))
        assertEquals(2L, AudioWriteBackoff.delayMillis(20, 19_200, 0, true))
    }
    @Test fun lowReserveCannotBeSleptThrough() {
        assertEquals(4L, AudioWriteBackoff.delayMillis(20, 3072, 192_000, true))
        assertEquals(1L, AudioWriteBackoff.delayMillis(20, 768, 192_000, true))
    }
    @Test fun invalidOrVeryLargeInputsStayBounded() {
        for (bytes in listOf(-1L, 1L, Long.MAX_VALUE)) {
            assertTrue(AudioWriteBackoff.delayMillis(Int.MAX_VALUE, bytes, 1, true) in 1L..8L)
        }
    }
    @Test fun repeatedFullTrackPollsAreReducedWithoutChangingBufferPresets() {
        var elapsed = 0L
        var calls = 0
        while (elapsed < 1000L) {
            calls++
            elapsed += AudioWriteBackoff.delayMillis(calls, 19_200, 192_000, true)
        }
        assertTrue("Full-track retries should be well below the old 500/s polling", calls < 150)
        assertEquals(150, MediaAudioBuffer.WIRELESS_DEFAULT_MILLIS)
        assertEquals(100, MediaAudioBuffer.WIRED_DEFAULT_MILLIS)
        assertEquals(150, MediaAudioBuffer.bufferedStartMillis(false))
        assertEquals(100, MediaAudioBuffer.bufferedStartMillis(true))
    }
}
