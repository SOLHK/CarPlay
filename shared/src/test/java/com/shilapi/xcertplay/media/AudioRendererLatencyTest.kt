package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioTrack
import android.os.Build
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 28, 31, 33], manifest = Config.NONE, shadows = [AudioRendererLatencyTest.BoundedTrack::class])
class AudioRendererLatencyTest {
    private val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")

    @Before fun resetTrack() {
        BoundedTrack.capacity = 4096
        BoundedTrack.queued = 0
        BoundedTrack.playing = false
        BoundedTrack.plays = 0
        BoundedTrack.pauses = 0
        BoundedTrack.flushes = 0
        BoundedTrack.head = 0
        BoundedTrack.onWrite = null
        BoundedTrack.accepted.clear()
        BoundedTrack.calls = 0
        BoundedTrack.nativeThreshold = 1024
        BoundedTrack.refuseThreshold = false
        BoundedTrack.enforceStartupGate = false
        BoundedTrack.hardwareStarted = false
        BoundedTrack.minimumThreshold = 1
        BoundedTrack.frameBytes = 4
        BoundedTrack.thresholdCalls = 0
        BoundedTrack.underruns = 0
    }

    @Test(timeout = 10_000)
    fun smallMonoUsbTracksStartBeforeAPausedTrackRunsOutOfSpace() {
        for (rate in listOf(8_000, 16_000, 24_000, 44_100, 48_000)) {
            for (channels in 1..2) {
                val plan = MediaAudioBuffer.plan(false, rate, channels, rate * channels * 2 / 50, 100, wired = true)
                val threshold = MediaAudioBuffer.startBytesFor(plan.startBytes, plan.trackBufferBytes, 2048)
                assertTrue(threshold in 1..plan.trackBufferBytes)
                BoundedTrack.capacity = plan.trackBufferBytes
                BoundedTrack.queued = 0
                BoundedTrack.playing = false
                withRenderer(rate, channels, threshold) { renderer ->
                    write(renderer, ByteArray(plan.trackBufferBytes * 2))
                    assertTrue("$rate Hz / $channels channels did not start", BoundedTrack.playing)
                }
            }
        }
    }

    @Test(timeout = 5_000)
    fun closeJustBeforeWriteCannotBlockOrRestartTheOldSong() {
        withRenderer(48_000, 2, 2048) { renderer ->
            set(renderer, "playbackStarted", true)
            BoundedTrack.playing = true
            BoundedTrack.onWrite = { _, _ ->
                method("close").invoke(renderer)
                0 // The platform has no space after close paused the output.
            }
            write(renderer, ByteArray(16_384))
            assertEquals(1, BoundedTrack.calls)
            assertEquals(0, BoundedTrack.plays)
            assertTrue(BoundedTrack.pauses > 0)
            assertTrue(BoundedTrack.flushes > 0)
            assertFalse(BoundedTrack.playing)
        }
    }

    @Test(timeout = 5_000)
    fun temporaryZeroAndPartialNonblockingWritesPreserveEveryPcmByte() {
        withRenderer(48_000, 2, 2048) { renderer ->
            set(renderer, "playbackStarted", true)
            set(renderer, "fadeApplied", true)
            BoundedTrack.playing = true
            BoundedTrack.onWrite = { call, size -> when (call) { 1 -> 0; 2 -> 16; else -> size } }
            val pcm = ByteArray(96) { it.toByte() }
            write(renderer, pcm)
            assertArrayEquals(pcm, BoundedTrack.accepted.toByteArray())
            assertEquals(3, BoundedTrack.calls)
        }
    }

    @Test(timeout = 5_000)
    fun malformedPartialPcmFrameIsRejectedWithoutAnEndlessZeroWriteRetry() {
        withRenderer(48_000, 2, 2048) { renderer ->
            write(renderer, ByteArray(3))
            assertEquals(0, BoundedTrack.calls)
            assertEquals(AudioTrack.ERROR_BAD_VALUE, field("lastWriteErrorCode").get(renderer))
        }
    }

