package com.shilapi.xcertplay

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.Locale

/** Tests select the same category controls used by a driver; no private renderer calls. */
internal fun navigateSteamSettings(activity: DiPlayActivity, section: SteamSettingsSection) {
    val navigation = activity.window.decorView.findViewWithTag<View>("steam_settings_${section.name.lowercase(Locale.ROOT)}")
    requireNotNull(navigation) { "Missing category: $section" }.performClick()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "zh-rCN-w1280dp-h720dp")
class SteamGlassSettingsTest {
    private fun intent() = Intent(RuntimeEnvironment.getApplication(), DiPlayActivity::class.java).putExtra("page", "settings")
    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }
    private fun labels(activity: DiPlayActivity) = views(activity.window.decorView).filterIsInstance<TextView>()
    private fun layout(activity: DiPlayActivity) {
        val decor = activity.window.decorView
        val width = (activity.resources.configuration.screenWidthDp * activity.resources.displayMetrics.density).toInt()
        val height = (activity.resources.configuration.screenHeightDp * activity.resources.displayMetrics.density).toInt()
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, width, height)
    }
    private fun leftInWindow(view: View): Int {
        var x = view.left
        var parent = view.parent
        while (parent is View) { x += parent.left - parent.scrollX; parent = parent.parent }
        return x
    }

    @Test fun categoryChangesKeepTheSameHeaderNavigationAndScrollHost() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, intent()).setup()
        try {
            val activity = controller.get()
            val shell = activity.window.decorView.findViewWithTag<View>("steam_settings_workspace")
            val navigation = activity.window.decorView.findViewWithTag<View>("steam_settings_navigation")
            val scroll = ReflectionHelpers.getField<ScrollView>(activity, "rootScroll")
            navigateSteamSettings(activity, SteamSettingsSection.AUDIO)
            navigateSteamSettings(activity, SteamSettingsSection.LOCATION)
            assertSame(shell, activity.window.decorView.findViewWithTag<View>("steam_settings_workspace"))
            assertSame(navigation, activity.window.decorView.findViewWithTag<View>("steam_settings_navigation"))
            assertSame(scroll, ReflectionHelpers.getField<ScrollView>(activity, "rootScroll"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun optionAndSwitchTitlesShareTheSameStartEdge() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, intent()).setup()
        try {
            val activity = controller.get()
            layout(activity)
            val option = labels(activity).single { it.isClickable && it.text.contains(activity.getString(R.string.resolution)) }
            val toggle = labels(activity).single { it.text.toString() == activity.getString(R.string.efficient_video) }
            assertEquals(leftInWindow(option) + option.paddingLeft, leftInWindow(toggle))
            val intro = activity.window.decorView.findViewWithTag<View>("steam_settings_section_title")
            val cardTitle = labels(activity).single { it.text.toString() == activity.getString(R.string.display_and_performance) }
            assertEquals(leftInWindow(intro), leftInWindow(cardTitle))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun anOlderRestoreCannotMoveTheNextCategory() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, intent()).setup()
        try {
            val activity = controller.get()
            navigateSteamSettings(activity, SteamSettingsSection.LOCATION)
            ReflectionHelpers.setField(activity, "pendingScrollY", 400)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
            navigateSteamSettings(activity, SteamSettingsSection.DISPLAY)
            layout(activity)
            val scroll = ReflectionHelpers.getField<ScrollView>(activity, "rootScroll")
            scroll.computeScroll()
            assertEquals(0, scroll.scrollY)
            assertTrue(activity.window.decorView.findViewWithTag<View>("steam_settings_display").isSelected)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun wideLayoutKeepsCategoriesOutsideTheDetailScroll() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, intent()).setup()
        try {
            val activity = controller.get()
            val navigation = activity.window.decorView.findViewWithTag<View>("steam_settings_navigation")
            val scroll = ReflectionHelpers.getField<ScrollView>(activity, "rootScroll")
            assertFalse(views(scroll).any { it === navigation })
            assertFalse(views(activity.window.decorView).any { it is HorizontalScrollView })
            assertEquals(SteamSettingsSection.entries.size, views(navigation).count { it.tag?.toString()?.startsWith("steam_settings_") == true } - 1)
        } finally { controller.pause().stop().destroy() }
    }

    @Test @Config(qualifiers = "zh-rCN-w393dp-h852dp")
    fun narrowLayoutUsesHorizontalCategoriesAndOneVisibleSection() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, intent()).setup()
        try {
            val activity = controller.get()
            assertEquals(1, views(activity.window.decorView).count { it is HorizontalScrollView })
            assertTrue(labels(activity).any { it.text.toString().contains(activity.getString(R.string.resolution)) })
            assertFalse(labels(activity).any { it.text.toString().contains(activity.getString(R.string.music_buffer)) })
            navigateSteamSettings(activity, SteamSettingsSection.AUDIO)
            assertTrue(labels(activity).any { it.text.toString().contains("100 毫秒") })
            assertFalse(labels(activity).any { it.text.toString().contains(activity.getString(R.string.resolution)) })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun categoryChangesDoNotRewriteSavedPreferences() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, intent()).setup()
        try {
            val activity = controller.get()
            AirPlayPersistence.saveMediaBufferMillis(activity, 500)
            AirPlayPersistence.saveDisplayScaleTenths(activity, 6)
            for (section in SteamSettingsSection.entries) navigateSteamSettings(activity, section)
            assertEquals(500, AirPlayPersistence.loadMediaBufferMillis(activity))
            assertEquals(6, AirPlayPersistence.loadDisplayScaleTenths(activity))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun returningFromExtensionsKeepsTheSelectedCategory() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, intent()).setup()
        try {
            val activity = controller.get()
            navigateSteamSettings(activity, SteamSettingsSection.MORE)
            labels(activity).single { it.text.toString() == activity.getString(R.string.steam_extensions) }.performClick()
            assertEquals("features", ReflectionHelpers.getField<String>(activity, "page"))
            activity.onBackPressedDispatcher.onBackPressed()
            assertEquals("settings", ReflectionHelpers.getField<String>(activity, "page"))
            assertTrue(activity.window.decorView.findViewWithTag<View>("steam_settings_more").isSelected)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun recreationRestoresTheSelectedCategory() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, intent()).setup()
        val state = Bundle()
        try {
            navigateSteamSettings(controller.get(), SteamSettingsSection.AUDIO)
            controller.saveInstanceState(state)
        } finally { controller.pause().stop().destroy() }
        val restored = Robolectric.buildActivity(DiPlayActivity::class.java, intent()).create(state).start().resume().visible()
        try {
            assertTrue(restored.get().window.decorView.findViewWithTag<View>("steam_settings_audio").isSelected)
            assertTrue(labels(restored.get()).any { it.text.toString().contains("100 毫秒") })
        } finally { restored.pause().stop().destroy() }
    }

    @Test @Config(qualifiers = "zh-rCN-w393dp-h852dp")
    fun controlsGrowToFitLargerText() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, intent()).setup()
        try {
            val activity = controller.get()
            navigateSteamSettings(activity, SteamSettingsSection.CONNECTION)
            val toggle = views(activity.window.decorView).filterIsInstance<Switch>().single {
                it.contentDescription == activity.getString(R.string.connect_when_diplay_opens)
            }
            val line = toggle.parent as ViewGroup
            val text = views(line).filterIsInstance<TextView>().first { it !== toggle }
            text.textSize = 36f
            line.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            assertTrue(line.measuredHeight >= text.measuredHeight)
            assertTrue(toggle.measuredHeight >= (48 * activity.resources.displayMetrics.density).toInt())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun leavingTheSiriPageCancelsLearningBeforeAnyOtherKeyCanBeAssigned() {
        WheelSiriSettings.setEnabled(RuntimeEnvironment.getApplication(), true)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, intent()).setup()
        try {
            val activity = controller.get()
            navigateSteamSettings(activity, SteamSettingsSection.VOICE)
            val assign = labels(activity).single { it.isClickable && it.text.toString().contains("点按以更改") }
            assign.performClick()
            assertNotNull(ReflectionHelpers.getField<Any?>(activity, "learntSiriInWindow"))
            navigateSteamSettings(activity, SteamSettingsSection.AUDIO)
            assertNull(ReflectionHelpers.getField<Any?>(activity, "learntSiriInWindow"))
            activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_F1))
            activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_F1))
            assertNull(WheelSiriSettings.key(activity))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun tappingASwitchRowChangesItsPreferenceOnce() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, intent()).setup()
        try {
            val activity = controller.get()
            navigateSteamSettings(activity, SteamSettingsSection.AUDIO)
            val control = views(activity.window.decorView).filterIsInstance<Switch>().single {
                it.contentDescription == activity.getString(R.string.contrib_audio_home_toggle_audio_focus)
            }
            val before = AirPlayPersistence.loadAudioFocusEnabled(activity)
            (control.parent as View).performClick()
            assertEquals(!before, AirPlayPersistence.loadAudioFocusEnabled(activity))
            assertEquals(!before, control.isChecked)
        } finally { controller.pause().stop().destroy() }
    }
}
