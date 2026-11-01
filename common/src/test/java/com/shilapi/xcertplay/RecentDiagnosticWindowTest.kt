package com.shilapi.xcertplay

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class RecentDiagnosticWindowTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun at(value: String): Long = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        .apply { timeZone = utc; isLenient = false }.parse(value)!!.time
    private fun filtered(log: String, now: String = "2026-11-01 14:00:00.000") =
        RecentDiagnosticWindow.filter(log.trimIndent(), at(now), utc)

    @Test fun excludesPriorDaysAndKeepsExactThreeHourBoundary() {
        val result = filtered("""
            Device snapshot
            --- previous.log ---
            DiPlay log started 2026-10-31 12:00:00.000
            12:01:00.000 old-event
            --- diplay.log ---
            DiPlay log started 2026-11-01 10:00:00.000
            10:59:59.999 too-old
            11:00:00.000 boundary
            13:59:59.000 current-event
        """)
        assertTrue(result.contains("Device snapshot"))
        assertTrue(result.contains("2026-11-01 11:00:00.000 boundary"))
        assertTrue(result.contains("current-event"))
        assertFalse(result.contains("old-event"))
        assertFalse(result.contains("too-old"))
        assertFalse(result.contains("--- previous.log ---"))
    }

    @Test fun undatedRotatedRecordsAreNotAssumedToBeToday() {
        val result = filtered("""
            --- previous-7.log ---
            13:55:00.000 ambiguous-old-event
            --- diplay.log ---
            2026-11-01 13:55:00.000 current-event
        """)
        assertFalse(result.contains("ambiguous-old-event"))
        assertTrue(result.contains("undatedOmitted=1"))
        assertTrue(result.contains("current-event"))
    }

    @Test fun legacyMidnightRolloverRetainsTheRealDate() {
        val result = filtered("""
            --- diplay.log ---
            DiPlay log started 2026-10-31 23:59:00.000
            23:59:59.000 before-midnight
            00:00:01.000 after-midnight
        """, "2026-11-01 01:00:00.000")
        assertTrue(result.contains("2026-10-31 23:59:59.000 before-midnight"))
        assertTrue(result.contains("2026-11-01 00:00:01.000 after-midnight"))
    }

    @Test fun minorProducerReorderingDoesNotAdvanceADay() {
        val result = filtered("""
            --- diplay.log ---
            DiPlay log started 2026-11-01 13:00:00.000
            13:00:01.010 first
            13:00:01.001 reordered
        """)
        assertTrue(result.contains("2026-11-01 13:00:01.001 reordered"))
    }

    @Test fun explicitOffsetsAreComparedAsInstantsAndFutureIsExcluded() {
        val result = filtered("""
            --- diplay.log ---
            2026-11-01 21:30:00.000 +0800 singapore
            2026-11-01 13:30:00.000 +0000 utc
            2026-11-01 14:00:00.001 +0000 future-event
            2026-10-30 13:30:00.000 +0000 past-event
        """)
        assertTrue(result.contains("singapore"))
        assertTrue(result.contains("utc"))
        assertFalse(result.contains("future-event"))
        assertFalse(result.contains("past-event"))
    }

    @Test fun continuationBelongsOnlyToAnIncludedTimestamp() {
        val result = filtered("""
            --- diplay.log ---
            2026-11-01 10:00:00.000 old-error
                at old.Stack
            2026-11-01 13:00:00.000 current-error
                at current.Stack
        """)
        assertFalse(result.contains("old.Stack"))
        assertTrue(result.contains("current.Stack"))
    }

    @Test fun invalidDatesDoNotLeakOrCrash() {
        val result = filtered("""
            --- diplay.log ---
            2026-13-01 13:00:00.000 invalid-month
            99:00:00.000 invalid-hour
            2026-11-01 13:00:00.000 good
        """)
        assertFalse(result.contains("invalid-month"))
        assertFalse(result.contains("invalid-hour"))
        assertTrue(result.contains("good"))
    }

    @Test fun reportsWithoutLogSectionsRemainUnchanged() {
        assertEquals("simple report", RecentDiagnosticWindow.filter("simple report", 0, utc))
    }

    @Test fun repeatedFilteringDoesNotDuplicateWindowOrResurrectOldData() {
        val once = filtered("""
            --- diplay.log ---
            2026-10-31 13:00:00.000 obsolete
            2026-11-01 13:00:00.000 retained
        """)
        val twice = RecentDiagnosticWindow.filter(once, at("2026-11-01 14:00:00.000"), utc)
        assertEquals(1, twice.lineSequence().count { it.startsWith("Log window:") })
        assertFalse(twice.contains("obsolete"))
        assertTrue(twice.contains("retained"))
    }
}
