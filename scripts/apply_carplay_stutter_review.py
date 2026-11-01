#!/usr/bin/env python3
"""Integrate the log-cross-check repairs after apply_carplay_stutter_fix.py; exact anchors only."""
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
def change(path, old, new):
    p = ROOT / path
    s = p.read_text()
    if s.count(old) != 1:
        raise RuntimeError(f'{path}: expected one exact reviewed anchor, got {s.count(old)}')
    p.write_text(s.replace(old, new))
def add_test(path, body):
    p=ROOT/path; s=p.read_text(); pos=s.index('    @Test'); p.write_text(s[:pos]+body+'\n'+s[pos:])
COMMON='common/src/main/java/com/shilapi/xcertplay/'
TEST='common/src/test/java/com/shilapi/xcertplay/'
SINK='shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt'
change(COMMON+'CarPlayHostActivity.kt', '''            onAudioDiagnostic = { message ->
                if (message.startsWith("Microphone: ")) {
                    AsyncDiagnosticLog.append(diagnosticLog, message)
                } else {
                    diagnosticLog?.append(formattedLogLine(message, System.currentTimeMillis()))
                }
            },''', '''            // Every audio diagnostic uses the captured session target. Ordinary music/voice
            // statistics used to perform synchronous disk I/O directly on the audio worker.
            onAudioDiagnostic = { message -> AsyncDiagnosticLog.append(diagnosticLog, message) },''')
change(SINK, '''        if (bufferProgress.shouldRebuffer(usesMusicBuffer(), playbackStarted,
                track.underrunCount > underrunsAtPlaybackStart, queue.isEmpty(), track.playbackHeadPosition,
                startThresholdBytes / 2L)) {''', '''        // underrunCount is cumulative: a previous, already recovered underrun is not
        // permission to pause a currently draining tail (the field log retained 73 ms).
        // No extra audio is buffered: empty hardware still recovers immediately. For
        // retained PCM, allow four existing poll ticks for the platform head to advance.
        val retainedAudioIsAdvancing = bufferProgress.queuedBytes(track.playbackHeadPosition) > 0L &&
            nowNs - lastPlaybackProgressNs < AUDIO_POLL_MILLIS * 4_000_000L
        if (!retainedAudioIsAdvancing && bufferProgress.shouldRebuffer(usesMusicBuffer(), playbackStarted,
                track.underrunCount > underrunsAtPlaybackStart, queue.isEmpty(), track.playbackHeadPosition,
                startThresholdBytes / 2L)) {''')
change(COMMON+'RecentDiagnosticWindow.kt',
       'section = line; emitted = false; day = null; lastTod = null',
       '''// REPORT_NAMES is chronological. Size rotation has no new session header;
                // carry an already established date into the next segment, never invent one.
                section = line; emitted = false''')
change(COMMON+'RecentDiagnosticWindow.kt',
       '                section = null; includeContinuation = false',
       '                section = null; includeContinuation = false; day = null; lastTod = null')
change(COMMON+'RecentDiagnosticWindow.kt',
       '            val source = if (line.startsWith(START)) line.removePrefix(START) else line',
       '''            // A malformed explicit restart cannot inherit the previous session's day.
            if (line.startsWith(START)) { day = null; lastTod = null }
            val source = if (line.startsWith(START)) line.removePrefix(START) else line''')
change(COMMON+'DiPlayActivity.kt',
       '    private fun diagnosticReportText(appContext: Context): String = buildString {',
       '''    private fun diagnosticReportText(appContext: Context): String {
        // Called only by the export/backup worker, never by transport or UI producers.
        val drained = AsyncDiagnosticLog.awaitIdle(1_000L)
        return RecentDiagnosticWindow.filter(buildDiagnosticReportText(appContext, drained))
    }

    private fun buildDiagnosticReportText(appContext: Context, drained: Boolean): String = buildString {''')
change(COMMON+'DiPlayActivity.kt',
       '        appendLine("CarPlay ${version()} · diagnostic report")',
       '''        appendLine("CarPlay ${version()} · diagnostic report")
        appendLine("Diagnostic pipeline: async-audio-v2; logWindowHours=3; pendingWriterDrained=$drained")''')
# Wait for asynchronous completion before checking exactly the same expected evidence.
p=ROOT/(TEST+'CarPlayHostThemeDiagnosticsTest.kt'); s=p.read_text().replace('logFile.readText()', 'savedLog()')
s=s.replace('    private fun refresh(', '''    private fun savedLog(): String {
        assertTrue("Diagnostic writer must finish before saved evidence is read", AsyncDiagnosticLog.awaitIdle(2_000L))
        return logFile.readText()
    }

    private fun refresh('''); p.write_text(s)
change(TEST+'CarPlayHostThemeDiagnosticsTest.kt', '    @After fun tearDown() {',
       '    @After fun tearDown() {\n        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000L))')
