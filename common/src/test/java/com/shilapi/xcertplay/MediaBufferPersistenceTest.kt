package com.shilapi.xcertplay

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class MediaBufferPersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Before fun clearPreferences() {
        prefs.edit().clear().apply()
    }

    @Test fun freshInstallUsesTransportSpecificLowLatencyDefaults() {
        assertEquals(150, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true))
        assertEquals(100, AirPlayPersistence.loadMediaBufferMillis(context, wireless = false))
    }

    @Test fun wiredAndWirelessAreSavedSeparately() {
        AirPlayPersistence.saveMediaBufferMillis(context, wireless = false, millis = 50)
        AirPlayPersistence.saveMediaBufferMillis(context, wireless = true, millis = 1000)
        assertEquals(50, AirPlayPersistence.loadMediaBufferMillis(context, wireless = false))
        assertEquals(1000, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true))
    }

    @Test fun legacySharedDelayDoesNotOverrideTheNewWiredDefault() {
        prefs.edit().putInt("media_buffer_ms", 50).apply()
        assertEquals(100, AirPlayPersistence.loadMediaBufferMillis(context, wireless = false))
        // 50 ms is not a wireless preset, so wireless falls back to the default.
        assertEquals(150, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true))
    }

    @Test fun changingWirelessDoesNotMoveTheWiredDefault() {
        prefs.edit().putInt("media_buffer_ms", 500).apply()
        AirPlayPersistence.saveMediaBufferMillis(context, wireless = true, millis = 1000)
        assertEquals(100, AirPlayPersistence.loadMediaBufferMillis(context, wireless = false))
        assertEquals(1000, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true))
    }

    @Test fun wirelessRejectsWiredOnlyPresets() {
        AirPlayPersistence.saveMediaBufferMillis(context, wireless = true, millis = 100)
        assertEquals(150, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true))
    }

    @Test fun savedWirelessChoicesSurviveTheNewInstallDefault() {
        for (millis in listOf(300, 500, 1000)) {
            prefs.edit().putInt("media_buffer_ms", millis).apply()
            assertEquals(millis, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true))
            assertEquals(100, AirPlayPersistence.loadMediaBufferMillis(context, wireless = false))
        }
    }

    @Test fun wirelessMinimumCanBeSavedWithoutChangingAnExplicitWiredChoice() {
        AirPlayPersistence.saveMediaBufferMillis(context, wireless = false, millis = 500)
        AirPlayPersistence.saveMediaBufferMillis(context, wireless = true, millis = 150)
        assertEquals(150, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true))
        assertEquals(500, AirPlayPersistence.loadMediaBufferMillis(context, wireless = false))
    }

    @Test fun explicitWiredChoicesSurviveTheNewInstallDefault() {
        for (millis in listOf(50, 100, 150, 300, 500, 1000)) {
            prefs.edit().putInt("wired_media_buffer_ms", millis).apply()
            assertEquals(millis, AirPlayPersistence.loadMediaBufferMillis(context, wireless = false))
        }
    }

    @Test fun invalidStoredValuesFallBackIndependently() {
        prefs.edit().putInt("media_buffer_ms", -1).putInt("wired_media_buffer_ms", 99999).apply()
        assertEquals(150, AirPlayPersistence.loadMediaBufferMillis(context, wireless = true))
        assertEquals(100, AirPlayPersistence.loadMediaBufferMillis(context, wireless = false))
    }
}
