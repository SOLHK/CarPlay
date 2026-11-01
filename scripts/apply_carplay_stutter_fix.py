#!/usr/bin/env python3
"""One-shot, fail-closed integration for the reviewed 0.2.37 base. Removed after validation."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
def change(path, old, new, expected_blob=None):
    file = ROOT / path
    if expected_blob:
        actual = subprocess.check_output(['git', 'hash-object', str(file)], text=True).strip()
        if actual != expected_blob:
            raise RuntimeError(f'{path}: source changed; refusing to overwrite it')
    source = file.read_text()
    if source.count(old) != 1:
        raise RuntimeError(f'{path}: expected exactly one reviewed anchor')
    file.write_text(source.replace(old, new))

SINK = 'shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt'
change(SINK, '        var written = 0\n        while (written < length && running) {',
       '        var written = 0\n        var consecutiveZeroWrites = 0\n        while (written < length && running) {',
       'ff5cb3a425003942a4e0a20d4b6fe4b311e00a59')
change(SINK, '''            if (count == 0) {
                zeroWritesThisWindow++
                Thread.sleep(AUDIO_WRITE_RETRY_MILLIS)
                continue
            }
            if (count < writeLength) partialWritesThisWindow++''', '''            if (count == 0) {
                zeroWritesThisWindow++
                consecutiveZeroWrites = (consecutiveZeroWrites + 1).coerceAtMost(3)
                val queuedBytes = bufferProgress.queuedBytes(track.playbackHeadPosition)
                // A full device queue needs time to drain, not another 500 Hz JNI poll.
                // This is bounded by audible reserve; startup, PCM and buffer presets are unchanged.
                Thread.sleep(AudioWriteBackoff.delayMillis(consecutiveZeroWrites,
                    queuedBytes, bytesPerSecond, playbackStarted))
                continue
            }
            consecutiveZeroWrites = 0
            if (count < writeLength) partialWritesThisWindow++''')
change(SINK, '        const val AUDIO_WRITE_RETRY_MILLIS = 2L\n', '')

EXPORT = 'common/src/main/java/com/shilapi/xcertplay/DiagnosticExportStore.kt'
change(EXPORT,
       '    fun saveWithoutPicker(context: Context, fileName: String, report: String): SavedReport {',
       '''    fun saveWithoutPicker(context: Context, fileName: String, report: String): SavedReport =
        saveRecentWithoutPicker(context, fileName, RecentDiagnosticWindow.filter(report))

    private fun saveRecentWithoutPicker(context: Context, fileName: String, report: String): SavedReport {''',
       '1bdc4e15559e76c3e1a6b191da63d076be359669')
change(EXPORT, '        stream.bufferedWriter(Charsets.UTF_8).use { it.write(report) }',
       '        stream.bufferedWriter(Charsets.UTF_8).use { it.write(RecentDiagnosticWindow.filter(report)) }')

TEST = 'shared/src/test/java/com/shilapi/xcertplay/media/AudioRendererLatencyTest.kt'
anchor = '    @Test\n    fun fullEnhancedMusicQueueBackpressuresInsteadOfDroppingFrames()'
change(TEST, anchor, '''    @Test(timeout = 5_000)
    fun repeatedFullTrackRetriesPreserveEveryByteAndNeverRestartPlayback() {
        withRenderer(48_000, 2, 2048) { renderer ->
            set(renderer, "playbackStarted", true)
            set(renderer, "fadeApplied", true)
            BoundedTrack.playing = true
            (field("bufferProgress").get(renderer) as AudioBufferProgress).written(4096)
            BoundedTrack.onWrite = { call, size -> if (call <= 12) 0 else size }
            val pcm = ByteArray(96) { it.toByte() }
            write(renderer, pcm)
            assertArrayEquals(pcm, BoundedTrack.accepted.toByteArray())
            assertEquals(13, BoundedTrack.calls)
            assertEquals(12, field("zeroWritesThisWindow").get(renderer))
            assertEquals(0, BoundedTrack.plays)
            assertEquals(0, BoundedTrack.flushes)
        }
    }

''' + anchor)
HOST = 'common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt'
change(HOST, '''    private fun appendLog(message: String) {
        val safe = DiagnosticRedactor.redact(message) ?: return
        sessionLog?.append(formattedLogLine(safe, System.currentTimeMillis()))
    }

    private fun appendFileLog(message: String) {
        sessionLog?.append(formattedLogLine(message, System.currentTimeMillis()))
    }''', '''    private fun appendLog(message: String) {
        AsyncDiagnosticLog.append(sessionLog, message)
    }

    private fun appendFileLog(message: String) {
        AsyncDiagnosticLog.append(sessionLog, message)
    }''', 'e0de1574ee57456d7afb1e6642342cd7e585c7c3')
change(HOST, '        const val LOG_RETENTION_MILLIS = 5 * 60_000L',
       '        const val LOG_RETENTION_MILLIS = RecentDiagnosticWindow.WINDOW_MILLIS')

subprocess.run(['python3', 'scripts/version.py', 'bump'], cwd=ROOT, check=True)
subprocess.run(['git', 'diff', '--exit-code', 'ffe3ceca9bbcd634a7f7bc4225752228dc1f1da7', '--',
                'shared/src/main/java/com/shilapi/xcertplay/media/MediaAudioBuffer.kt'], cwd=ROOT, check=True)
print('Integrated bounded audio retry, async host logging and three-hour export. MediaAudioBuffer is byte-for-byte unchanged.')
