package com.shilapi.xcertplay.location

import android.location.Location
import android.location.LocationListener
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE)
class AndroidLocationLifecycleTest {
    private lateinit var source: AndroidCarPlayLocationProvider
    private lateinit var listener: LocationListener
    @Before fun setup() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        source = AndroidCarPlayLocationProvider(RuntimeEnvironment.getApplication())
        listener = AndroidCarPlayLocationProvider::class.java.getDeclaredField("listener").apply { isAccessible = true }.get(source) as LocationListener
        assertTrue(source.start())
    }
    @After fun cleanup() { source.stop() }
    private fun fix() = Location("gps").apply {
        latitude = 31.2; longitude = 121.5; accuracy = 3f
        time = 1700000000123; elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }
    @Test fun lateOlderCallbackCannotReplaceCurrentFix() {
        val old = fix(); ShadowSystemClock.advanceBy(Duration.ofMillis(500))
        listener.onLocationChanged(fix()); listener.onLocationChanged(old)
        assertNotNull(source.latestNmea()); assertNull(source.latestNmea())
        val cache = AndroidCarPlayLocationProvider::class.java.getDeclaredField("latestFixes").apply { isAccessible = true }.get(source) as Map<*, *>
        assertEquals(SystemClock.elapsedRealtimeNanos(), (cache["gps"] as Location).elapsedRealtimeNanos)
    }
    @Test fun disablingProviderInvalidatesItsCachedPosition() {
        listener.onLocationChanged(fix()); assertNotNull(source.latestNmea()); listener.onProviderDisabled("gps")
        assertTrue(requireNotNull(source.latestNmea()).contains(",V,")); assertNull(source.latestNmea())
    }
    @Test fun stoppedProviderCannotEmitLateCallbacks() {
        listener.onLocationChanged(fix()); source.stop(); listener.onLocationChanged(fix()); assertNull(source.latestNmea())
        assertTrue(source.start()); assertNull(source.latestNmea())
    }
}