    @Test
    fun playbackPositionUsesTheHardwareHeadAndWrapsOnTheRtpClock() {
        withRenderer(48_000, 2, 2048) { renderer ->
            set(renderer, "firstRtpSample", 0xffff_fc00L.toInt())
            assertEquals(0xffff_fc00L, method("playbackPosition").invoke(renderer))
            BoundedTrack.head = 1536
            assertEquals(512L, method("playbackPosition").invoke(renderer))
        }
    }

    @Test
    fun startupBudgetIncludesTheNativeGateAndOnlyUsesSupportedPlatformApis() {
        withRenderer(48_000, 2, 1024) { renderer ->
            val track = field("track").get(renderer) as AudioTrack
            val actual = audioTrackStartThresholdBytes(track, 1024, 4)
            assertEquals(if (Build.VERSION.SDK_INT >= 31) 1024 else 4096, actual)
        }
    }

    @Test
    @Config(sdk = [33])
    fun refusedStartupThresholdReportsThePlatformsActualGate() {
        BoundedTrack.refuseThreshold = true
        BoundedTrack.nativeThreshold = 768
        withRenderer(48_000, 2, 1024) { renderer ->
            val track = field("track").get(renderer) as AudioTrack
            assertEquals(3072, audioTrackStartThresholdBytes(track, 1024, 4))
        }
    }

    @Test
    @Config(sdk = [31, 33])
    fun shortTailOpensNativeGateWithoutAdvancingTheClockBeforeHardwarePlayback() {
        BoundedTrack.enforceStartupGate = true
        BoundedTrack.nativeThreshold = 256
        withRenderer(48_000, 2, 1024) { renderer ->
            set(renderer, "firstRtpSample", 10_000)
            set(renderer, "platformStartThresholdBytes", 1024)
            write(renderer, ByteArray(384)) // 96 frames cannot meet the original 256-frame gate.
            assertFalse(BoundedTrack.playing)
            set(renderer, "lastPcmWriteNs", System.nanoTime() - 200_000_000L)
            method("maintainPlaybackBuffer").invoke(renderer)
            assertTrue(BoundedTrack.playing)
            assertEquals(96, BoundedTrack.nativeThreshold)
            assertEquals(10_000L, method("playbackPosition").invoke(renderer))

            // Maintenance before actual output must not immediately restore the old gate.
            method("maintainPlaybackBuffer").invoke(renderer)
            assertEquals(96, BoundedTrack.nativeThreshold)
            BoundedTrack.advance(32)
            assertEquals(10_032L, method("playbackPosition").invoke(renderer))
            method("maintainPlaybackBuffer").invoke(renderer)
            assertEquals(256, BoundedTrack.nativeThreshold)
            assertEquals(1024, field("platformStartThresholdBytes").get(renderer))
        }
    }

    @Test
    @Config(sdk = [31, 33])
    fun refusedOrClampedTailGateNeverPretendsUnplayedPcmWasHeard() {
        for (refused in listOf(true, false)) {
            resetTrack()
            BoundedTrack.enforceStartupGate = true
            BoundedTrack.nativeThreshold = 256
            BoundedTrack.refuseThreshold = refused
            BoundedTrack.minimumThreshold = 128
            withRenderer(48_000, 2, 1024) { renderer ->
                set(renderer, "firstRtpSample", 20_000)
                set(renderer, "platformStartThresholdBytes", 1024)
                write(renderer, ByteArray(384))
                set(renderer, "lastPcmWriteNs", System.nanoTime() - 200_000_000L)
                method("maintainPlaybackBuffer").invoke(renderer)
                BoundedTrack.advance(32)
                assertEquals(20_000L, method("playbackPosition").invoke(renderer))
                assertEquals(384, BoundedTrack.queued)
                assertEquals(if (refused) 1024 else 512, field("platformStartThresholdBytes").get(renderer))
                repeat(3) { method("maintainPlaybackBuffer").invoke(renderer) }
                assertEquals("A rejected gate must not trigger a busy retry loop", 1, BoundedTrack.thresholdCalls)
            }
        }
    }

