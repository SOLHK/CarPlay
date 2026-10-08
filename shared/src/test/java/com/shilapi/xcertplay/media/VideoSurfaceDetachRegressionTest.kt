package com.shilapi.xcertplay.media

import android.media.MediaCodec
import android.view.Surface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the production decoder worker with a codec whose native stop is deliberately delayed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class VideoSurfaceDetachRegressionTest {
    private fun worker(surface: Surface, codec: MediaCodec): Any {
        val type = Class.forName("com.shilapi.xcertplay.media.VideoDecoder")
        val constructor = type.declaredConstructors.single { it.parameterCount == 9 }
        constructor.isAccessible = true
        val request: () -> Unit = {}
        val report: (String) -> Unit = {}
        val exited: (Any) -> Unit = {}
        return constructor.newInstance(110, surface, 1280, 720, false, request, report, null, exited).also {
            ReflectionHelpers.setField(it, "decoder", codec)
            `when`(codec.dequeueOutputBuffer(any(MediaCodec.BufferInfo::class.java), anyLong()))
                .thenReturn(MediaCodec.INFO_TRY_AGAIN_LATER)
        }
    }
    private fun start(worker: Any) = ReflectionHelpers.callInstanceMethod<Unit>(worker, "start")
    private fun close(worker: Any) {
        ReflectionHelpers.callInstanceMethod<Unit>(worker, "close")
        ReflectionHelpers.getField<Thread>(worker, "thread").join(3000)
        assertFalse(ReflectionHelpers.getField<Thread>(worker, "thread").isAlive)
    }
    private fun detach(worker: Any, surface: Surface, done: () -> Unit) {
        worker.javaClass.getDeclaredMethod("detachSurface", Surface::class.java, kotlin.jvm.functions.Function0::class.java)
            .apply { isAccessible = true }.invoke(worker, surface, done)
    }
    private fun stallStop(codec: MediaCodec, entered: CountDownLatch, allowed: CountDownLatch) {
        doAnswer {
            entered.countDown()
            var interrupted = false
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (allowed.count > 0 && System.nanoTime() < deadline) {
                try { allowed.await(50, TimeUnit.MILLISECONDS) }
                catch (_: InterruptedException) { interrupted = true }
            }
            if (interrupted) Thread.currentThread().interrupt()
            null
        }.`when`(codec).stop()
    }

    @Test fun textureReleaseRunsAfterNativeCodecReleaseRatherThanAfterEnqueue() {
        val surface = mock(Surface::class.java)
        val codec = mock(MediaCodec::class.java)
        val entered = CountDownLatch(1)
        val allowed = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        stallStop(codec, entered, allowed)
        doAnswer { events.add("codec released"); null }.`when`(codec).release()
        val worker = worker(surface, codec)
        val barrier = VideoReleaseBarrier(1) { events.add("texture released") }
        try {
            start(worker)
            detach(worker, surface, barrier.acknowledgement())
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(barrier.await(0))
            assertTrue(events.isEmpty())
            allowed.countDown()
            assertTrue(barrier.await(2000))
            assertEquals(listOf("codec released", "texture released"), events)
        } finally { allowed.countDown(); close(worker) }
    }

    @Test fun queuedDetachCompletesEvenWhenTheDecoderWasClosedAndInterrupted() {
        val surface = mock(Surface::class.java)
        val codec = mock(MediaCodec::class.java)
        val entered = CountDownLatch(1)
        val allowed = CountDownLatch(1)
        stallStop(codec, entered, allowed)
        val worker = worker(surface, codec)
        val barrier = VideoReleaseBarrier(1)
        try {
            start(worker)
            ReflectionHelpers.callInstanceMethod<Unit>(worker, "close")
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            detach(worker, surface, barrier.acknowledgement())
            assertFalse(barrier.await(0))
            allowed.countDown()
            assertTrue(barrier.await(2000))
            verify(codec).release()
        } finally { allowed.countDown(); close(worker) }
    }

    @Test fun destroyingTheOldOutputKeepsTheNewlyAttachedOutputAndCodec() {
        val old = mock(Surface::class.java)
        val current = mock(Surface::class.java)
        val codec = mock(MediaCodec::class.java)
        val worker = worker(old, codec)
        val barrier = VideoReleaseBarrier(1)
        try {
            start(worker)
            worker.javaClass.getDeclaredMethod("setSurface", Surface::class.java).apply { isAccessible = true }.invoke(worker, current)
            detach(worker, old, barrier.acknowledgement())
            assertTrue(barrier.await(2000))
            assertSame(current, ReflectionHelpers.getField<Surface>(worker, "outputSurface"))
            verify(codec).setOutputSurface(current)
            verify(codec, never()).stop()
            verify(codec, never()).release()
        } finally { close(worker) }
    }
}
