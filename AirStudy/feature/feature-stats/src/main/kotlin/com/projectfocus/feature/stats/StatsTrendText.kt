package com.projectfocus.feature.stats

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal object StatsTrendText {
    fun value(context: Context, metric: StatsTrendMetric, value: Double): String {
        if (metric == StatsTrendMetric.COMPLETED) return context.getString(R.string.stats_times_fmt, Math.round(value))
        if (value < 1.0) return context.getString(R.string.stats_duration_minutes, 0)
        val minutes = Math.round(value).coerceIn(0,Int.MAX_VALUE.toLong()).toInt()
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours > 0 && rest > 0 -> context.getString(R.string.stats_duration_hours_minutes,hours,rest)
            hours > 0 -> context.getString(R.string.stats_duration_hours,hours)
            else -> context.getString(R.string.stats_duration_minutes,rest)
        }
    }
    fun axis(context: Context, granularity: StatsTrendGranularity, wall: Long, zone: TimeZone): String {
        val resource = when(granularity) {
            StatsTrendGranularity.HOUR -> R.string.stats_trend_axis_hour_format
            StatsTrendGranularity.DAY -> R.string.stats_trend_axis_day_format
            StatsTrendGranularity.WEEK -> R.string.stats_trend_axis_week_format
            StatsTrendGranularity.MONTH -> R.string.stats_trend_axis_month_format
        }
        return SimpleDateFormat(context.getString(resource),Locale.getDefault()).apply { timeZone=zone }.format(wall)
    }
}
