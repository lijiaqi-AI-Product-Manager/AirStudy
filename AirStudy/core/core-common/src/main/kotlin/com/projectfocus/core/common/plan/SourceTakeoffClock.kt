package com.projectfocus.core.common.plan

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.regex.Pattern


object SourceTakeoffClock {
    private const val MINUTES_PER_DAY = 24 * 60
    private val colon = Pattern.compile(":")
    private val localDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    /**
     * p0 uses Java trim only for the initial blank check, then splits the original
     * text with Java's default trailing-empty removal and Integer.parseInt.
     * Thus 1:2, +1:+2 and 01:02: remain accepted; surrounding spaces do not.
     */
    fun parseMinutes(time: String?): Int? {
        if (isJavaBlank(time)) return null
        val parts = colon.split(time)
        if (parts.size != 2) return null
        return try {
            val hour = Integer.parseInt(parts[0])
            val minute = Integer.parseInt(parts[1])
            if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
        } catch (_: Throwable) {
            // Exact non-suspending source parser behavior, not a coroutine error boundary.
            null
        }
    }

    /** Public minute formatting uses Q's double modulo and the source default Locale. */
    fun format(minutes: Int): String {
        val normalized = ((minutes % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        return String.format(Locale.getDefault(), "%02d:%02d", normalized / 60, normalized % 60)
    }

    /** Q argument order and Int addition/overflow semantics; no date carry is returned. */
    fun shift(offsetMinutes: Int, time: String): String? {
        val minutes = parseMinutes(time) ?: return null
        return try {
            format(minutes + offsetMinutes)
        } catch (_: Throwable) {
            null
        }
    }

    /** J deliberately accepts any non-Java-blank selection when earliest is blank. */
    fun canSelect(time: String?, earliest: String?): Boolean {
        if (isJavaBlank(time)) return false
        if (isJavaBlank(earliest)) return true
        val selected = parseMinutes(time) ?: return false
        val minimum = parseMinutes(earliest) ?: return false
        return selected >= minimum
    }

    /**
     * g2.b/c device-date layer only. The caller supplies its actual device-local
     * today; this neither infers a plan zone nor extends new-only guards to edits.
     * Compare source-shaped date text; do not parse/normalize noncanonical dates.
     */
    fun newDateSupported(date: String?, todayLocalDate: LocalDate): Boolean {
        if (isJavaBlank(date)) return false
        val value = javaTrim(checkNotNull(date))
        return value == todayLocalDate.format(localDateFormat) ||
            value == todayLocalDate.plusDays(1).format(localDateFormat)
    }

    /**
     * Clock-only ETA using an already frozen duration. Negative minutes are an
     * invalid product API input; no distance formula or source G/H equivalence
     * is claimed here. Day carry and absolute arrival timestamps belong to Root.
     */
    fun eta(time: String?, durationMinutes: Int): String? {
        if (time == null || durationMinutes < 0) return null
        return shift(durationMinutes, time)
    }

    private fun isJavaBlank(value: String?): Boolean = value == null || javaTrim(value).isEmpty()

    /** String.trim() in the Java source removes UTF-16 chars <= U+0020, not all Unicode whitespace. */
    private fun javaTrim(value: String): String = value.trim { it <= '\u0020' }
}
