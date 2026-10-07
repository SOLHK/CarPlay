package com.shilapi.xcertplay

import android.os.Looper
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import java.time.Duration
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "zh-rCN-w1280dp-h720dp")
class SteamConnectionRecoveryTest {
    private lateinit var host: CarPlayHostActivity
    private lateinit var panel: SteamConnectionPanel
    private lateinit var reporter: (CarPlayStatus) -> Unit
    @Before fun setUp() {
        host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        panel = SteamConnectionPanel(host, "Wi-Fi Direct", true, {}, {}, {}, {})
        ReflectionHelpers.setField(host, "steamConnectionPanel", panel)
        CarPlayBackgroundSession::class.java.getDeclaredField("owner").apply { isAccessible = true }
            .set(CarPlayBackgroundSession, host)
        @Suppress("UNCHECKED_CAST")
        reporter = host.javaClass.getDeclaredMethod("createStatusReporter", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(host, 0) as (CarPlayStatus) -> Unit
    }
    @After fun tearDown() {
        ReflectionHelpers.getField<ExecutorService>(host, "teardownExecutor").shutdownNow()
        ReflectionHelpers.getField<ExecutorService>(host, "airPlayCommandExecutor").shutdownNow()
        CarPlayBackgroundSession.clear()
    }

    @Test fun chineseLocaleShowsEachRealConnectionStage() {
        reporter(CarPlayStatus.StartingHotspot)
        assertEquals(host.getString(R.string.steam_stage_wifi), panel.stage.text.toString())
        reporter(CarPlayStatus.ConnectingBluetooth)
        assertEquals(host.getString(R.string.steam_stage_bluetooth), panel.stage.text.toString())
        reporter(CarPlayStatus.WaitingForIphone)
        assertEquals(host.getString(R.string.steam_stage_usb), panel.stage.text.toString())
    }

    @Test fun rejectedCreationStopsTheHostRetryLoopAndShowsRecovery() {
        reporter(CarPlayStatus.Failed("Could not establish WIFI_P2P hotspot: Wi-Fi P2P createGroup failed: busy (code=2)"))
        assertEquals(host.getString(R.string.steam_issue_busy), panel.stage.text.toString())
        assertEquals(host.getString(R.string.open_car_wi_fi_settings), panel.recoveryButton.text.toString())
        assertFalse(ReflectionHelpers.getField<Boolean>(host, "reconnectScheduled"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
        assertEquals(0, ReflectionHelpers.getField<Int>(host, "restartGeneration"))
        assertEquals(host.getString(R.string.steam_issue_busy), panel.stage.text.toString())
    }

    @Test fun aPermanentFailureCancelsAnAlreadyScheduledReconnect() {
        reporter(CarPlayStatus.Failed("temporary transport loss"))
        assertTrue(ReflectionHelpers.getField<Boolean>(host, "reconnectScheduled"))
        reporter(CarPlayStatus.Failed("Turn on Wi-Fi in the head unit's settings, then reconnect"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertFalse(ReflectionHelpers.getField<Boolean>(host, "reconnectScheduled"))
        assertEquals(0, ReflectionHelpers.getField<Int>(host, "restartGeneration"))
        assertEquals(host.getString(R.string.steam_issue_wifi_off), panel.stage.text.toString())
    }

    @Test fun resetRequiredNeverRemovesAGroupWithoutTheExistingConfirmation() {
        reporter(CarPlayStatus.Failed("foreign group", wifiResetRequired = true))
        assertEquals(host.getString(R.string.reset_carplay_wi_fi), panel.recoveryButton.text.toString())
        assertFalse(ReflectionHelpers.getField<Boolean>(host, "reconnectScheduled"))
    }

    @Test fun chosenChannelFailureProvidesAutomaticChannelRecovery() {
        val feedback = SteamConnectionFeedback.from(CarPlayStatus.Failed(
            "Wi-Fi Direct could not use channel 149. Choose Auto or another channel. createGroup failed: busy"))
        assertEquals(SteamRecoveryAction.AUTO_CHANNEL, feedback.action)
        assertFalse(feedback.automaticRetry)
    }

    @Test fun permissionFailureTakesPrecedenceOverTheCreateGroupError() {
        val feedback = SteamConnectionFeedback.from(CarPlayStatus.Failed(
            "Wi-Fi P2P createGroup failed: permission denied (code=3)"))
        assertEquals(SteamRecoveryAction.PERMISSIONS, feedback.action)
        assertFalse(feedback.automaticRetry)
    }

    @Test fun missingHotspotAndUnsupportedDriverOfferDifferentRecovery() {
        assertEquals(SteamRecoveryAction.HOTSPOT, SteamConnectionFeedback.from(
            CarPlayStatus.Failed("The car hotspot is off. Turn it on in the car settings and connect again.")).action)
        assertEquals(SteamRecoveryAction.SETUP, SteamConnectionFeedback.from(
            CarPlayStatus.Failed("Wi-Fi P2P createGroup failed: unsupported (code=1)")).action)
    }

    @Test fun returningHomeAfterFailureShowsTheCauseAndOffersARealReconnect() {
        val stop: ((() -> Unit) -> Unit) = { it() }
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", stop)
        reporter(CarPlayStatus.Failed("Wi-Fi P2P createGroup failed: busy (code=2)"))
        val home = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        home.setTheme(android.R.style.Theme_Material_NoActionBar)
        ReflectionHelpers.callInstanceMethod<Unit>(home, "render")
        val stage = home.window.decorView.findViewWithTag<android.widget.TextView>("steam_home_status")
        val connect = home.window.decorView.findViewWithTag<android.widget.Button>("steam_home_connect")
        assertEquals(home.getString(R.string.steam_issue_busy), stage.text.toString())
        assertEquals(home.getString(R.string.steam_retry_connection), connect.text.toString())
        CarPlayBackgroundSession.clear()
        ReflectionHelpers.callInstanceMethod<Unit>(home, "refreshStatus")
        assertEquals(home.getString(R.string.connect_phone), connect.text.toString())
        assertNotEquals(home.getString(R.string.steam_issue_busy), stage.text.toString())
    }

    @Test fun aFormerOwnerCannotOverwriteTheCurrentConnectionFeedback() {
        val current = SteamConnectionFeedback.from(CarPlayStatus.ConnectingBluetooth)
        CarPlayBackgroundSession.updateFeedback(host, current)
        CarPlayBackgroundSession.updateFeedback(Any(), SteamConnectionFeedback.from(CarPlayStatus.Failed("Turn on Wi-Fi")))
        assertEquals(current, CarPlayBackgroundSession.connectionFeedback)
        CarPlayBackgroundSession.clear()
        assertNull(CarPlayBackgroundSession.connectionFeedback)
    }
}
