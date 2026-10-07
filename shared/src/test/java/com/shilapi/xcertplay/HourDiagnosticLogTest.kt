package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class HourDiagnosticLogTest {
    @Test fun exportsRecentMultilineEntriesAndExcludesOldEntries() {
        val context = RuntimeEnvironment.getApplication()
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        val old = System.currentTimeMillis() - 3_700_000
        File(dir, "hour-${old / 60000}.log").writeText("$old\tEXPIRED\n")
        HourDiagnosticLog.append(dir, "RECENT_ONE\nRECENT_TWO")
        val report = StringBuilder()
        HourDiagnosticLog.appendReport(context, report)
        assertTrue(report.contains("RECENT_ONE"))
        assertTrue(report.contains("RECENT_TWO"))
        assertFalse(report.contains("EXPIRED"))
    }
}