    @Test
    @Config(sdk = [31, 33])
    fun laterNavigationPromptReopensNativeGateAfterAnUnderrunAtANonzeroHead() {
        BoundedTrack.enforceStartupGate = true
        BoundedTrack.nativeThreshold = 256
        withRenderer(48_000, 2, 1024, audioType = "default") { renderer ->
            set(renderer, "mappedChannel", AudioChannel.NAVIGATION)
            set(renderer, "firstRtpSample", 10_000)
            set(renderer, "platformStartThresholdBytes", 1024)
            write(renderer, ByteArray(1024))
            BoundedTrack.advance(256)
            assertEquals(10_256L, method("playbackPosition").invoke(renderer))

            // AudioTrack re-enters its native fill gate after underrun, without another play().
            BoundedTrack.hardwareStarted = false
            BoundedTrack.underruns = 1
            method("maintainPlaybackBuffer").invoke(renderer)
            write(renderer, ByteArray(384))
            BoundedTrack.advance(32)
            assertEquals(10_256L, method("playbackPosition").invoke(renderer))
            Thread.sleep(120)
            method("maintainPlaybackBuffer").invoke(renderer)
            assertEquals(96, BoundedTrack.nativeThreshold)
            assertEquals(1, BoundedTrack.plays)

            // The initial play() head was zero. Only progress beyond this prompt's head may
            // restore the gate; otherwise this second prompt would be stranded again.
            method("maintainPlaybackBuffer").invoke(renderer)
            assertEquals(96, BoundedTrack.nativeThreshold)
            assertEquals(10_256L, method("playbackPosition").invoke(renderer))
            BoundedTrack.advance(32)
            method("maintainPlaybackBuffer").invoke(renderer)
            assertEquals(10_288L, method("playbackPosition").invoke(renderer))
            assertEquals(256, BoundedTrack.nativeThreshold)
        }
    }

    @Test
    @Config(sdk = [31, 33])
    fun aTailWhoseHardwareHeadIsStillMovingDoesNotChangeTheNativeGate() {
        BoundedTrack.enforceStartupGate = true
        BoundedTrack.nativeThreshold = 512
        withRenderer(48_000, 2, 1024, audioType = "default") { renderer ->
            set(renderer, "mappedChannel", AudioChannel.NAVIGATION)
            set(renderer, "platformStartThresholdBytes", 2048)
            write(renderer, ByteArray(2048))
            set(renderer, "lastPcmWriteNs", System.nanoTime() - 200_000_000L)
            repeat(3) {
                BoundedTrack.advance(32)
                method("maintainPlaybackBuffer").invoke(renderer)
                assertEquals(512, BoundedTrack.nativeThreshold)
                assertEquals(0, BoundedTrack.thresholdCalls)
            }
        }
    }

    @Test
    fun fullEnhancedMusicQueueBackpressuresInsteadOfDroppingFrames() {
        withRenderer(48_000, 2, 2048) { renderer ->
            set(renderer, "started", true)
            @Suppress("UNCHECKED_CAST")
            val queue = field("queue").get(renderer) as java.util.concurrent.LinkedBlockingQueue<Any>
            val offer = method("offerBuffered", ByteArray::class.java, Int::class.javaPrimitiveType!!)
            var sample = 0
            while (queue.remainingCapacity() > 0) {
                assertTrue(offer.invoke(renderer, ByteArray(16), sample) as Boolean)
                sample += 1024
            }
            assertFalse(offer.invoke(renderer, ByteArray(16), sample) as Boolean)
            assertEquals(0, (field("packetsDroppedTotal").get(renderer) as java.util.concurrent.atomic.AtomicInteger).get())
            queue.poll()
            assertTrue(offer.invoke(renderer, ByteArray(16), sample) as Boolean)
        }
    }

    @Test
    fun speakerphoneSpeechNeverPausesForMusicRefillButMusicRoutingStillDoes() {
        for (audioType in listOf("telephony", "media", "compatibility")) {
            resetTrack()
            withRenderer(48_000, 2, 1024, audioType = audioType, speakerphoneCall = true,
                advancedMapping = true) { renderer ->
                val selection = method("mappedSelection").invoke(renderer) as AudioChannelSelection
                assertEquals(AudioChannel.MEDIA, selection.channel)
                set(renderer, "mappedChannel", selection.channel)
                set(renderer, "playbackStarted", true)
                BoundedTrack.playing = true
                BoundedTrack.underruns = 1
                method("maintainPlaybackBuffer").invoke(renderer)
                if (audioType == "telephony") {
                    assertEquals(AudioContentType.SPEECH, selection.contentType)
                    assertTrue("A live speakerphone call must not pause to refill a music buffer", BoundedTrack.playing)
                    assertEquals(0, BoundedTrack.pauses)
                } else {
                    assertEquals(AudioContentType.MUSIC, selection.contentType)
                    assertFalse("$audioType lost music underrun recovery", BoundedTrack.playing)
                    assertEquals(1, BoundedTrack.pauses)
                }
            }
        }
    }

