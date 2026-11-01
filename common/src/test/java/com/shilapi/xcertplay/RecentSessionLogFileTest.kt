package com.shilapi.xcertplay

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class RecentSessionLogFileTest {
    @Test fun everyAppendIsVisibleAndCloseCannotResurrectTheWriter() {
        val dir = Files.createTempDirectory("recent-log").toFile()
        val log = SessionLogFile(File(dir, "diplay.log"))
        try {
            log.reset("start")
            log.append("first")
            assertTrue(log.file.readText().endsWith("first\n"))
            log.append("second")
            assertTrue(log.file.readText().contains("first\nsecond\n"))
            log.close()
            val before = log.file.readText()
            log.append("late")
            assertEquals(before, log.file.readText())
        } finally { log.close(); dir.deleteRecursively() }
    }

    @Test fun rotationPreservesOldBytesAndWriterTargetsTheNewFile() {
        val dir = Files.createTempDirectory("rotate-log").toFile()
        val log = SessionLogFile(File(dir, "diplay.log"))
        try {
            log.reset("old-session")
            log.append("last-old-record")
            log.reset("new-session")
            log.append("new-record")
            assertEquals("old-session\nlast-old-record\n", File(dir, "previous.log").readText())
            assertEquals("new-session\nnew-record\n", log.file.readText())
        } finally { log.close(); dir.deleteRecursively() }
    }

    @Test fun oldArchivesArePrunedButRecentArchivesAndExportsSurvive() {
        val dir = Files.createTempDirectory("prune-log").toFile()
        val now = System.currentTimeMillis()
        val old = File(dir, "previous-2.log").apply { writeText("old"); setLastModified(now - RecentDiagnosticWindow.WINDOW_MILLIS - 1) }
        val recent = File(dir, "previous.log").apply { writeText("recent"); setLastModified(now - 60_000) }
        val exported = File(dir, "saved-report.txt").apply { writeText("export"); setLastModified(now - 86400_000) }
        val log = SessionLogFile(File(dir, "diplay.log")) { now }
        try {
            log.append("new")
            assertFalse(old.exists())
            assertTrue(recent.exists())
            assertTrue(exported.exists())
        } finally { log.close(); dir.deleteRecursively() }
    }

    @Test fun persistentWriterStillRejectsSecretsAndMultilineInjection() {
        val dir = Files.createTempDirectory("safe-log").toFile()
        val log = SessionLogFile(File(dir, "diplay.log"))
        try {
            log.reset("safe")
            log.append("password=never-write")
            log.append("line\nforged-event")
            log.append("peer=192.168.1.9")
            val text = log.file.readText()
            assertFalse(text.contains("never-write"))
            assertFalse(text.contains("forged-event"))
            assertFalse(text.contains("192.168.1.9"))
            assertTrue(text.contains("[ip]"))
        } finally { log.close(); dir.deleteRecursively() }
    }
}
