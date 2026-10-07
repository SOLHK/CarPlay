package com.shilapi.xcertplay

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class WheelSiriWindowLearningTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @After fun cleanup() { WheelSiriSettings.setEnabled(app, false) }
    @Test fun windowLearningSavesOnePressAndConsumesItsRepeatAndRelease() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        WheelSiriSettings.setEnabled(app, true)
        val learnt = mutableListOf<WheelKey>()
        val done: (WheelKey) -> Unit = { learnt += it }
        ReflectionHelpers.setField(activity, "learntSiriInWindow", done)
        val down = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP, 0)
        assertTrue(activity.dispatchKeyEvent(down))
        assertTrue(activity.dispatchKeyEvent(KeyEvent(0, 1, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP, 1)))
        assertTrue(activity.dispatchKeyEvent(KeyEvent(0, 2, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP, 0)))
        assertEquals(listOf(WheelKey.of(down)), learnt)
        assertEquals(learnt.single(), WheelSiriSettings.key(app))
    }
    @Test fun cancelLearningClearsCallbacksAndRestoresTheButtonOnce() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        var cancelled = 0
        val callback: () -> Unit = { cancelled++ }
        val done: (WheelKey) -> Unit = { fail("cancelled learning") }
        ReflectionHelpers.setField(activity, "learntSiriInWindow", done)
        ReflectionHelpers.setField(activity, "siriLearningCancelled", callback)
        repeat(2) { ReflectionHelpers.callInstanceMethod<Unit>(activity, "cancelSiriLearning") }
        assertEquals(1, cancelled)
        assertNull(ReflectionHelpers.getField<Any?>(activity, "learntSiriInWindow"))
    }
}
