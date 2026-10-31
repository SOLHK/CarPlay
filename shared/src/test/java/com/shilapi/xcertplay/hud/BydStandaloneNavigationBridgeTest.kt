package com.shilapi.xcertplay.hud

import android.app.Application
import android.content.Context
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class BydStandaloneNavigationBridgeTest {
    private lateinit var app: Application
    private var initialBroadcastCount = 0

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        BydOutputSettings.setEnabled(app, true)
        BydOutputSettings.setHudSong(app, false)
        BydStandaloneHudOutput.syntheticHold = false
        // Install a real broadcast output without starting the periodic worker. Assertions below
        // therefore prove delivery from an incoming frame, without advancing a timer.
        val output = BydStandaloneHudOutput::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(app)
        setBridgeField("context", app)
        setBridgeField("output", output)
        BydStandaloneNavigationBridge.clear()
        initialBroadcastCount = shadowOf(app).broadcastIntents.size
    }

    @After fun tearDown() {
        BydStandaloneHudOutput.syntheticHold = false
        BydStandaloneNavigationBridge.clear()
        setBridgeField("output", null)
        setBridgeField("context", null)
    }

    @Test fun changedTurnAndDistancePublishWithoutWaitingForThePeriodicTick() {
        maneuver(type = 1)
        route(state = 1, distance = 500)
        assertEquals(listOf(BydStandalonePackets.start(), BydStandalonePackets.guidance(2, 0, 500, "")), packets())

        route(state = 1, distance = 250)
        assertEquals(BydStandalonePackets.guidance(2, 0, 250, ""), packets().last())
        maneuver(type = 2)
        assertEquals(BydStandalonePackets.guidance(3, 0, 250, ""), packets().last())
    }

    @Test fun arrivalClearsImmediatelyAndKeepaliveCannotRestoreOldGuidance() {
        maneuver(type = 1)
        route(state = 1, distance = 40)
        route(state = 2, distance = 0)
        assertEquals(BydStandalonePackets.clear(), packets().last())
        val sent = packets().size
        tick()
        assertEquals(sent, packets().size)
    }

    @Test fun duplicateGuidanceDoesNotAddBroadcastsBeforeTheKeepaliveInterval() {
        maneuver(type = 1)
        route(state = 1, distance = 500)
        val sent = packets().size
        route(state = 1, distance = 500)
        assertEquals(sent, packets().size)
    }

    @Test fun immediateUpdatesStillRespectTheOutputSettingAndDiagnosticHold() {
        BydOutputSettings.setEnabled(app, false)
        maneuver(type = 1)
        route(state = 1, distance = 500)
        assertTrue(packets().isEmpty())

        BydOutputSettings.setEnabled(app, true)
        BydStandaloneHudOutput.syntheticHold = true
        route(state = 1, distance = 250)
        assertTrue(packets().isEmpty())
        BydStandaloneHudOutput.syntheticHold = false
        tick()
        assertEquals(BydStandalonePackets.guidance(2, 0, 250, ""), packets().last())
    }

    private fun packets() = shadowOf(app).broadcastIntents
        .drop(initialBroadcastCount)
        .filter { it.action == "byd.hud.NAVIGATION" }.map { it.getStringExtra("normal") }

    private fun maneuver(type: Int) = BydStandaloneNavigationBridge.onFrame(
        Iap2Frame(BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            tlv(0x01, 0, 0) + tlv(0x03, type)),
    )

    private fun route(state: Int, distance: Int) = BydStandaloneNavigationBridge.onFrame(
        Iap2Frame(BydHudRouteState.ROUTE_GUIDANCE_UPDATE,
            tlv(0x01, state) +
                tlv(0x0a, distance ushr 24, distance ushr 16, distance ushr 8, distance) +
                tlv(0x0d, 0, 0)),
    )

    private fun tlv(type: Int, vararg values: Int): ByteArray {
        val size = values.size + 4
        return byteArrayOf((size ushr 8).toByte(), size.toByte(), (type ushr 8).toByte(), type.toByte()) +
            ByteArray(values.size) { values[it].toByte() }
    }

    private fun setBridgeField(name: String, value: Any?) =
        BydStandaloneNavigationBridge::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(null, value)

    private fun tick() = BydStandaloneNavigationBridge::class.java.getDeclaredMethod("tick")
        .apply { isAccessible = true }.invoke(BydStandaloneNavigationBridge)
}
