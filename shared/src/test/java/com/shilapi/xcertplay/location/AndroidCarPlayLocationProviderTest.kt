package com.shilapi.xcertplay.location

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.transport.NmeaLocationEncoder
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [CarPlayLocationManagerShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidCarPlayLocationProviderTest {
    private val providers = mutableListOf<AndroidCarPlayLocationProvider>()

    @Before fun setUp() {
        CarPlayLocationReplay.reset()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(100))
    }

    @After fun tearDown() {
        CarPlayLocationReplay.beforeRemove = null
        providers.forEach { it.stop() }
        CarPlayLocationReplay.requests.map { it.looper.thread }.distinct().forEach {
            it.join(5_000)
            assertFalse("location callback thread leaked: ${it.name}", it.isAlive)
        }
    }

    @Test fun callbacksArriveWhileTheMainLooperIsPausedAndStopWithTheSubscription() {
        val provider = provider()
        assertTrue(provider.start())
        val requests = CarPlayLocationReplay.requests
        assertEquals(setOf("gps", "network"), requests.map { it.provider }.toSet())
        assertTrue(requests.all { it.minTimeMillis == 500L })
        val callback = requests.first()
        assertNotSame(Looper.getMainLooper(), callback.looper)
        assertTrue(requests.all { it.looper === callback.looper && it.listener === callback.listener })

        val delivered = CountDownLatch(1)
        Handler(callback.looper).post {
            callback.listener.onLocationChanged(fix())
            delivered.countDown()
        }
        assertTrue("location delivery must not need the paused UI looper", delivered.await(5, TimeUnit.SECONDS))
        assertNotNull(provider.latestNmea())
        assertNull(provider.latestNmea())

        assertTrue(provider.start())
        assertEquals(2, requests.size)
        provider.stop()
        provider.stop()
        assertEquals(listOf(callback.listener), CarPlayLocationReplay.removed)
        assertNull(provider.latestNmea())
    }

    @Test fun disabledProviderDoesNotSeedOrReviveItsOldPosition() {
        CarPlayLocationReplay.disabled += "gps"
        CarPlayLocationReplay.known["gps"] = fix()
        val provider = provider()
        assertTrue(provider.start())
        val listener = CarPlayLocationReplay.requests.first().listener
        assertNull(provider.latestNmea())
        listener.onLocationChanged(fix())
        assertNull(provider.latestNmea())

        listener.onProviderEnabled("gps")
        listener.onLocationChanged(fix())
        assertNotNull(provider.latestNmea())
        listener.onProviderDisabled("gps")
        assertEquals(NmeaLocationEncoder.unavailable(), provider.latestNmea())
        ShadowSystemClock.advanceBy(Duration.ofMillis(500))
        listener.onLocationChanged(fix())
        assertNull(provider.latestNmea())
        listener.onProviderEnabled("gps")
        assertNull(provider.latestNmea())
        listener.onLocationChanged(fix())
        assertNotNull(provider.latestNmea())
    }

    @Test fun aLateCallbackOrDisableFromTheOldSessionCannotChangeTheRestartedSession() {
        val provider = provider()
        assertTrue(provider.start())
        val old = CarPlayLocationReplay.requests.first().listener
        old.onLocationChanged(fix(latitude = 20.0))
        assertNotNull(provider.latestNmea())
        provider.stop()
        old.onLocationChanged(fix(latitude = 20.5))
        assertNull(provider.latestNmea())

        assertTrue(provider.start())
        val current = CarPlayLocationReplay.requests.last().listener
        assertNotSame(old, current)
        current.onLocationChanged(fix(latitude = 22.0))
        old.onProviderDisabled("gps")
        old.onLocationChanged(fix(latitude = 21.0))
        assertTrue(requireNotNull(provider.latestNmea()).contains(",2200.0000,N,"))
    }

    @Test fun aProviderEnabledBetweenSeedingAndRegistrationDoesNotNeedAnotherEnableEvent() {
        CarPlayLocationReplay.disabled += "gps"
        CarPlayLocationReplay.known["gps"] = fix()
        CarPlayLocationReplay.beforeRequest = { CarPlayLocationReplay.disabled.remove(it) }
        val provider = provider()
        assertTrue(provider.start())
        assertNull(provider.latestNmea())

        // Enabling happened before registration, so no enabled callback belongs to this listener.
        CarPlayLocationReplay.requests.first().listener.onLocationChanged(fix())
        assertNotNull(provider.latestNmea())
    }

    @Test fun anOldUnregisterCannotRemoveAReplacementStartedDuringStop() {
        val provider = provider()
        assertTrue(provider.start())
        val old = CarPlayLocationReplay.requests.first().listener
        CarPlayLocationReplay.beforeRemove = {
            CarPlayLocationReplay.beforeRemove = null
            assertSame(old, it)
            assertTrue(provider.start())
        }
        provider.stop()

        val current = CarPlayLocationReplay.requests.last().listener
        assertNotSame(old, current)
        assertTrue(current in CarPlayLocationReplay.active)
        assertFalse(old in CarPlayLocationReplay.active)
        current.onLocationChanged(fix())
        assertNotNull(provider.latestNmea())
    }

    @Test fun totalRegistrationFailureCleansUpAndAllowsARetry() {
        CarPlayLocationReplay.failRequests += setOf("gps", "network")
        val provider = provider()
        assertFalse(provider.start())
        assertNull(provider.latestNmea())
        val failed = CarPlayLocationReplay.requests.first().listener
        assertEquals(listOf(failed), CarPlayLocationReplay.removed)

        CarPlayLocationReplay.failRequests.clear()
        assertTrue(provider.start())
        val current = CarPlayLocationReplay.requests.last().listener
        assertNotSame(failed, current)
        failed.onLocationChanged(fix(latitude = 20.0))
        assertNull(provider.latestNmea())
        current.onLocationChanged(fix(latitude = 22.0))
        assertNotNull(provider.latestNmea())
    }

    @Test fun aFailedProviderSubscriptionCannotLeaveItsSeededFixActive() {
        CarPlayLocationReplay.known["gps"] = fix()
        CarPlayLocationReplay.failRequests += "gps"
        val provider = provider()
        assertTrue(provider.start())
        assertNull(provider.latestNmea())
        val current = CarPlayLocationReplay.requests.last().listener
        current.onLocationChanged(fix("network"))
        assertNotNull(provider.latestNmea())
    }

    private fun provider() = AndroidCarPlayLocationProvider(RuntimeEnvironment.getApplication())
        .also { providers += it }

    private fun fix(provider: String = "gps", latitude: Double = 21.0) = Location(provider).apply {
        this.latitude = latitude
        longitude = 114.0
        accuracy = 5f
        time = 1_799_136_000_125L
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }
}

