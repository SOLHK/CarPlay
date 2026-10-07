package com.shilapi.xcertplay

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.shilapi.xcertplay.host.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class CarPlayCustomizationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Test fun homeKeepsTheOwnersBrandAndBoundedTitleSize() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        try {
            val views = texts(controller.get().window.decorView).toList()
            assertTrue(views.any { it.text.toString() == "CarPlay" })
            assertFalse(views.any { it.text.contains("DiPlay") })
            assertTrue(views.filter { it.text.toString() == "CarPlay" }.all { it.textSize <= 42f })
            val root = controller.get().window.decorView
            val usb = root.findViewWithTag<View>("steam_home_usb")
            val mode = root.findViewWithTag<View>("steam_home_mode")
            val phone = root.findViewWithTag<View>("steam_home_phone")
            assertNotNull(usb); assertNotNull(mode); assertNotNull(phone)
            assertSame(usb.parent, mode.parent)
            assertSame(usb.parent, phone.parent)
            assertNotNull(root.findViewWithTag<View>("steam_home_settings"))
            assertEquals("Steam", AirPlayPersistence.loadManufacturer(context))
            assertEquals("CarPlay", AirPlayPersistence.loadModel(context))
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun settingsKeepThe100msLabelAndHideRemovedDrivingSide() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
        try {
            val activity = controller.get()
            navigateSteamSettings(activity, SteamSettingsSection.AUDIO)
            assertTrue(texts(activity.window.decorView).any { it.text.contains("100 毫秒") })
            navigateSteamSettings(activity, SteamSettingsSection.MORE)
            assertTrue(texts(activity.window.decorView).any { it.text.toString() == "手机接收与上游扩展" })
            navigateSteamSettings(activity, SteamSettingsSection.LOCATION)
            assertTrue(texts(activity.window.decorView).any { it.text.contains("接收设备GPS") })
            for (section in SteamSettingsSection.entries) {
                navigateSteamSettings(activity, section)
                assertFalse(texts(activity.window.decorView).any { it.text.toString() == activity.getString(R.string.right_hand_drive) })
            }
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun eachBufferLabelSavesTheSameNumericValue() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
        try {
            val activity = controller.get()
            navigateSteamSettings(activity, SteamSettingsSection.AUDIO)
            val control = texts(activity.window.decorView).single { it.text.contains(activity.getString(R.string.music_buffer)) && it.isClickable }
            control.performClick()
            val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
            assertEquals(4, dialog.listView.adapter.count)
            assertTrue(dialog.listView.adapter.getItem(2).toString().contains("500"))
            dialog.listView.performItemClick(null, 2, 2)
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(500, AirPlayPersistence.loadMediaBufferMillis(context))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun profileMigrationReplacesUpstreamIdentityOnlyOnce() {
        AirPlayPersistence.saveManufacturer(context, "DiPlay")
        AirPlayPersistence.saveModel(context, "DiPlay")
        AirPlayPersistence.saveDisplayScaleTenths(context, 10)
        CarPlayProfileDefaults.apply(context)
        assertEquals("Steam", AirPlayPersistence.loadManufacturer(context))
        assertEquals("CarPlay", AirPlayPersistence.loadModel(context))
        assertEquals(8, AirPlayPersistence.loadDisplayScaleTenths(context))
        AirPlayPersistence.saveManufacturer(context, "My receiver")
        AirPlayPersistence.saveDisplayScaleTenths(context, 10)
        CarPlayProfileDefaults.apply(context)
        assertEquals("My receiver", AirPlayPersistence.loadManufacturer(context))
        assertEquals(10, AirPlayPersistence.loadDisplayScaleTenths(context))
    }
    private fun texts(view: View): Sequence<TextView> = sequence {
        if (view is TextView) yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(texts(view.getChildAt(i)))
    }
}
