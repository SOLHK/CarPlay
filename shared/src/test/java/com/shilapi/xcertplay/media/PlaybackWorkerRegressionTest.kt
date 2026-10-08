package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PlaybackWorkerRegressionTest {
    @Test fun pcmCopyPreservesSignedSamplesAndCanReuseTheSameBuffer() {
        val packet = ByteArray(12) + byteArrayOf(0x80.toByte(), 0, 0x7f, -1, 0, 1)
        val original = packet.copyOf()
        val output = ByteArray(32)
        assertEquals(6, Pcm16Payload.copy(packet, 12, output))
        assertArrayEquals(byteArrayOf(0, 0x80.toByte(), -1, 0x7f, 1, 0), output.copyOf(6))
        assertArrayEquals(original, packet)
        assertEquals(2, Pcm16Payload.copy(ByteArray(12) + byteArrayOf(0x12, 0x34, 0x56), 12, output))
        assertEquals(0x34.toByte(), output[0])
        assertEquals(0x12.toByte(), output[1])
        assertEquals(0, Pcm16Payload.copy(ByteArray(4), 12, output))
    }

    @Test fun blockedAudioDecoderStopsWhenTheNavigationTailExpires() {
        var time = 0
        var calls = 0
        val result = AudioInputPump.acquire(
            shouldContinue = { time < 3 }, drain = { time++ }, dequeue = { calls++; -1 })
        assertEquals(-1, result)
        assertEquals(2, calls)
        // A dequeue that becomes ready during a late drain must also respect teardown.
        assertEquals(-1, AudioInputPump.acquire({ false }, { fail("drained after stop") }, { 0 }))
    }

    @Test fun audioOutputIsDrainedBeforeRetryingTheSameInput() {
        var outputPending = 2
        var calls = 0
        assertEquals(7, AudioInputPump.acquire({ true }, { outputPending-- }, {
            calls++; if (outputPending > 0) -1 else 7
        }))
        assertEquals(2, calls)
    }

    @Test fun resourceReleaseWaitsForEveryDecoderAndIgnoresDuplicateAcknowledgements() {
        val releases = AtomicInteger()
        val barrier = VideoReleaseBarrier(2) { releases.incrementAndGet() }
        val first = barrier.acknowledgement()
        val second = barrier.acknowledgement()
        first(); first()
        assertFalse(barrier.await(0))
        assertEquals(0, releases.get())
        second(); second()
        assertTrue(barrier.await(0))
        assertEquals(1, releases.get())
        assertTrue(VideoReleaseBarrier(0) { releases.incrementAndGet() }.await(0))
        assertEquals(2, releases.get())
    }

    @Test fun shutdownDrainPreservesControlsWhenTheWorkerWasInterrupted() {
        val queue = VideoDecodeQueue(maxFrames = 1)
        val config = VideoJob.Config(com.shilapi.xcertplay.airplay.VideoCodec.H264, byteArrayOf(1))
        val surface = VideoJob.SurfaceChanged(null)
        queue.offer(config); queue.offer(VideoJob.Frame(ByteArray(2))); queue.offer(surface)
        Thread.currentThread().interrupt()
        try {
            val pending = queue.drain()
            assertSame(config, pending[0])
            assertArrayEquals(ByteArray(2), (pending[1] as VideoJob.Frame).nalus)
            assertSame(surface, pending[2])
        }
        finally { Thread.interrupted() }
        assertNull(queue.poll(0))
    }
}
