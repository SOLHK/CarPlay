package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class AndroidMediaSinkStateTest {
    @Test fun lateAudioPacketsAndStartCallbacksCannotRecreateAClosedRenderer() {
        val events = mutableListOf<Boolean>()
        val sink = AndroidMediaSink(onMediaAudioChanged = events::add)
        val id = com.shilapi.xcertplay.airplay.AudioStreamId(100, "media")
        val format = com.shilapi.xcertplay.airplay.AudioFormat(
            payloadType = 96, audioType = "media", codec = com.shilapi.xcertplay.airplay.AudioCodecKind.LPCM,
            sampleRate = 48000, channels = 2)
        sink.close()
        sink.onAudioRtp(id, format, ByteArray(16), 0)
        sink.onAudioStarted(id, format, 0)
        sink.onAudioStopped(id)
        sink.close()
        val renderers = sink.javaClass.getDeclaredField("audioRenderers").apply { isAccessible = true }.get(sink) as Map<*, *>
        assertTrue(renderers.isEmpty())
        assertTrue(events.isEmpty())
    }

    @Test fun recreatingTheScreenRestoresItsActiveVideoState() {
        val sink = AndroidMediaSink()
        sink.onScreenStreamActive(110, true)
        sink.onScreenStreamActive(111, true)
        sink.onScreenStreamActive(111, false)
        val events = mutableListOf<Pair<Int, Boolean>>()
        sink.setScreenStreamActiveChangedListener { type, active -> events.add(type to active) }
        assertEquals(listOf(110 to true), events)
        sink.close()
        assertEquals(listOf(110 to true, 110 to false), events)
        val afterClose = mutableListOf<Pair<Int, Boolean>>()
        sink.setScreenStreamActiveChangedListener { type, active -> afterClose.add(type to active) }
        assertTrue(afterClose.isEmpty())
    }
}
