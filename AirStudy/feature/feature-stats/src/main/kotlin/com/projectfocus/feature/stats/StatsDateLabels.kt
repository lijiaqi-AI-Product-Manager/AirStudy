package com.projectfocus.feature.stats

import android.content.Context
import android.text.format.DateUtils
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Formatter
import java.util.Locale
import java.util.TimeZone

internal object StatsDateLabels {
    fun label(context: Context, dates: StatsDateSelection, zone: ZoneId, nowWallMs: Long, compact: Boolean): String {
        val range = dates.range(zone)
        fun pattern(value: String) = SimpleDateFormat(value, Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone(zone) }
        fun formatted(wall: Long, showYear: Boolean): String {
            val flags = DateUtils.FORMAT_SHOW_DATE or if (showYear) DateUtils.FORMAT_SHOW_YEAR else DateUtils.FORMAT_NO_YEAR
            return DateUtils.formatDateRange(context, Formatter(StringBuilder(), Locale.getDefault()), wall, wall, flags, zone.id).toString()
        }
        return when (dates.period) {
            StatsPeriod.DAY -> formatted(range.fromWallMs, true)
            StatsPeriod.MONTH -> pattern(context.getString(R.string.stats_date_month_pattern)).format(Date(range.fromWallMs))
            StatsPeriod.YEAR -> pattern(context.getString(R.string.stats_date_year_pattern)).format(Date(range.fromWallMs))
            else -> {
                val currentYear = Instant.ofEpochMilli(nowWallMs).atZone(zone).year
                val showYear = !compact || range.firstDate.year != currentYear || range.lastDate.year != currentYear
                context.getString(R.string.stats_date_range_format, formatted(range.fromWallMs, showYear), formatted(range.untilWallMs - 1, showYear))
            }
        }
    }
}
