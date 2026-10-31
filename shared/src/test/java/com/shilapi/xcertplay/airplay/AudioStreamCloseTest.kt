package com.shilapi.xcertplay.airplay

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real UDP/decryption callbacks must be quiescent before their old renderer is stopped. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AudioStreamCloseTest {
    private val key = ByteArray(32) { (it + 1).toByte() }

    @Test fun aClosedStreamCannotAllocateNewUdpPorts() {
        val stream = AudioStream(key, 100)
        stream.close()
        assertThrows(IllegalStateException::class.java) { stream.listen(object : AudioStream.Listener {}) }
        for (field in listOf("dataSocket", "controlSocket")) {
            assertNull(AudioStream::class.java.getDeclaredField(field).apply { isAccessible = true }.get(stream))
        }
    }

    @Test fun aDuplicateListenCannotReplaceAndLeakTheOriginalReceiver() {
        val stream = AudioStream(key, 100)
        val delivered = CountDownLatch(1)
        try {
            val (port) = stream.listen(object : AudioStream.Listener {
                override fun onRtp(rtp: ByteArray, sample: Int) { delivered.countDown() }
            })
            assertThrows(IllegalStateException::class.java) { stream.listen(object : AudioStream.Listener {}) }
            send(port)
            assertTrue("Rejected duplicate setup broke the original receiver", delivered.await(3, TimeUnit.SECONDS))
        } finally { stream.close() }
    }

    @Test fun closingFromTheStartupDiagnosticCannotPublishNewSockets() {
        lateinit var stream: AudioStream
        stream = AudioStream(key, 100) { stream.close() }
        assertThrows(IllegalStateException::class.java) { stream.listen(object : AudioStream.Listener {}) }
        for (field in listOf("dataSocket", "controlSocket")) {
            assertNull(AudioStream::class.java.getDeclaredField(field).apply { isAccessible = true }.get(stream))
        }
    }

    @Test fun closingDuringSocketSetupWaitsForTheAllocationToBeRetired() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val stream = AudioStream(key, 100) {
            entered.countDown()
            assertTrue(release.await(3, TimeUnit.SECONDS))
        }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val listening = workers.submit {
                assertThrows(IllegalStateException::class.java) { stream.listen(object : AudioStream.Listener {}) }
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val closing = workers.submit { closeStarted.countDown(); stream.close() }
            assertTrue(closeStarted.await(3, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { closing.get(100, TimeUnit.MILLISECONDS) }
            release.countDown()
            listening.get(3, TimeUnit.SECONDS)
            closing.get(3, TimeUnit.SECONDS)
            for (field in listOf("dataSocket", "controlSocket")) {
                assertNull(AudioStream::class.java.getDeclaredField(field).apply { isAccessible = true }.get(stream))
            }
        } finally {
            release.countDown()
            stream.close()
            workers.shutdownNow()
        }
    }

    @Test fun everyCloseWaitsForTheInFlightDelivery() {
        val stream = AudioStream(key, 100)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeStarted = CountDownLatch(2)
        val delivered = AtomicInteger()
        val workers = Executors.newFixedThreadPool(2)
        try {
            val (port) = stream.listen(object : AudioStream.Listener {
                override fun onRtp(rtp: ByteArray, sample: Int) {
                    entered.countDown()
                    // A callback can be in code that does not respond to interruption.
                    while (true) {
                        try { release.await(); break } catch (_: InterruptedException) { }
                    }
                    delivered.incrementAndGet()
                }
            })
            send(port)
            assertTrue("decrypted callback did not arrive", entered.await(3, TimeUnit.SECONDS))
            val closes = List(2) { workers.submit { closeStarted.countDown(); stream.close() } }
            assertTrue(closeStarted.await(3, TimeUnit.SECONDS))
            closes.forEach { close ->
                assertThrows(TimeoutException::class.java) { close.get(100, TimeUnit.MILLISECONDS) }
            }
            release.countDown()
            closes.forEach { it.get(3, TimeUnit.SECONDS) }
            assertEquals(1, delivered.get())
        } finally {
            release.countDown()
            stream.close()
            workers.shutdownNow()
        }
    }

    @Test fun reentrantCloseFromPacketObservationCannotStartOrFeedTheRenderer() {
        val stream = AudioStream(key, 100)
        val closed = CountDownLatch(1)
        val started = AtomicInteger()
        val delivered = CountDownLatch(1)
        try {
            val (port) = stream.listen(object : AudioStream.Listener {
                override fun onPacket(wire: ByteArray, rtp: ByteArray?, sample: Int?, error: Throwable?) {
                    if (rtp != null) { stream.close(); closed.countDown() }
                }
                override fun onStarted(firstSample: Int) { started.incrementAndGet() }
                override fun onRtp(rtp: ByteArray, sample: Int) { delivered.countDown() }
            })
            send(port)
            assertTrue(closed.await(3, TimeUnit.SECONDS))
            assertFalse("closed stream delivered stale audio", delivered.await(100, TimeUnit.MILLISECONDS))
            assertEquals(0, started.get())
        } finally { stream.close() }
    }

    @Test fun reentrantCloseFromStartCannotFeedStaleAudio() {
        val stream = AudioStream(key, 100)
        val closed = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        try {
            val (port) = stream.listen(object : AudioStream.Listener {
                override fun onStarted(firstSample: Int) { stream.close(); closed.countDown() }
                override fun onRtp(rtp: ByteArray, sample: Int) { delivered.countDown() }
            })
            send(port)
            assertTrue(closed.await(3, TimeUnit.SECONDS))
            assertFalse("closed stream delivered stale audio", delivered.await(100, TimeUnit.MILLISECONDS))
        } finally { stream.close() }
    }

    private fun send(port: Int) {
        val header = ByteArray(12).apply { this[0] = 0x80.toByte(); this[1] = 0x60; this[3] = 1 }
        val tail = ByteArray(8) { it.toByte() }
        val nonce = ByteArray(12).also { tail.copyInto(it, 4) }
        val wire = header + AirPlayCrypto.chachaSeal(key, nonce, ByteArray(32), header.copyOfRange(4, 12)) + tail
        DatagramSocket().use { it.send(DatagramPacket(wire, wire.size, InetAddress.getLoopbackAddress(), port)) }
    }
}
