package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.net.Socket
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayMediaEngineTest {
    @Test
    fun streamConnectionIdUsesUnsignedDecimalForHkdfSalt() {
        assertEquals("18446744073709551615", unsignedPlistDecimal(-1L))
        assertEquals(
            BigInteger("18446744073709551615"),
            unsignedPlistInteger(-1L),
        )
    }

    @Test
    fun screenStreamTeardownReportsInactive() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val sink = object : MediaSink {
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                events += type to active
            }
        }
        val session = testSession()

        try {
            val engine = CarPlayMediaEngine(sink)
            engine.onTeardown(session, 110)
            engine.onTeardown(session, 100)
        } finally {
            session.close()
        }

        assertEquals(listOf(110 to false), events)
    }

    @Test
    fun sessionCloseReportsAllScreenStreamsInactive() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val sink = object : MediaSink {
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                events += type to active
            }
        }
        val session = testSession()
        val engine = CarPlayMediaEngine(sink)
        val streamsField = CarPlayMediaEngine::class.java.getDeclaredField("streams").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val streams = streamsField.get(engine) as
            MutableMap<CarPlayMediaEngine.StreamKey, Closeable>
        streams[CarPlayMediaEngine.StreamKey(session, 110)] = Closeable {}
        streams[CarPlayMediaEngine.StreamKey(session, 111)] = Closeable {}
        streams[CarPlayMediaEngine.StreamKey(session, 100)] = Closeable {}

        engine.onSessionClosed(session)
        session.close()

        assertEquals(setOf(110 to false, 111 to false), events.toSet())
        assertTrue(streams.isEmpty())
    }

    @Test
    fun videoRemoteControlSessionsAreAcceptedOnlyWithVideoInCar() {
        val stream = mapOf("type" to 130L, "clientTypeUUID" to "A6B27562-B43A-4F2D-B75F-82391E250194", "controlType" to 1L)
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val plain = testSession()
        val video = testSession(videoInCar = true)
        try {
            assertNull(engine.onDataStream(plain, stream))
            val first = engine.onDataStream(video, stream)
            assertEquals(130, first?.get("type"))
            assertEquals(3L, first?.get("streamID"))
            assertEquals(4L, engine.onDataStream(video, stream)?.get("streamID"))
        } finally {
            plain.close()
            video.close()
        }
    }


    @Test
    fun feedbackNeverLeaksAnotherPhonesAudioStreams() {
        val engine = CarPlayMediaEngine(object : MediaSink {})
        val old = testSession()
        val replacement = testSession()
        val map = metadata(engine)
        val stream = CarPlayMediaEngine.StreamKey(replacement, 100, "media")
        // The old implementation read every value, then sent another session's
        // streamConnectionID back to this phone. A sentinel also detects eager reads.
        map[stream] = Any()
        try {
            assertNull(engine.onFeedback(old))
        } finally {
            map.clear()
            old.close()
            replacement.close()
        }
    }

    @Test
    fun closingOldSessionKeepsReplacementMetadataAndMicState() {
        val stopped = mutableListOf<AudioStreamId>()
        val inactive = mutableListOf<Int>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioStopped(id: AudioStreamId) { stopped += id }
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                if (!active) inactive += type
            }
        })
        val old = testSession()
        val replacement = testSession()
        val activeStreams = registrations(engine)
        val meta = metadata(engine)
        val microphones = microphones(engine)
        val oldScreen = CarPlayMediaEngine.StreamKey(old, 110)
        val newScreen = CarPlayMediaEngine.StreamKey(replacement, 110)
        val oldAudio = CarPlayMediaEngine.StreamKey(old, 100, "media")
        val newAudio = CarPlayMediaEngine.StreamKey(replacement, 100, "media")
        val oldClosed = mutableListOf<String>()
        activeStreams[oldScreen] = Closeable { oldClosed += "screen" }
        activeStreams[oldAudio] = Closeable { oldClosed += "audio" }
        activeStreams[newScreen] = Closeable { }
        activeStreams[newAudio] = Closeable { }
        meta[oldAudio] = Any()
        meta[newAudio] = Any()
        microphones[newAudio] = Any()
        try {
            engine.onSessionClosed(old)
            assertEquals(setOf("screen", "audio"), oldClosed.toSet())
            assertEquals(setOf(newScreen, newAudio), activeStreams.keys)
            assertEquals(setOf(newAudio), meta.keys)
            assertEquals(setOf(newAudio), microphones.keys)
            assertTrue("Old session cannot stop replacement audio", stopped.isEmpty())
            assertTrue("Old session cannot blank replacement screen", inactive.isEmpty())
        } finally {
            activeStreams.clear()
            meta.clear()
            microphones.clear()
            old.close()
            replacement.close()
        }
    }

    @Test
    fun unexpectedAudioDisconnectReleasesItsRenderer() {
        val stopped = mutableListOf<AudioStreamId>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioStopped(id: AudioStreamId) { stopped += id }
        })
        val session = testSession()
        registrations(engine)[CarPlayMediaEngine.StreamKey(session, 100, "media")] = Closeable { }
        try {
            engine.onSessionClosed(session)
            assertEquals(listOf(AudioStreamId(100, "media")), stopped)
        } finally { session.close() }
    }

    @Test
    fun oldTeardownCannotStopReplacementScreenOrAudio() {
        val stopped = mutableListOf<AudioStreamId>()
        val inactive = mutableListOf<Int>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioStopped(id: AudioStreamId) { stopped += id }
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                if (!active) inactive += type
            }
        })
        val old = testSession()
        val replacement = testSession()
        val registrations = registrations(engine)
        val oldScreen = CarPlayMediaEngine.StreamKey(old, 110)
        val newScreen = CarPlayMediaEngine.StreamKey(replacement, 110)
        val oldAudio = CarPlayMediaEngine.StreamKey(old, 100, "media")
        val newAudio = CarPlayMediaEngine.StreamKey(replacement, 100, "media")
        listOf(oldScreen, newScreen, oldAudio, newAudio).forEach { registrations[it] = Closeable {} }
        try {
            engine.onTeardown(old, 110)
            engine.onTeardown(old, 100)
            assertTrue("Old TEARDOWN cannot stop new audio", stopped.isEmpty())
            assertTrue("Old TEARDOWN cannot blank new screen", inactive.isEmpty())
            engine.onTeardown(replacement, 110)
            engine.onTeardown(replacement, 100)
            assertEquals(listOf(110), inactive)
            assertEquals(listOf(AudioStreamId(100, "media")), stopped)
        } finally {
            registrations.clear()
            old.close()
            replacement.close()
        }
    }

    @Test
    fun oldUnduckCannotCancelNewPhoneNavigationFocus() {
        val received = mutableListOf<Boolean>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioDuck(ducked: Boolean) { received += ducked }
        })
        val old = testSession()
        val replacement = testSession()
        try {
            engine.onAudioDuck(old, true)
            engine.onAudioDuck(replacement, true)
            engine.onAudioDuck(old, false)
            assertEquals(listOf(true, true), received)
            engine.onAudioDuck(replacement, false)
            assertEquals(listOf(true, true, false), received)
        } finally {
            old.close()
            replacement.close()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun registrations(engine: CarPlayMediaEngine): MutableMap<CarPlayMediaEngine.StreamKey, Closeable> =
        CarPlayMediaEngine::class.java.getDeclaredField("streams").apply { isAccessible = true }.get(engine)
            as MutableMap<CarPlayMediaEngine.StreamKey, Closeable>

    @Suppress("UNCHECKED_CAST")
    private fun metadata(engine: CarPlayMediaEngine): MutableMap<CarPlayMediaEngine.StreamKey, Any> =
        CarPlayMediaEngine::class.java.getDeclaredField("audioMeta").apply { isAccessible = true }.get(engine)
            as MutableMap<CarPlayMediaEngine.StreamKey, Any>

    @Suppress("UNCHECKED_CAST")
    private fun microphones(engine: CarPlayMediaEngine): MutableMap<CarPlayMediaEngine.StreamKey, Any> =
        CarPlayMediaEngine::class.java.getDeclaredField("pendingMicrophone").apply { isAccessible = true }.get(engine)
            as MutableMap<CarPlayMediaEngine.StreamKey, Any>

    private fun testSession(videoInCar: Boolean = false): AirPlaySession = AirPlaySession(
        socket = Socket(),
        config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0",
            main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            videoInCar = videoInCar,
        ),
        identity = AirPlayIdentity.generate(),
        pairings = PairingStore(),
        mfi = null,
        listener = object : AirPlaySessionListener {},
        media = object : AirPlayMediaHandler {},
    )
}
