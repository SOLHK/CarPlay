package com.shilapi.xcertplay.media

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Release a window resource only after every decoder has acknowledged detachment. */
class VideoReleaseBarrier(count: Int, private val onReleased: () -> Unit = {}) {
    private val remaining = AtomicInteger(count)
    private val latch = CountDownLatch(if (count == 0) 0 else 1)
    init {
        require(count >= 0)
        if (count == 0) onReleased()
    }

    /** A worker may finish its detach and then exit; each acknowledgement counts once. */
    fun acknowledgement(): () -> Unit {
        val completed = AtomicBoolean()
        return {
            if (completed.compareAndSet(false, true) && remaining.decrementAndGet() == 0) {
                try { onReleased() } finally { latch.countDown() }
            }
        }
    }

    fun await(timeoutMillis: Long): Boolean = try {
        latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
}