    private fun withRenderer(rate: Int, channels: Int, threshold: Int,
        audioType: String = "media", speakerphoneCall: Boolean = false, advancedMapping: Boolean = false,
        block: (Any) -> Unit) {
        BoundedTrack.frameBytes = channels * 2
        val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().build())
            .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(rate)
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(if (channels == 1) android.media.AudioFormat.CHANNEL_OUT_MONO
                    else android.media.AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(BoundedTrack.capacity).build()
        val renderer = type.declaredConstructors.single { it.parameterCount == 13 }.apply { isAccessible = true }
            .newInstance(AudioFormat(AudioCodecKind.LPCM, rate, channels, 103, audioType),
                advancedMapping, false, 0, 0, AudioFocusCoordinator(null, false, false),
                0, 100, { _: String -> }, 0L, speakerphoneCall, null, false)
        set(renderer, "track", track)
        set(renderer, "startThresholdBytes", threshold)
        set(renderer, "bytesPerSecond", rate * channels * 2)
        try { block(renderer) } finally { method("close").invoke(renderer); track.release() }
    }

    private fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
    private fun set(renderer: Any, name: String, value: Any) = field(name).set(renderer, value)
    private fun method(name: String, vararg parameters: Class<*>) = type.getDeclaredMethod(name, *parameters).apply { isAccessible = true }
    private fun write(renderer: Any, pcm: ByteArray) = method("writePcm", ByteArray::class.java,
        Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!).invoke(renderer, pcm, 0, pcm.size)

    @Implements(AudioTrack::class)
    class BoundedTrack {
        @Implementation fun getBufferSizeInFrames() = capacity / frameBytes
        @Implementation(minSdk = 31) fun getStartThresholdInFrames() = nativeThreshold
        @Implementation(minSdk = 31) fun setStartThresholdInFrames(frames: Int): Int {
            thresholdCalls++
            if (refuseThreshold) return AudioTrack.ERROR_INVALID_OPERATION
            nativeThreshold = frames.coerceAtLeast(minimumThreshold)
            return nativeThreshold
        }
        @Implementation fun getPlaybackHeadPosition() = head
        @Implementation fun getUnderrunCount() = underruns
        @Implementation fun play() { playing = true; plays++ }
        @Implementation fun pause() { playing = false; pauses++ }
        @Implementation fun flush() { queued = 0; flushes++ }
        @Implementation fun write(bytes: ByteArray, offset: Int, size: Int, mode: Int): Int {
            assertEquals("A stopped track must never trap a blocking writer", AudioTrack.WRITE_NON_BLOCKING, mode)
            calls++
            val result = onWrite?.invoke(calls, size) ?: size
            if (!playing || enforceStartupGate) {
                check(queued + result <= capacity) { "Paused track filled before play(): $queued + $result > $capacity" }
                queued += result
            }
            for (i in 0 until result) accepted += bytes[offset + i]
            return result
        }
        companion object {
            var capacity = 4096
            var queued = 0
            var playing = false
            var plays = 0
            var pauses = 0
            var flushes = 0
            var head = 0
            var calls = 0
            var nativeThreshold = 1024
            var refuseThreshold = false
            var enforceStartupGate = false
            var hardwareStarted = false
            var minimumThreshold = 1
            var frameBytes = 4
            var thresholdCalls = 0
            var underruns = 0
            var onWrite: ((Int, Int) -> Int)? = null
            val accepted = mutableListOf<Byte>()

            fun advance(frames: Int) {
                if (!playing || (!hardwareStarted && queued / frameBytes < nativeThreshold)) return
                hardwareStarted = true
                val played = minOf(frames, queued / frameBytes)
                queued -= played * frameBytes
                head += played
            }
        }
    }
}