change(TEST+'CarPlayVpnConsentTest.kt',
       '    private fun log() = File(activity.filesDir, "logs/diplay.log").readText()',
       '''    private fun log(): String {
        assertTrue("Diagnostic writer must finish before saved evidence is read", AsyncDiagnosticLog.awaitIdle(2_000L))
        return File(activity.filesDir, "logs/diplay.log").readText()
    }''')
change(TEST+'CarPlayVpnConsentTest.kt', '        awaitAdbConsent()\n        ReflectionHelpers.getField<AtomicBoolean>',
       '''        awaitAdbConsent()
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000L))
        ReflectionHelpers.getField<SessionLogFile?>(activity, "sessionLog")?.close()
        ReflectionHelpers.getField<AtomicBoolean>''')
change(TEST+'WirelessStartupRecoveryTest.kt', '        assertTrue(log.readText(), log.readText().contains(',
       '''        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000L))
        assertTrue(log.readText(), log.readText().contains(''')
change(TEST+'WirelessStartupRecoveryTest.kt', '        return Retries(log.readLines().map',
       '''        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000L))
        return Retries(log.readLines().map''')
change(TEST+'WirelessStartupRecoveryTest.kt', '    @After fun cleanup() {',
       '''    @After fun cleanup() {
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000L))
        ReflectionHelpers.getField<SessionLogFile?>(activity, "sessionLog")?.close()''')
add_test(TEST+'RecentDiagnosticWindowTest.kt', '''    @Test fun chronologicalRotationCarriesTheSessionDateIntoHeaderlessSegments() {
        val result = filtered("""
            --- previous-3.log ---
            DiPlay log started 2026-11-01 12:05:41.101
            12:10:47.194 first-segment
            --- previous-2.log ---
            12:30:00.000 second-segment
            --- previous.log ---
            13:00:00.000 third-segment
            --- diplay.log ---
            13:59:59.000 newest-segment
        """)
        for (event in listOf("first-segment", "second-segment", "third-segment", "newest-segment")) {
            assertTrue("Missing size-rotated evidence: $event", result.contains(event))
        }
        assertTrue(result.contains("2026-11-01 13:59:59.000 newest-segment"))
        assertTrue(result.contains("undatedOmitted=0"))
    }

    @Test fun midnightRotationAndExplicitRestartsDoNotMixSessionDates() {
        val result = filtered("""
            --- previous-2.log ---
            DiPlay log started 2026-10-31 23:58:00.000
            23:59:59.000 before
            --- previous.log ---
            00:00:01.000 after
            --- diplay.log ---
            DiPlay log started 2026-11-01 00:30:00.000
            00:30:01.000 restarted
        """, "2026-11-01 01:00:00.000")
        assertTrue(result.contains("2026-10-31 23:59:59.000 before"))
        assertTrue(result.contains("2026-11-01 00:00:01.000 after"))
        assertTrue(result.contains("2026-11-01 00:30:01.000 restarted"))
    }

    @Test fun malformedExplicitRestartDoesNotBorrowAnEarlierDate() {
        val result = filtered("""
            --- previous.log ---
            DiPlay log started 2026-11-01 12:00:00.000
            12:01:00.000 known
            --- diplay.log ---
            DiPlay log started invalid
            12:30:00.000 ambiguous
        """)
        assertTrue(result.contains("known"))
        assertFalse(result.contains("ambiguous"))
    }
''')
add_test('shared/src/test/java/com/shilapi/xcertplay/media/AudioRendererLatencyTest.kt', '''    @Test fun recoveredUnderrunCannotPauseSeventyThreeMillisecondsOfMovingMusic() {
        BoundedTrack.capacity = 32_640
        withRenderer(48_000, 2, 28_800) { renderer ->
            set(renderer, "mappedChannel", AudioChannel.MEDIA)
            set(renderer, "playbackStarted", true)
            BoundedTrack.playing = true
            BoundedTrack.underruns = 1
            BoundedTrack.head = 1_000
            val progress = field("bufferProgress").get(renderer) as AudioBufferProgress
            progress.written(4_000 + 14_016) // 73 ms remains at 48 kHz stereo.
            method("maintainPlaybackBuffer").invoke(renderer)
            assertEquals(0, BoundedTrack.pauses)
            assertTrue(BoundedTrack.playing)
            assertEquals(14_016L, progress.queuedBytes(BoundedTrack.head))

            // Real starvation is still handled immediately; no larger start buffer or PCM loss.
            BoundedTrack.head = 4_504
            method("maintainPlaybackBuffer").invoke(renderer)
            assertEquals(1, BoundedTrack.pauses)
            assertFalse(BoundedTrack.playing)
            assertEquals(0, BoundedTrack.flushes)
            assertEquals(28_800, field("startThresholdBytes").get(renderer))
        }
    }
''')
print('Cross-log fixes integrated: async audio callback, moving-tail recovery guard, complete rolling-window export, unchanged test assertions.')
