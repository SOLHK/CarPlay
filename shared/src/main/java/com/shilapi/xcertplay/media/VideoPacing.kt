package com.shilapi.xcertplay.media

/**
 * Receiver-side presentation scheduling. USB frames share one 100 ms software pacing budget from
 * arrival, and wireless frames one 200 ms budget, including clock mapping and smoothing. Transport
 * and hardware decode time are not bounded
 * by this policy. An overdue frame is shown immediately by the decoder, without breaking its chain.
 */
object VideoPacing {
    const val WIRED_MAX_MILLIS = 100
    const val WIRELESS_MAX_MILLIS = 200

    fun initialDelayMillis(fps: Int, wiredTransport: Boolean): Int {
        val delay = 40 + 3_000 / fps.coerceIn(30, 60)
        return if (wiredTransport) minOf(delay, WIRED_MAX_MILLIS) else delay
    }

    internal fun maximumDelayNanos(wiredTransport: Boolean): Long =
        (if (wiredTransport) WIRED_MAX_MILLIS else WIRELESS_MAX_MILLIS) * 1_000_000L

    internal fun presentationTimeNanos(
        localNanos: Long,
        delayNanos: Long,
        arrivalNanos: Long,
        wiredTransport: Boolean,
    ): Long {
        if (arrivalNanos <= 0L) return 0L
        val target = localNanos + delayNanos
        // FramePacer can map a frame up to 100 ms into the future. That clock correction and the
        // adaptive delay must fit together on both transports. Otherwise wireless clock correction
        // silently adds up to another 100 ms after its adaptive delay has reached the 200 ms limit.
        return minOf(target, arrivalNanos + maximumDelayNanos(wiredTransport))
    }
}
