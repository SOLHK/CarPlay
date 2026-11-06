package com.shilapi.xcertplay

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Filters event records, not device snapshots. Never guess a date for an unanchored old log. */
internal object RecentDiagnosticWindow {
    const val WINDOW_MILLIS = 3L * 60L * 60L * 1000L
    private const val SUMMARY = "Log window: "
    private val logSection = Regex("^--- (?:diplay|previous(?:-[0-9]+)?)\\.log ---$")
    private val fullTime = Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}(?: [+-]\\d{4})?")
    private val shortTime = Regex("^(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})(?=\\s|$)")
    private const val START = "DiPlay log started "

    fun filter(report: String, nowMillis: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault()): String {
        if (!report.lineSequence().any { logSection.matches(it.trimEnd()) }) return report
        val local = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply {
            timeZone = zone; isLenient = false
        }
        val zoned = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).apply { isLenient = false }
        fun parse(value: String): Long? {
            val pos = ParsePosition(0)
            val date = (if (value.length > 23) zoned else local).parse(value, pos)
            return date?.time?.takeIf { pos.index == value.length }
        }
        val cutoff = nowMillis - WINDOW_MILLIS
        val output = StringBuilder()
        var section: String? = null
        var emitted = false
        var firstLogOffset: Int? = null
        var day: Calendar? = null
        var lastTod: Long? = null
        var includeContinuation = false
        var excluded = 0
        var unknown = 0
        var first: Long? = null
        var last: Long? = null
        fun setDay(epoch: Long) {
            day = Calendar.getInstance(zone, Locale.US).apply {
                timeInMillis = epoch
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
        }
        for (raw in report.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.startsWith(SUMMARY)) continue
            if (logSection.matches(line)) {
                // REPORT_NAMES is chronological. Size rotation has no new session header;
                // carry an already established date into the next segment, never invent one.
                section = line; emitted = false
                includeContinuation = false
                continue
            }
            if (line.startsWith("--- ") && line.endsWith(" ---")) {
                section = null; includeContinuation = false; day = null; lastTod = null
            }
            if (section == null) {
                output.append(line).append('\n')
                continue
            }
            // A malformed explicit restart cannot inherit the previous session's day.
            if (line.startsWith(START)) { day = null; lastTod = null }
            val source = if (line.startsWith(START)) line.removePrefix(START) else line
            val full = fullTime.find(source)?.value
            val short = if (full == null) shortTime.find(line) else null
            var epoch = full?.let(::parse)
            var normalized = line
            val absolute = epoch
            if (absolute != null) {
                setDay(absolute)
                val c = Calendar.getInstance(zone, Locale.US).apply { timeInMillis = absolute }
                lastTod = ((c.get(Calendar.HOUR_OF_DAY) * 60L + c.get(Calendar.MINUTE)) * 60L +
                    c.get(Calendar.SECOND)) * 1000L + c.get(Calendar.MILLISECOND)
            } else if (short != null) {
                val (h, m, s, ms) = short.destructured
                val hour = h.toInt(); val minute = m.toInt(); val second = s.toInt()
                val valid = hour in 0..23 && minute in 0..59 && second in 0..59
                val tod = ((hour * 60L + minute) * 60L + second) * 1000L + ms.toInt()
                if (valid && day != null) {
                    // Small producer reorderings are not a midnight rollover.
                    if (lastTod?.let { it - tod > 12L * 60L * 60L * 1000L } == true) day!!.add(Calendar.DATE, 1)
                    lastTod = tod
                    val c = day!!.clone() as Calendar
                    c.set(Calendar.HOUR_OF_DAY, hour); c.set(Calendar.MINUTE, minute)
                    c.set(Calendar.SECOND, second); c.set(Calendar.MILLISECOND, ms.toInt())
                    epoch = c.timeInMillis
                    normalized = local.format(Date(c.timeInMillis)) + line.substring(short.value.length)
                }
            } else if (full == null && !line.startsWith(START)) {
                if (includeContinuation && emitted) output.append(line).append('\n')
                else if (line.isNotBlank()) unknown++
                continue
            }
            includeContinuation = epoch != null && epoch in cutoff..nowMillis
            if (!includeContinuation) {
                if (epoch == null) unknown++ else excluded++
                continue
            }
            if (!emitted) {
                if (firstLogOffset == null) firstLogOffset = output.length
                output.append(section).append('\n'); emitted = true
            }
            output.append(normalized).append('\n')
            val stamp = epoch!!
            first = minOf(first ?: stamp, stamp); last = maxOf(last ?: stamp, stamp)
        }
        val available = if (first == null) "none" else "${local.format(Date(first!!))} .. ${local.format(Date(last!!))}"
        val summary = "${SUMMARY}last 3 hours; from=${local.format(Date(cutoff))}; " +
            "to=${local.format(Date(nowMillis))}; timezone=${zone.id}; available=$available; " +
            "excluded=$excluded; undatedOmitted=$unknown; history may be shorter due to the storage size cap.\n"
        val body = output.toString().trimEnd() + "\n"
        // Version, last problem and Android version retain their established first three
        // lines. Put window metadata beside the log, not ahead of the report header.
        val insertion = firstLogOffset ?: body.length
        return body.substring(0, insertion) + summary + body.substring(insertion)
    }
}
