#!/usr/bin/env python3
"""Preserve the existing diagnostic header contract while adding window metadata."""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def change(path,old,new):
 p=ROOT/path;s=p.read_text()
 if s.count(old)!=1:raise RuntimeError(f'{path}: expected one reviewed anchor')
 p.write_text(s.replace(old,new))
C='common/src/main/java/com/shilapi/xcertplay/'
T='common/src/test/java/com/shilapi/xcertplay/'
change(C+'DiPlayActivity.kt',
 '        appendLine("Diagnostic pipeline: async-audio-v2; logWindowHours=3; pendingWriterDrained=$drained")\n', '')
change(C+'DiPlayActivity.kt',
 '        appendLine("Head-unit board: ${Build.BOARD}; hardware: ${Build.HARDWARE}; build: ${Build.DISPLAY}")',
 r'''        appendLine("Head-unit board: ${Build.BOARD}; hardware: ${Build.HARDWARE}; build: ${Build.DISPLAY}")
        appendLine("Diagnostic pipeline: async-audio-v2; logWindowHours=3; pendingWriterDrained=$drained")''')
change(C+'RecentDiagnosticWindow.kt', '        var emitted = false',
 '        var emitted = false\n        var firstLogOffset: Int? = null')
change(C+'RecentDiagnosticWindow.kt',
 r'''            if (!emitted) { output.append(section).append('\n'); emitted = true }''',
 r'''            if (!emitted) {
                if (firstLogOffset == null) firstLogOffset = output.length
                output.append(section).append('\n'); emitted = true
            }''')
change(C+'RecentDiagnosticWindow.kt',
 r'''        return summary + output.toString().trimEnd() + "\n"''',
 r'''        val body = output.toString().trimEnd() + "\n"
        // Version, last problem and Android version retain their established first three
        // lines. Put window metadata beside the log, not ahead of the report header.
        val insertion = firstLogOffset ?: body.length
        return body.substring(0, insertion) + summary + body.substring(insertion)''')
p=ROOT/(T+'RecentDiagnosticWindowTest.kt');s=p.read_text();pos=s.index('    @Test')
s=s[:pos]+'''    @Test fun windowSummaryPreservesTheFirstThreeReportLines() {
        val header = "CarPlay 0.2.38 · diagnostic report\\nLast problem: none recorded\\nAndroid 12 / API 32"
        val result = filtered(header + "\\n--- diplay.log ---\\n2026-11-01 13:00:00.000 retained")
        assertEquals(header.lines(), result.lines().take(3))
        assertTrue(result.indexOf("Log window:") > result.indexOf("Android 12"))
        assertTrue(result.indexOf("Log window:") < result.indexOf("--- diplay.log ---"))
        val twice = RecentDiagnosticWindow.filter(result, at("2026-11-01 14:00:00.000"), utc)
        assertEquals(header.lines(), twice.lines().take(3))
        assertEquals(1, twice.lineSequence().count { it.startsWith("Log window:") })
    }

    @Test fun anEmptyWindowStillPreservesTheReportHeader() {
        val header = "CarPlay 0.2.38 · diagnostic report\\nLast problem: none recorded\\nAndroid 12 / API 32"
        val result = filtered(header + "\\n--- diplay.log ---\\n2026-10-01 13:00:00.000 old")
        assertEquals(header.lines(), result.lines().take(3))
        assertTrue(result.contains("available=none"))
        assertFalse(result.contains("--- diplay.log ---"))
    }

'''+s[pos:];p.write_text(s)
print('Diagnostic report header preserved; no existing test assertions changed.')
