package com.shilapi.xcertplay.media

/** Recover growing or persistently old live input, while allowing bursts and static tails to finish. */
internal class VideoBacklogRecovery {
    private var episodeStartNs: Long? = null
    private var initialSpanNs = 0L
    private var initialPendingFrames = 0
    private var lastObservedNs: Long? = null
    private var persistentStartNs: Long? = null
    private var persistentSpanNs = 0L

    fun reset() {
        episodeStartNs = null
        initialSpanNs = 0L
        initialPendingFrames = 0
        lastObservedNs = null
        persistentStartNs = null
        persistentSpanNs = 0L
    }

    fun observe(nowNs: Long, currentReceivedNs: Long, pendingFrames: Int,
        newestPendingReceivedNs: Long?): Boolean {
        if (pendingFrames <= 0 || newestPendingReceivedNs == null ||
            currentReceivedNs > nowNs || newestPendingReceivedNs > nowNs ||
            newestPendingReceivedNs < currentReceivedNs ||
            lastObservedNs?.let { nowNs < it } == true) {
            reset()
            return false
        }
        lastObservedNs = nowNs
        val ageNs = nowNs - currentReceivedNs
        val spanNs = newestPendingReceivedNs - currentReceivedNs
        if (ageNs <= LOW_AGE_NS || spanNs <= LOW_SPAN_NS) {
            reset()
            return false
        }
        if (ageNs >= HARD_AGE_NS && spanNs >= SOFT_SPAN_NS) {
            reset()
            return true
        }
        // After a stall, decoding at exactly the arrival rate leaves a fixed queue forever: it is
        // not growing, but navigation still displays old pictures. Give a substantial live backlog
        // time to drain, restarting that grace whenever it makes meaningful catch-up progress.
        // A final/static frame and a short burst must never request an unnecessary new keyframe.
        if (ageNs >= PERSISTENT_AGE_NS && spanNs >= PERSISTENT_SPAN_NS) {
            val persistentStart = persistentStartNs
            if (persistentStart == null || persistentSpanNs - spanNs >= CATCH_UP_PROGRESS_NS) {
                persistentStartNs = nowNs
                persistentSpanNs = spanNs
            } else if (nowNs - persistentStart >= PERSISTENT_GRACE_NS) {
                reset()
                return true
            }
        } else {
            persistentStartNs = null
            persistentSpanNs = 0L
        }
        if (ageNs <= SOFT_AGE_NS || spanNs < SOFT_SPAN_NS) return false
        val start = episodeStartNs
        if (start == null) {
            episodeStartNs = nowNs
            initialSpanNs = spanNs
            initialPendingFrames = pendingFrames
            return false
        }
        val growing = spanNs - initialSpanNs >= SPAN_GROWTH_NS ||
            pendingFrames - initialPendingFrames >= FRAME_GROWTH
        if (nowNs - start >= GRACE_NS && growing) {
            reset()
            return true
        }
        return false
    }

    private companion object {
        const val SOFT_AGE_NS = 250_000_000L
        const val SOFT_SPAN_NS = 100_000_000L
        const val LOW_AGE_NS = 150_000_000L
        const val LOW_SPAN_NS = 50_000_000L
        const val HARD_AGE_NS = 1_500_000_000L
        const val GRACE_NS = 750_000_000L
        const val SPAN_GROWTH_NS = 100_000_000L
        const val FRAME_GROWTH = 4
        const val PERSISTENT_AGE_NS = 500_000_000L
        const val PERSISTENT_SPAN_NS = 250_000_000L
        const val PERSISTENT_GRACE_NS = 1_500_000_000L
        const val CATCH_UP_PROGRESS_NS = 50_000_000L
    }
}
