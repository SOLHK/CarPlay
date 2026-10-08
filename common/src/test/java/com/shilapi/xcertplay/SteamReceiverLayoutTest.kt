package com.shilapi.xcertplay

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "zh-rCN-w1280dp-h720dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SteamReceiverLayoutTest {
    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }
    private fun measure(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }
    private fun screenshot(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val output = File("../validation/ui-0.2.33/$name.png")
        requireNotNull(output.parentFile).mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
    private fun page(name: String, block: (DiPlayActivity, View) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        // The library test manifest has no application version; use the release fixture for previews.
        shadowOf(app.packageManager).installPackage(app.packageManager.getPackageInfo(app.packageName, 0).apply { versionName = "0.2.33" })
        val intent = Intent(app, DiPlayActivity::class.java).putExtra("page", name)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            ReflectionHelpers.setField(activity, "setupError", null)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
            val root = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            measure(root, activity.resources.configuration.screenWidthDp, activity.resources.configuration.screenHeightDp)
            block(activity, root)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun homeHasNewLayoutAndThreeAlignedShortcuts() = page("home") { activity, root ->
        val shortcuts = root.findViewWithTag<ViewGroup>("steam_home_shortcuts")
        assertEquals(3, shortcuts.childCount)
        val first = shortcuts.getChildAt(0)
        for (i in 1..2) {
            assertEquals(first.top, shortcuts.getChildAt(i).top)
            assertEquals(first.bottom, shortcuts.getChildAt(i).bottom)
            assertTrue(kotlin.math.abs(first.width - shortcuts.getChildAt(i).width) <= 1)
        }
        assertFalse(views(root).filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.a_familiar_drive) })
        assertNotNull(root.findViewWithTag<View>("steam_home_deck"))
        screenshot(root, "home-wide")
    }

    @Test @Config(qualifiers = "zh-rCN-w393dp-h852dp-mdpi")
    fun narrowHomeStacksItsShortcutsAndKeepsTextWithinBounds() = page("home") { _, root ->
        val settings = root.findViewWithTag<View>("steam_home_settings")
        val carHome = root.findViewWithTag<View>("steam_home_carhome")
        assertEquals(settings.top, carHome.top)
        assertEquals(settings.bottom, carHome.bottom)
        val shortcuts = root.findViewWithTag<ViewGroup>("steam_home_shortcuts")
        assertTrue(shortcuts.getChildAt(1).top >= shortcuts.getChildAt(0).bottom)
        for (label in views(root).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE }) {
            assertTrue(label.text.toString(), label.width > label.paddingLeft + label.paddingRight)
            assertTrue(label.text.toString(), label.layout == null || label.layout.height <= label.height - label.paddingTop - label.paddingBottom)
        }
        screenshot(root, "home-narrow")
    }

    @Test fun connectionMethodCanSwitchBackToWifiDirectWithoutErasingHotspotDetails() {
        val app = RuntimeEnvironment.getApplication()
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveManualHotspotSsid(app, "Test car")
        AirPlayPersistence.saveManualHotspotPassphrase(app, "test-password")
        page("connection") { activity, root ->
            root.findViewWithTag<View>("steam_mode_wifi_p2p").performClick()
            assertEquals(WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(activity))
            assertEquals("Test car", AirPlayPersistence.loadManualHotspotSsid(activity))
            assertEquals("test-password", AirPlayPersistence.loadManualHotspotPassphrase(activity))
            val updated = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            measure(updated, 1280, 720)
            screenshot(updated, "connection-setup-wide")
        }
    }

    @Test fun waitingPageKeepsRecoveryActionsBelowTheErrorAndCallsTheRightAction() {
        val app = RuntimeEnvironment.getApplication()
        var selected: SteamRecoveryAction? = null
        val panel = SteamConnectionPanel(app, "Wi-Fi Direct", true, {}, {}, {}, { selected = it })
        panel.show(SteamConnectionFeedback.from(CarPlayStatus.Failed("Wi-Fi P2P createGroup failed: busy (code=2)")))
        measure(panel, 1280, 720)
        assertTrue(panel.recoveryButton.top >= panel.hint.bottom)
        assertEquals(app.getString(R.string.steam_issue_busy), panel.stage.text.toString())
        panel.recoveryButton.performClick()
        assertEquals(SteamRecoveryAction.WIFI, selected)
        screenshot(panel, "connection-error-wide")
        panel.show(SteamConnectionFeedback.from(CarPlayStatus.ConnectingBluetooth))
        measure(panel, 1280, 720)
        assertEquals(View.GONE, panel.recoveryButton.visibility)
        screenshot(panel, "connection-waiting-wide")
    }

    @Test @Config(qualifiers = "zh-rCN-w393dp-h852dp-mdpi")
    fun longErrorAndLargerTextStayScrollableOnNarrowScreens() {
        val app = RuntimeEnvironment.getApplication()
        val panel = SteamConnectionPanel(app, "Wi-Fi Direct", true, {}, {}, {}, {})
        panel.show(SteamConnectionFeedback.from(CarPlayStatus.Failed("Wi-Fi P2P createGroup failed: busy (code=2)")))
        panel.stage.textSize = 34f; panel.hint.textSize = 24f
        measure(panel, 393, 852)
        assertTrue(panel.stage.layout.height <= panel.stage.height - panel.stage.paddingTop - panel.stage.paddingBottom)
        assertTrue(panel.hint.layout.height <= panel.hint.height - panel.hint.paddingTop - panel.hint.paddingBottom)
        assertTrue(panel.recoveryButton.top >= panel.hint.bottom)
        assertTrue(panel.getChildAt(0).height > panel.height)
        screenshot(panel, "connection-error-narrow-large-text")
    }

    @Test fun allSettingsAndChildPagesUseTheSameGlassFamily() {
        page("settings") { _, root -> screenshot(root, "settings-wide") }
        page("settings") { activity, root ->
            navigateSteamSettings(activity, SteamSettingsSection.AUDIO)
            measure(root, 1280, 720)
            screenshot(root, "audio-wide")
            navigateSteamSettings(activity, SteamSettingsSection.VOICE)
            measure(root, 1280, 720)
            screenshot(root, "voice-wide")
        }
        page("features") { _, root -> screenshot(root, "extensions-wide") }
        page("about") { _, root -> screenshot(root, "about-wide") }
    }

    @Test @Config(qualifiers = "zh-rCN-w393dp-h852dp-mdpi")
    fun narrowSettingsKeepEveryCategoryReachable() = page("settings") { activity, root ->
        navigateSteamSettings(activity, SteamSettingsSection.VOICE)
        measure(root, 393, 852)
        assertTrue(root.findViewWithTag<View>("steam_settings_voice").isSelected)
        assertTrue(views(root).filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.wheel_siri_key) })
        screenshot(root, "settings-narrow")
    }

    @Test fun wideConnectionPageShowsTheMainActionBesideTheSetup() = page("connection") { _, root ->
        val connect = root.findViewWithTag<View>("steam_setup_connect")
        val location = IntArray(2)
        connect.getLocationOnScreen(location)
        assertTrue(location[0] > root.width / 2)
        assertTrue(location[1] + connect.height <= root.height)
        screenshot(root, "connection-setup-wide")
    }

    @Test @Config(qualifiers = "zh-rCN-w1280dp-h720dp-mdpi")
    fun largeSystemFontsUseOneDetailColumnAndKeepTextUnclipped() {
        org.robolectric.RuntimeEnvironment.setFontScale(1.5f)
        page("settings") { activity, root ->
            assertNull(root.findViewWithTag<View>("steam_settings_columns"))
            navigateSteamSettings(activity, SteamSettingsSection.VOICE)
            measure(root, 1280, 720)
            assertNull(root.findViewWithTag<View>("steam_settings_columns"))
            for (label in views(root).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE }) {
                assertTrue(label.text.toString(), label.width > label.paddingLeft + label.paddingRight)
                assertTrue(label.text.toString(), label.layout == null || label.layout.height <= label.height - label.paddingTop - label.paddingBottom)
            }
            screenshot(root, "settings-large-text")
        }
    }
}
