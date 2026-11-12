package com.projectfocus.core.common.plan

import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID


object SourceFlightPlanDates {
    const val HISTORY_LIMIT = 200
    private const val MILLIS_PER_MINUTE = 60_000

    data class DailyFields(
        val planDate: String,
        val localPlanDate: String,
        val timezone: String,
        val timezoneOffsetMinutes: Int,
    )

    data class HistoryRange(
        val startDate: String,
        val endDate: String,
        val timezone: String,
        val limit: Int = HISTORY_LIMIT,
    )

    /** c4.h.n writes the same selected date to both GET fields. */
    fun get(localDate: LocalDate, zone: ZoneId): DailyFields {
        val selected = localDate.toString()
        return DailyFields(selected, selected, zone.id, offsetMinutes(localDate, zone))
    }

    /**
     * c0.f passes y() to plan_date and x() to local_plan_date. A remote cached n
     * takes precedence; otherwise h0 converts LOCAL MIDNIGHT to its UTC date.
     * Java trim here is the effective c4.h.t wire value, not a new date validator.
     */
    fun upsert(
        localDate: LocalDate,
        zone: ZoneId,
        cachedPlanDate: String? = null,
    ): DailyFields {
        val cached = cachedPlanDate?.let(::javaTrim)?.takeIf { it.isNotEmpty() }
        val planDate = cached ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(parseCalendarDate(localDate, zone, noon = false))
        return DailyFields(planDate, localDate.toString(), zone.id, offsetMinutes(localDate, zone))
    }

    /**
     * y4.a.R uses the selected day's LOCAL NOON offset, not UTC midnight, local
     * midnight, the current instant or the offset stored on another plan date.
     */
    fun offsetMinutes(localDate: LocalDate, zone: ZoneId): Int {
        val atNoon = parseCalendarDate(localDate, zone, noon = true)
        return TimeZone.getTimeZone(zone).getOffset(atNoon.time) / MILLIS_PER_MINUTE
    }

    /**
     * g2.a/Y compare the selection with DEVICE-local today. A non-past date uses
     * GET, represented by null here. History starts at the selected month day1;
     * the device-current month ends at device tomorrow (even in the next month),
     * otherwise at that selected month's last day. The range is date text.
     */
    fun history(
        selectedDate: LocalDate,
        todayDeviceDate: LocalDate,
        zone: ZoneId,
    ): HistoryRange? {
        if (!selectedDate.isBefore(todayDeviceDate)) return null
        val end = if (selectedDate.year == todayDeviceDate.year &&
            selectedDate.month == todayDeviceDate.month
        ) {
            todayDeviceDate.plusDays(1)
        } else {
            selectedDate.withDayOfMonth(selectedDate.lengthOfMonth())
        }
        return HistoryRange(selectedDate.withDayOfMonth(1).toString(), end.toString(), zone.id)
    }

    fun getRequestId(uuid: UUID): String = "get_" + uuid.toString().replace("-", "")

    fun historyRequestId(uuid: UUID): String = "history_" + uuid.toString().replace("-", "")

    /** c0.f's request id. legCount represents JSONArray.length and cannot be negative. */
    fun upsertRequestId(planDate: String, legCount: Int, epochMillis: Long): String {
        require(legCount >= 0) { "legCount must be a list length" }
        return "req_add_${javaTrim(planDate)}_${legCount}_$epochMillis"
    }

    /** c4.h.t's fallback for an absent/Java-blank supplied request id. */
    fun fallbackUpsertRequestId(epochMillis: Long): String = "req_$epochMillis"

    private fun parseCalendarDate(localDate: LocalDate, zone: ZoneId, noon: Boolean): Date {
        val pattern = if (noon) "yyyy-MM-dd HH:mm:ss" else "yyyy-MM-dd"
        val input = localDate.toString() + if (noon) " 12:00:00" else ""
        return checkNotNull(SimpleDateFormat(pattern, Locale.US).apply {
            timeZone = TimeZone.getTimeZone(zone)
        }.parse(input)) { "Typed date was not representable by the source calendar" }
    }

    private fun javaTrim(value: String): String = value.trim { it <= '\u0020' }
}
