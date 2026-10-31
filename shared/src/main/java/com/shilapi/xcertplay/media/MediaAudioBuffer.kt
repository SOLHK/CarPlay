package com.shilapi.xcertplay.media

/**
 * Jitter buffer for streams mapped to the media channel. Wireless CarPlay delivers audio over the same Wi-Fi link as
 * video; radio gaps of several hundred milliseconds are normal, so music needs a buffer that
 * outlasts them. Calls, Siri and navigation prompts keep the small low-latency buffer.
 * Wireless and wired USB keep separate settings. Larger wireless presets outlast Wi-Fi gaps;
 * the 150 ms preset trades that protection for a shorter response time.
 * Wired USB has no radio gaps, so it also offers short presets that let music start and pause sooner.
 * The device minimum AudioTrack size can still make the real buffer longer than a short preset.
 */
object MediaAudioBuffer {
    const val DEFAULT_MILLIS = 300
    const val MOST_STABLE_MILLIS = 1000
    const val WIRED_DEFAULT_MILLIS = 100
    const val WIRELESS_MIN_MILLIS = 150
    const val WIRELESS_DEFAULT_MILLIS = WIRELESS_MIN_MILLIS
    const val TRACK_SWITCH_MILLIS = 150
    val wirelessPresets = listOf(WIRELESS_MIN_MILLIS, DEFAULT_MILLIS, 500, MOST_STABLE_MILLIS)
    val wiredPresets = listOf(50, 100, 150, 200) + (DEFAULT_MILLIS..MOST_STABLE_MILLIS step 100)
    /** Every delay a session can use; [plan] accepts any of them. */
    val presets = (wiredPresets + wirelessPresets).distinct().sorted()

    private const val HEADROOM_MILLIS = 200 // room above the start level so bursts after a gap fit
    private const val MIN_TRACK_BUFFER_BYTES = 16 * 1024
    private const val MIN_START_BUFFER_BYTES = 4 * 1024
    private const val LOW_LATENCY_HEADROOM_MILLIS = 20
    private const val VOICE_BUFFER_MILLIS = 100

    fun sanitize(millis: Int): Int = millis.takeIf { it in presets } ?: DEFAULT_MILLIS
    fun sanitizeWireless(millis: Int): Int = millis.takeIf { it in wirelessPresets } ?: WIRELESS_DEFAULT_MILLIS
    fun sanitizeWired(millis: Int): Int = millis.takeIf { it in wiredPresets } ?: WIRED_DEFAULT_MILLIS

    /** Enhanced buffering already keeps its jitter reserve in the TCP stream, outside AudioTrack. */
    fun bufferedStartMillis(wired: Boolean): Int = if (wired) WIRED_DEFAULT_MILLIS else TRACK_SWITCH_MILLIS

    data class Plan(val trackBufferBytes: Int, val startBytes: Int)

    /** AudioTrack capacity and the amount to queue before play() for one output stream. */
    fun plan(isMedia: Boolean, sampleRate: Int, channels: Int, minBufferBytes: Int, mediaMillis: Int,
        wired: Boolean = false): Plan {
        val bytesPerSecond = sampleRate.toLong() * channels.coerceIn(1, 2) * 2
        // Byte-count floors become hundreds of milliseconds for mono navigation and calls:
        // 16 KiB is over a second at 8 kHz. Speech needs a time budget on Wi-Fi as well as USB.
        // Keep the platform minimum, which can still exceed the requested software budget.
        if (!isMedia) {
            val budgetBytes = (bytesPerSecond * VOICE_BUFFER_MILLIS / 1000).toInt()
            // At high sample rates the old wireless buffer can already be shorter than 100 ms.
            // Keep that smaller capacity rather than turning this latency fix into a regression.
            val capacityBytes = if (wired) budgetBytes
                else minOf(budgetBytes, maxOf(minBufferBytes * 4, MIN_TRACK_BUFFER_BYTES))
            return Plan(
                trackBufferBytes = maxOf(minBufferBytes, capacityBytes),
                startBytes = maxOf(minBufferBytes, (bytesPerSecond * LOW_LATENCY_HEADROOM_MILLIS / 1000).toInt()),
            )
        }
        val lowLatency = Plan(
            trackBufferBytes = maxOf(minBufferBytes * 4, MIN_TRACK_BUFFER_BYTES),
            startBytes = maxOf(minBufferBytes, MIN_START_BUFFER_BYTES),
        )
        val millis = sanitize(mediaMillis)
        val start = (bytesPerSecond * millis / 1000).toInt()
        val shortBuffer = millis <= TRACK_SWITCH_MILLIS
        val headroom = if (shortBuffer) LOW_LATENCY_HEADROOM_MILLIS else HEADROOM_MILLIS
        val capacity = (bytesPerSecond * (millis + headroom) / 1000).toInt()
        return Plan(
            trackBufferBytes = maxOf(capacity, if (shortBuffer) minBufferBytes else lowLatency.trackBufferBytes),
            startBytes = maxOf(start, if (shortBuffer) minBufferBytes else lowLatency.startBytes),
        )
    }

    /**
     * The device may grant a smaller AudioTrack than requested. Writes block while the track is
     * paused and full, so the start level must stay below the real capacity or play() never runs.
     */
    fun startBytesFor(plannedStartBytes: Int, actualCapacityBytes: Int, writeChunkBytes: Int): Int {
        if (actualCapacityBytes <= 0) return plannedStartBytes
        val chunk = minOf(writeChunkBytes, plannedStartBytes, actualCapacityBytes).coerceAtLeast(1)
        return minOf(plannedStartBytes, actualCapacityBytes - chunk).coerceAtLeast(chunk)
    }

    /** A partial music burst must not bypass a long start buffer during a radio stall. */
    fun tailWaitNanos(isMedia: Boolean, startBytes: Int, bytesPerSecond: Int): Long {
        val startNanos = if (isMedia && bytesPerSecond > 0) {
            startBytes.toLong() * 1_000_000_000L / bytesPerSecond
        } else 0L
        // A short final burst in a low-latency session must not inherit a hidden half-second wait.
        // Long wireless stability presets still wait long enough to span the known radio gaps.
        return if (startNanos <= TRACK_SWITCH_MILLIS * 1_000_000L) {
            maxOf(WIRED_DEFAULT_MILLIS * 1_000_000L, startNanos)
        } else maxOf(500_000_000L, startNanos)
    }
}
