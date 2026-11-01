package com.shilapi.xcertplay.media

/** Retry a full nonblocking track without a 500 Hz busy poll. No PCM or latency budget changes. */
internal object AudioWriteBackoff {
    fun delayMillis(consecutiveZeros: Int, queuedBytes: Long, bytesPerSecond: Int, playing: Boolean): Long {
        // Startup and an empty/unknown hardware queue must retain the original prompt retry.
        if (!playing || queuedBytes <= 0 || bytesPerSecond <= 0) return 2L
        val reserveMillis = queuedBytes.coerceAtMost(Int.MAX_VALUE.toLong()) * 1000L / bytesPerSecond
        val ceiling = (reserveMillis / 4L).coerceIn(1L, 8L)
        val ramp = when {
            consecutiveZeros <= 1 -> 2L
            consecutiveZeros == 2 -> 4L
            else -> 8L
        }
        return minOf(ramp, ceiling)
    }
}
