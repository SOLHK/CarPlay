package com.shilapi.xcertplay.media

import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayTouchMapperTest {
    private val content = CarPlayVideoLayout.fit(1920, 990, 1920, 942)

    @org.junit.Before fun resetContacts() { contact(MotionEvent.ACTION_CANCEL, 0f, 0f) }

    @Test fun contentCornersMapToCanvasCorners() {
        contact(MotionEvent.ACTION_DOWN, content.left, content.top).let {
            assertEquals(0.0, it.x, 1e-6)
            assertEquals(0.0, it.y, 1e-6)
            assertTrue(it.down)
        }
        contact(MotionEvent.ACTION_UP, content.left + content.width, content.top + content.height).let {
            assertEquals(1.0, it.x, 1e-6)
            assertEquals(1.0, it.y, 1e-6)
            assertFalse(it.down)
        }
    }

    @Test fun centreRemainsCentred() {
        contact(MotionEvent.ACTION_MOVE, 960f, 471f).let {
            assertEquals(0.5, it.x, 1e-6)
            assertEquals(0.5, it.y, 1e-6)
        }
    }

    @Test fun dragIntoABarClampsToTheCanvasEdge() {
        assertEquals(0.0, contact(MotionEvent.ACTION_MOVE, 0f, 471f).x, 1e-6)
        assertEquals(1.0, contact(MotionEvent.ACTION_MOVE, 1920f, 471f).x, 1e-6)
    }

    @Test fun cancelInABarReleasesTheContact() {
        assertFalse(contact(MotionEvent.ACTION_CANCEL, 0f, 471f).down)
    }

    @Test fun fullViewOverloadKeepsExistingCoordinates() {
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 480f, 247.5f, 0)
        try {
            val contact = CarPlayTouchMapper.contacts(event, 1920, 990).first()
            assertEquals(0.25, contact.x, 1e-6)
            assertEquals(0.25, contact.y, 1e-6)
        } finally {
            event.recycle()
        }
    }

    @Test fun reorderedAndroidPointersKeepTheirCarPlaySlots() {
        fun send(action: Int, vararg points: Pair<Int, Float>) = MotionEvent.obtain(
            0, 1, action, points.size,
            points.map { (id, _) -> MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray(),
            points.map { (_, x) -> MotionEvent.PointerCoords().apply { this.x = x; y = 471f; pressure = 1f; size = 1f } }.toTypedArray(),
            0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0,
        ).let { event -> try { CarPlayTouchMapper.contacts(event, content) } finally { event.recycle() } }
        val left = content.left + content.width * 0.25f
        val right = content.left + content.width * 0.75f
        send(MotionEvent.ACTION_DOWN, 7 to left)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 7 to left, 42 to right)
        val reordered = send(MotionEvent.ACTION_MOVE, 42 to right, 7 to left)
        assertEquals(2, reordered.size)
        assertEquals(0.25, reordered[0].x, 1e-6)
        assertEquals(0.75, reordered[1].x, 1e-6)
        assertTrue(reordered.all { it.down })
        val released = send(MotionEvent.ACTION_POINTER_UP, 42 to right, 7 to left)
        assertTrue(released[0].down)
        assertFalse(released[1].down)
        val cancelled = send(MotionEvent.ACTION_CANCEL, 7 to left)
        assertTrue(cancelled.none { it.down })
    }

    private fun contact(action: Int, x: Float, y: Float) = MotionEvent.obtain(0, 0, action, x, y, 0).let {
        try {
            CarPlayTouchMapper.contacts(it, content).first()
        } finally {
            it.recycle()
        }
    }
}
