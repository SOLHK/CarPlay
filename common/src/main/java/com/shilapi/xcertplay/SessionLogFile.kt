package com.shilapi.xcertplay

import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream

/** Bounded private diagnostics. Redact before storage; age out archives without blocking producers. */
internal class SessionLogFile(val file: File, private val nowMillis: () -> Long = System::currentTimeMillis) : Closeable {
    private val lock = Any()
    private var closed = false
    private var writer: BufferedWriter? = null
    private var lastPruned = Long.MIN_VALUE

    fun reset(header: String) = synchronized(lock) {
        if (!closed) {
            closeWriter()
            file.parentFile?.mkdirs()
            rotate()
            pruneArchives(nowMillis(), force = true)
            file.writeText("")
            append(header)
        }
    }

    fun append(line: String) = synchronized(lock) {
        if (closed) return@synchronized
        val safe = DiagnosticRedactor.redact(line) ?: return@synchronized
        runCatching {
            pruneArchives(nowMillis())
            if (file.length() > MAX_BYTES) {
                closeWriter()
                rotate()
                file.writeText("")
            }
            val output = writer ?: FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8, 8192).also { writer = it }
            output.write(safe)
            output.newLine()
            // Readers and crash diagnostics see every completed append; no open/close per line.
            output.flush()
        }.onFailure { runCatching { closeWriter() } }
        Unit
    }

    private fun closeWriter() {
        val previous = writer
        writer = null
        previous?.close()
    }

    private fun pruneArchives(now: Long, force: Boolean = false) {
        if (!force && lastPruned != Long.MIN_VALUE && now >= lastPruned && now - lastPruned < 60_000L) return
        lastPruned = now
        val cutoff = now - RecentDiagnosticWindow.WINDOW_MILLIS
        // Include the legacy previous-1 spelling, but never prune user-exported reports.
        for (name in ARCHIVE_NAMES + "previous-1.log") {
            val archive = File(file.parentFile, name)
            if (archive.isFile && archive.lastModified() > 0L && archive.lastModified() < cutoff) archive.delete()
        }
    }

    private fun move(source: File, destination: File) {
        if (!source.exists()) return
        val modified = source.lastModified()
        if (destination.exists()) check(destination.delete()) { "Cannot retire old diagnostic archive" }
        if (!source.renameTo(destination)) {
            source.copyTo(destination, overwrite = true)
            destination.setLastModified(modified)
            check(source.delete()) { "Cannot rotate diagnostic log" }
        }
    }

    private fun rotate() {
        if (!file.exists() || file.length() == 0L) return
        // Renames replace the previous cascade of up to seven 512 KiB copies.
        for (index in ARCHIVE_NAMES.lastIndex downTo 1) {
            move(File(file.parentFile, ARCHIVE_NAMES[index - 1]), File(file.parentFile, ARCHIVE_NAMES[index]))
        }
        move(file, File(file.parentFile, ARCHIVE_NAMES.first()))
    }

    override fun close() = synchronized(lock) {
        closed = true
        closeWriter()
    }

    companion object {
        const val MAX_BYTES = 512 * 1024L
        private val ARCHIVE_NAMES = listOf("previous.log") + (2..7).map { "previous-$it.log" }
        val REPORT_NAMES = ARCHIVE_NAMES.reversed() + "diplay.log"
    }
}
