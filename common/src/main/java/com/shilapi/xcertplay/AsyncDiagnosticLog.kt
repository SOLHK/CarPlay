package com.shilapi.xcertplay

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Producer time is retained; formatting and disk work run on the bounded writer, not audio/UI. */
internal object AsyncDiagnosticLog {
    private data class Entry(val target: SessionLogFile, val safe: String, val timestamp: Long)
    // Accessed only by the single diagnostic writer. Include date and offset across restarts/DST.
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
    private val writer = BoundedDiagnosticWriter<Entry> {
        formatter.timeZone = TimeZone.getDefault()
        it.target.append("${formatter.format(Date(it.timestamp))}  ${it.safe}")
    }

    fun append(target: SessionLogFile?, message: String, nowMillis: Long = System.currentTimeMillis()) {
        if (target == null) return
        val safe = DiagnosticRedactor.redact(message) ?: return
        runCatching { writer.enqueue(Entry(target, safe, nowMillis)) }
    }

    internal fun awaitIdle(timeoutMillis: Long): Boolean = writer.awaitIdle(timeoutMillis)
}
