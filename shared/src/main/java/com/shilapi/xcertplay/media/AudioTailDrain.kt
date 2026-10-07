package com.shilapi.xcertplay.media

/** Bounded normal completion of a navigation prompt, including asynchronous decoder output. */
internal object AudioTailDrain {
    const val TIMEOUT_NS = 1_200_000_000L
    private const val OUTPUT_GRACE_NS = 100_000_000L

    fun complete(nowNs: Long, deadlineNs: Long, lastPcmWriteNs: Long,
        inputEmpty: Boolean, queuedBytes: Long): Boolean {
        if (deadlineNs == 0L) return false
        if (nowNs >= deadlineNs) return true
        val lastActivity = maxOf(lastPcmWriteNs, deadlineNs - TIMEOUT_NS)
        return inputEmpty && queuedBytes == 0L && nowNs - lastActivity >= OUTPUT_GRACE_NS
    }
}
