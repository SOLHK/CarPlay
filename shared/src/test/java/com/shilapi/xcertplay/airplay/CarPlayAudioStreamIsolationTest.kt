package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayAudioStreamIsolationTest {
    @Test fun guidanceSetupAndMediaReplacementKeepTheOtherAudioStreamAlive() {
        val session = session()
        val engine = CarPlayMediaEngine(object : MediaSink {})
        try {
            assertNotNull(engine.onAudio(session, 100, setup("media")))
            val streams = streams(engine)
            val mediaKey = CarPlayMediaEngine.StreamKey(session, 100, "media")
            val firstMedia = streams[mediaKey]
            assertNotNull(engine.onAudio(session, 100, setup("default")))
            val guidanceKey = CarPlayMediaEngine.StreamKey(session, 100, "default")
            val guidance = streams[guidanceKey]
            assertSame(firstMedia, streams[mediaKey])
            assertEquals(2, streams.size)
            assertNotNull(engine.onAudio(session, 100, setup("MEDIA")))
            assertNotSame(firstMedia, streams[mediaKey])
            assertSame(guidance, streams[guidanceKey])
            assertEquals(2, streams.size)
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test fun typeTeardownClosesEveryVariantButKeepsOtherTypes() {
        val stopped = mutableListOf<AudioStreamId>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioStopped(id: AudioStreamId) { stopped.add(id) }
        })
        val session = session()
        try {
            assertNotNull(engine.onAudio(session, 100, setup("media")))
            assertNotNull(engine.onAudio(session, 100, setup("default")))
            assertNotNull(engine.onAudio(session, 102, setup("media")))
            stopped.clear()
            engine.onTeardown(session, 100)
            assertEquals(setOf(AudioStreamId(100, "media"), AudioStreamId(100, "default")), stopped.toSet())
            assertEquals(setOf(CarPlayMediaEngine.StreamKey(session, 102, "media")), streams(engine).keys)
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test fun teardownStopsTheRendererOnlyAfterTheLastRealtimeDeliveryFinishes() {
        val session = session()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val teardownStarted = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        var setupComplete = false
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
                entered.countDown()
                while (true) {
                    try { release.await(); break } catch (_: InterruptedException) { }
                }
                events += "rtp"
            }
            override fun onAudioStopped(id: AudioStreamId) {
                if (setupComplete) { events += "stop"; stopped.countDown() }
            }
        })
        val worker = Executors.newSingleThreadExecutor()
        try {
            val response = engine.onAudio(session, 100, setup("media"))!!
            setupComplete = true
            val key = AirPlayCrypto.hkdfSha512(session.sharedSecret!!,
                "DataStream-Salt42".toByteArray(), "DataStream-Output-Encryption-Key".toByteArray())
            val header = ByteArray(12).apply { this[0] = 0x80.toByte(); this[1] = 0x60; this[3] = 1 }
            val nonceTail = ByteArray(8) { it.toByte() }
            val nonce = ByteArray(12).also { nonceTail.copyInto(it, 4) }
            val wire = header + AirPlayCrypto.chachaSeal(key, nonce, ByteArray(32), header.copyOfRange(4, 12)) + nonceTail
            DatagramSocket().use { it.send(DatagramPacket(wire, wire.size, InetAddress.getLoopbackAddress(),
                (response.getValue("dataPort") as Number).toInt())) }
            assertTrue("RTP did not reach the sink", entered.await(3, TimeUnit.SECONDS))
            val done = worker.submit { teardownStarted.countDown(); engine.onTeardown(session, 100) }
            assertTrue(teardownStarted.await(3, TimeUnit.SECONDS))
            assertFalse("renderer stopped before old delivery was retired", stopped.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            done.get(3, TimeUnit.SECONDS)
            assertEquals(listOf("rtp", "stop"), events.toList())
            assertTrue(streams(engine).isEmpty())
        } finally {
            release.countDown()
            engine.onSessionClosed(session)
            session.close()
            worker.shutdownNow()
        }
    }

    @Test fun reentrantTeardownFromAudioStartCannotRestartTheMicrophone() {
        for (teardownDuringStart in listOf(false, true)) {
            val session = session(object : Socket() {
                override fun getInetAddress(): InetAddress = InetAddress.getLoopbackAddress()
                override fun getRemoteSocketAddress(): java.net.SocketAddress =
                    java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), 54321)
            })
            val audioStarted = CountDownLatch(1)
            val microphoneStarts = AtomicInteger()
            val microphoneStops = AtomicInteger()
            lateinit var engine: CarPlayMediaEngine
            engine = CarPlayMediaEngine(object : MediaSink {
                override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {
                    if (teardownDuringStart) engine.onTeardown(session, 100)
                    audioStarted.countDown()
                }
                override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {
                    microphoneStarts.incrementAndGet()
                }
                override fun onMicrophoneStopped(id: AudioStreamId) { microphoneStops.incrementAndGet() }
            }, microphoneEnabled = true)
            try {
                // Telephony waits for its first downlink; no silent-start timer can mask this race.
                val response = engine.onAudio(session, 100,
                    setup("telephony") + mapOf("input" to true, "dataPort" to 4242))!!
                val receiver = streams(engine).values.single()
                val key = AirPlayCrypto.hkdfSha512(session.sharedSecret!!,
                    "DataStream-Salt42".toByteArray(), "DataStream-Output-Encryption-Key".toByteArray())
                val header = ByteArray(12).apply { this[0] = 0x80.toByte(); this[1] = 0x60; this[3] = 1 }
                val tail = ByteArray(8) { it.toByte() }
                val nonce = ByteArray(12).also { tail.copyInto(it, 4) }
                val wire = header + AirPlayCrypto.chachaSeal(key, nonce, ByteArray(32), header.copyOfRange(4, 12)) + tail
                DatagramSocket().use { it.send(DatagramPacket(wire, wire.size, InetAddress.getLoopbackAddress(),
                    (response.getValue("dataPort") as Number).toInt())) }
                assertTrue("downlink did not start", audioStarted.await(3, TimeUnit.SECONDS))
                receiver.close() // Delivery barrier: inspect counters after onStarted has returned.
                assertEquals("ordinary downlink must start; torn-down downlink must stay stopped",
                    if (teardownDuringStart) 0 else 1, microphoneStarts.get())
                assertEquals(if (teardownDuringStart) 1 else 0, microphoneStops.get())
                if (teardownDuringStart) assertTrue(streams(engine).isEmpty())
            } finally {
                engine.onSessionClosed(session)
                session.close()
            }
        }
    }

    private fun setup(audioType: String): Map<String, Any?> = mapOf(
        "audioType" to audioType,
        "audioFormat" to 0x8000L,
        "streamConnectionID" to 42L,
    )

    @Suppress("UNCHECKED_CAST")
    private fun streams(engine: CarPlayMediaEngine): MutableMap<CarPlayMediaEngine.StreamKey, Closeable> =
        CarPlayMediaEngine::class.java.getDeclaredField("streams").apply { isAccessible = true }
            .get(engine) as MutableMap<CarPlayMediaEngine.StreamKey, Closeable>

    private fun session(socket: Socket = Socket()): AirPlaySession {
        val session = AirPlaySession(
            socket = socket,
            config = AirPlayConfig(
                deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
                sourceVersion = "1.0", main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            ),
            identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
            listener = object : AirPlaySessionListener {}, media = object : AirPlayMediaHandler {},
        )
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        session.pairVerify.javaClass.getDeclaredField("sharedSecret").apply { isAccessible = true }
            .set(session.pairVerify, secret)
        return session
    }
}
