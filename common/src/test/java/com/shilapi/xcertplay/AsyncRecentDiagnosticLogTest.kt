package com.shilapi.xcertplay

import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class AsyncRecentDiagnosticLogTest {
    @Test(timeout = 10_000)
    fun producerDoesNotWaitForDiskAndKeepsTheOriginalAbsoluteTimestamp() {
        assertTrue(AsyncDiagnosticLog.awaitIdle(2000))
        val dir = Files.createTempDirectory("async-recent-log").toFile()
        val log = SessionLogFile(File(dir, "diplay.log"))
        log.reset("start")
        val lock = SessionLogFile::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(log)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val timestamp = 1793510000000L
        try {
            val blockedDisk = executor.submit {
                synchronized(lock) { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val producer = executor.submit { AsyncDiagnosticLog.append(log, "current-receive", timestamp) }
            producer.get(500, TimeUnit.MILLISECONDS)
            assertFalse("The test must keep the actual file writer blocked", AsyncDiagnosticLog.awaitIdle(20))
            release.countDown()
            blockedDisk.get(2, TimeUnit.SECONDS)
            assertTrue(AsyncDiagnosticLog.awaitIdle(2000))
            val expected = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date(timestamp))
            assertTrue(log.file.readText().contains("$expected  current-receive"))
        } finally {
            release.countDown()
            executor.shutdownNow()
            AsyncDiagnosticLog.awaitIdle(2000)
            log.close()
            dir.deleteRecursively()
        }
    }
}