object CarPlayLocationReplay {
    data class Request(val provider: String, val minTimeMillis: Long, val listener: LocationListener, val looper: Looper)
    val requests = mutableListOf<Request>()
    val removed = mutableListOf<LocationListener>()
    val active = mutableSetOf<LocationListener>()
    val known = mutableMapOf<String, Location>()
    val disabled = mutableSetOf<String>()
    val failRequests = mutableSetOf<String>()
    var beforeRequest: ((String) -> Unit)? = null
    var beforeRemove: ((LocationListener) -> Unit)? = null

    fun reset() {
        requests.clear()
        removed.clear()
        active.clear()
        known.clear()
        disabled.clear()
        failRequests.clear()
        beforeRequest = null
        beforeRemove = null
    }
}

@Implements(LocationManager::class)
class CarPlayLocationManagerShadow {
    @Implementation fun getAllProviders(): List<String> = listOf("gps", "network")
    @Implementation fun isProviderEnabled(provider: String): Boolean = provider !in CarPlayLocationReplay.disabled
    @Implementation fun getLastKnownLocation(provider: String): Location? = CarPlayLocationReplay.known[provider]

    @Implementation fun requestLocationUpdates(
        provider: String,
        minTimeMillis: Long,
        minDistanceMeters: Float,
        listener: LocationListener,
        looper: Looper,
    ) {
        CarPlayLocationReplay.requests += CarPlayLocationReplay.Request(provider, minTimeMillis, listener, looper)
        CarPlayLocationReplay.beforeRequest?.invoke(provider)
        if (provider in CarPlayLocationReplay.failRequests) throw SecurityException("test denied provider")
        CarPlayLocationReplay.active += listener
        if (provider in CarPlayLocationReplay.disabled) listener.onProviderDisabled(provider)
    }

    @Implementation fun removeUpdates(listener: LocationListener) {
        CarPlayLocationReplay.beforeRemove?.invoke(listener)
        CarPlayLocationReplay.removed += listener
        CarPlayLocationReplay.active -= listener
    }
}
