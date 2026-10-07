package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class WheelKeyPressesTest {
    @Test fun heldSiriPressKeepsItsReleaseWhenSessionOrSettingsChange() {
        val presses = WheelKeyPresses()
        val key = Triple(1, 304, 1)
        var invocations = 0
        assertEquals(WheelKeyDisposition.CONSUME, presses.filter(key, true, true) { invocations++; WheelKeyDisposition.CONSUME })
        assertEquals(WheelKeyDisposition.CONSUME, presses.filter(key, true, false) { invocations++; WheelKeyDisposition.PASS })
        assertEquals(WheelKeyDisposition.CONSUME, presses.filter(key, false, true) { invocations++; WheelKeyDisposition.PASS })
        assertEquals(1, invocations)
        assertFalse(presses.hasConsumedPress(key))
        assertEquals(WheelKeyDisposition.PASS, presses.filter(key, true, true) { WheelKeyDisposition.PASS })
        assertEquals(WheelKeyDisposition.PASS, presses.filter(key, false, true) { WheelKeyDisposition.CONSUME })
    }
    @Test fun orphanedRepeatDoesNotStartSiriAndDevicesRemainIndependent() {
        val presses = WheelKeyPresses()
        val key = Triple(1, 304, 1)
        assertEquals(WheelKeyDisposition.PASS, presses.filter(key, true, false) { fail("orphan repeat"); WheelKeyDisposition.CONSUME })
        assertEquals(WheelKeyDisposition.CONSUME, presses.filter(key, true, true) { WheelKeyDisposition.CONSUME })
        assertEquals(WheelKeyDisposition.PASS, presses.filter(Triple(2, 304, 1), false, true) { WheelKeyDisposition.CONSUME })
        assertTrue(presses.hasConsumedPress(key))
    }
}
