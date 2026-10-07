package com.shilapi.xcertplay.media

/** Tracks the unsigned AudioTrack head across wrap without flushing already queued sound. */
internal class AudioBufferProgress(private val frameBytes: Int) {
    private var writtenBytes = 0L
    private var playedFrames = 0L
    private var lastHead = 0L

    fun written(bytes: Int) { writtenBytes += bytes }

    fun queuedBytes(rawHead: Int): Long {
        val head = rawHead.toLong() and 0xffff_ffffL
        playedFrames += (head - lastHead) and 0xffff_ffffL
        lastHead = head
        return (writtenBytes - playedFrames * frameBytes).coerceAtLeast(0)
    }

    // Compressed packets cannot keep AudioTrack fed until decoded. A returning burst must
    // not bypass prebuffering merely because it is already waiting in the input queue.
    fun shouldRebuffer(isMedia: Boolean, playing: Boolean, underrunSinceStart: Boolean,
        rawHead: Int): Boolean =
        isMedia && playing && underrunSinceStart && queuedBytes(rawHead) == 0L
}
