package com.shilapi.xcertplay.media

import android.media.AudioTrack
import android.media.MediaCodec
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioTrack
import org.robolectric.shadows.ShadowMediaCodec

/** Exercise the real audio worker with a decoder that recovers after its input wait expires. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE,
    shadows = [AudioDecoderBackpressureTest.BackpressureCodec::class, AudioDecoderBackpressureTest.RecordingTrack::class])
class AudioDecoderBackpressureTest {
    private val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")

    @Before fun reset() {
        BackpressureCodec.firstWaitMillis = 125
        BackpressureCodec.neverReady = false
        BackpressureCodec.immediateTryAgain = false
        BackpressureCodec.attempts.set(0)
        BackpressureCodec.firstAttemptNanos = 0L
        BackpressureCodec.lastAttemptNanos = 0L
        BackpressureCodec.entered = CountDownLatch(1)
        BackpressureCodec.inputs.clear()
        BackpressureCodec.timestamps.clear()
        RecordingTrack.pcm.clear()
        RecordingTrack.written = CountDownLatch(3)
        MediaClockTrim.learnedTrim = 0.0
    }

    @Test(timeout = 10_000)
    fun usbMusicRetainsTheFirstFrameWhenTheDecoderTemporarilyHasNoInputSlot() = preservesFrames(wired = true, buffered = false)

    @Test(timeout = 10_000)
    fun wirelessMusicRetainsTheFirstFrameWhenTheDecoderTemporarilyHasNoInputSlot() = preservesFrames(wired = false, buffered = false)

    @Test(timeout = 10_000)
    fun bufferedMusicDoesNotLoseAnAcceptedTcpFrameToDownstreamBackpressure() = preservesFrames(wired = false, buffered = true)

    private fun preservesFrames(wired: Boolean, buffered: Boolean) {
        val reports = CopyOnWriteArrayList<String>()
        val renderer = renderer(wired, buffered, reports)
        val payloads = listOf(byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7, 8), byteArrayOf(9, 10, 11, 12))
        try {
            invoke(renderer, "start", 0)
            payloads.forEachIndexed { index, payload ->
                val rtp = ByteArray(12) + payload
                if (buffered) assertEquals(true, invoke(renderer, "offerBuffered", rtp, index * 1024))
                else invoke(renderer, "submit", rtp, index * 1024)
            }
            assertTrue("Decoder never received input: $reports", BackpressureCodec.entered.await(3, TimeUnit.SECONDS))
            assertTrue("One unavailable input slot lost music: $reports", RecordingTrack.written.await(3, TimeUnit.SECONDS))
            assertEquals("Every packet must enter the decoder exactly once", 3, BackpressureCodec.inputs.size)
            payloads.forEachIndexed { index, payload ->
                val input = BackpressureCodec.inputs[index]
                assertArrayEquals("AAC payload $index changed or was reordered", payload, input.copyOfRange(7, input.size))
            }
            assertEquals(listOf(0L, 21_333L, 42_666L), BackpressureCodec.timestamps.toList())
            assertArrayEquals(payloads.flatMap { it.toList() }.toByteArray(),
                RecordingTrack.pcm.flatMap { it.toList() }.toByteArray())
            assertEquals(0, field("inputDropped").get(renderer))
            assertTrue("A transient input wait must be visible in diagnostics",
                reports.any { it.contains("decoder input backpressure") && it.contains("retainingPacket=true") })
        } finally { close(renderer) }
    }

    @Test(timeout = 10_000)
    fun closeInterruptsAPermanentlyUnavailableDecoderWithoutReplayingThePendingPacket() {
        BackpressureCodec.firstWaitMillis = 0
        BackpressureCodec.neverReady = true
        val reports = CopyOnWriteArrayList<String>()
        val renderer = renderer(wired = true, buffered = false, reports)
        try {
            invoke(renderer, "start", 0)
            invoke(renderer, "submit", ByteArray(16), 0)
            assertTrue(BackpressureCodec.entered.await(3, TimeUnit.SECONDS))
            close(renderer)
            assertTrue(BackpressureCodec.inputs.isEmpty())
            assertTrue(RecordingTrack.pcm.isEmpty())
            assertEquals(0, field("inputDropped").get(renderer))
        } finally { close(renderer) }
    }

    @Test(timeout = 10_000)
    fun immediateTryAgainIsBackedOffInsideTheInputWaitWindowAndStillCancels() {
        BackpressureCodec.firstWaitMillis = 0
        BackpressureCodec.neverReady = true
        BackpressureCodec.immediateTryAgain = true
        val retried = CountDownLatch(1)
        val reports = CopyOnWriteArrayList<String>()
        val renderer = renderer(wired = true, buffered = false, reports) { message ->
            if (message.contains("decoder input backpressure")) retried.countDown()
        }
        try {
            invoke(renderer, "start", 0)
            invoke(renderer, "submit", ByteArray(16), 0)
            assertTrue("Worker did not finish its bounded input-wait window: $reports",
                retried.await(3, TimeUnit.SECONDS))
            close(renderer)
            val attempts = BackpressureCodec.attempts.get()
            val elapsedNanos = BackpressureCodec.lastAttemptNanos - BackpressureCodec.firstAttemptNanos
            // Allow first-call instrumentation and clock/scheduling rounding, but not an
            // unbounded hot loop. The old outer-window-only sleep made thousands of calls.
            val maximumAttempts = elapsedNanos / 2_000_000L + 5L
            assertTrue("Busy-looped $attempts times in ${elapsedNanos / 1_000_000L} ms",
                attempts > 1 && attempts <= maximumAttempts)
            assertTrue(BackpressureCodec.inputs.isEmpty())
            assertTrue(RecordingTrack.pcm.isEmpty())
            assertEquals(0, field("inputDropped").get(renderer))
        } finally { close(renderer) }
    }

    private fun renderer(wired: Boolean, buffered: Boolean, reports: MutableList<String>,
        onReport: (String) -> Unit = {}): Any =
        type.declaredConstructors.single { !it.isSynthetic }.apply { isAccessible = true }.newInstance(
            AudioFormat(AudioCodecKind.AAC_LC, 48_000, 2, if (buffered) 103 else 100, "media"),
            false, false, 0, 0, AudioFocusCoordinator(null, false, false),
            0, if (wired) 100 else 150, { message: String -> reports.add(message); onReport(message) }, 0L, false, null, false,
        ).also {
            field("wiredTransport").set(it, wired)
            field("fadeApplied").set(it, true)
        }

    private fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }

    private fun invoke(renderer: Any, name: String, vararg arguments: Any): Any? =
        type.declaredMethods.single { it.name == name && it.parameterCount == arguments.size }
            .apply { isAccessible = true }.invoke(renderer, *arguments)

    private fun close(renderer: Any) {
        invoke(renderer, "close")
        val worker = field("thread").get(renderer) as Thread
        worker.join(2_000)
        assertFalse("Decoder backpressure trapped a closed audio worker", worker.isAlive)
    }

    @Implements(MediaCodec::class)
    class BackpressureCodec : ShadowMediaCodec() {
        private val input = ByteBuffer.allocate(65_536)
        private val ready = ArrayDeque<ByteArray>()
        private var output = ByteArray(0)
        private var firstInput = true

        @Implementation override fun native_dequeueInputBuffer(timeoutUs: Long): Int {
            val now = System.nanoTime()
            if (attempts.incrementAndGet() == 1) firstAttemptNanos = now
            lastAttemptNanos = now
            entered.countDown()
            if (firstInput && firstWaitMillis > 0) {
                firstInput = false
                // Model a scheduler/vendor-codec stall longer than the renderer's 100 ms
                // input window. TRY_AGAIN is still temporary, not a damaged AAC frame.
                Thread.sleep(firstWaitMillis)
                return MediaCodec.INFO_TRY_AGAIN_LATER
            }
            if (neverReady) {
                if (!immediateTryAgain) Thread.sleep((timeoutUs / 1000).coerceAtLeast(1))
                return MediaCodec.INFO_TRY_AGAIN_LATER
            }
            return 0
        }

        @Implementation override fun getBuffer(input: Boolean, index: Int): ByteBuffer =
            if (input) this.input else ByteBuffer.wrap(output)

        @Implementation override fun native_queueInputBuffer(index: Int, offset: Int, size: Int,
            presentationTimeUs: Long, flags: Int) {
            val bytes = input.array().copyOfRange(offset, offset + size)
            inputs.add(bytes)
            timestamps.add(presentationTimeUs)
            // The fixture's four-byte AAC payload becomes one four-byte PCM frame. The
            // codec's capacity is under test here; actual AAC decoding remains Android's job.
            ready.addLast(bytes.copyOfRange(7, bytes.size))
        }

        @Implementation override fun native_dequeueOutputBuffer(info: MediaCodec.BufferInfo, timeoutUs: Long): Int {
            output = ready.pollFirst() ?: return MediaCodec.INFO_TRY_AGAIN_LATER
            info.set(0, output.size, 0L, 0)
            return 0
        }

        @Implementation override fun releaseOutputBuffer(index: Int, render: Boolean) = Unit

        companion object {
            var firstWaitMillis = 125L
            var neverReady = false
            var immediateTryAgain = false
            val attempts = AtomicInteger()
            var firstAttemptNanos = 0L
            var lastAttemptNanos = 0L
            lateinit var entered: CountDownLatch
            val inputs = CopyOnWriteArrayList<ByteArray>()
            val timestamps = CopyOnWriteArrayList<Long>()
        }
    }

    @Implements(AudioTrack::class)
    class RecordingTrack : ShadowAudioTrack() {
        @Implementation override fun getPlaybackHeadPosition() = 0
        @Implementation fun getUnderrunCount() = 0
        @Implementation fun write(bytes: ByteArray, offset: Int, size: Int, mode: Int): Int {
            assertEquals(AudioTrack.WRITE_NON_BLOCKING, mode)
            pcm.add(bytes.copyOfRange(offset, offset + size))
            written.countDown()
            return size
        }

        companion object {
            val pcm = CopyOnWriteArrayList<ByteArray>()
            lateinit var written: CountDownLatch
        }
    }
}
