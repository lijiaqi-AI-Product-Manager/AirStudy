package com.projectfocus.feature.stats

import com.projectfocus.service.focus.history.RecordedFlight
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

enum class StatsTrendGranularity { HOUR, DAY, WEEK, MONTH }
enum class StatsTrendMetric { COMPLETED, FOCUS }
enum class StatsTrendMode { LINE, STACKED_BAR }

data class StatsTrendBucket(
    val fromWallMs: Long,
    val untilWallMs: Long,
    val completedFlights: Int,
    val focusMinutes: Double,
    val scenes: List<StatsSceneEntry>,
) {
    fun value(metric: StatsTrendMetric): Double = if (metric == StatsTrendMetric.COMPLETED) completedFlights.toDouble() else focusMinutes
}

data class StatsTrendSeries(val granularity: StatsTrendGranularity, val buckets: List<StatsTrendBucket>) {
    fun peak(metric: StatsTrendMetric): Double = buckets.maxOfOrNull { it.value(metric) } ?: 0.0
}

object StatsTrend {
    fun calculate(records: List<RecordedFlight>, range: StatsDateRange, zone: ZoneId): StatsTrendSeries {
        val span = range.untilWallMs - range.fromWallMs
        val granularity = when {
            span <= 129_600_000L -> StatsTrendGranularity.HOUR
            span <= 2_678_400_000L -> StatsTrendGranularity.DAY
            span <= 18_144_000_000L -> StatsTrendGranularity.WEEK
            else -> StatsTrendGranularity.MONTH
        }
        val completed = records.filter { it.completed && it.recordedAtWallMs in range }
        if (completed.isEmpty()) return StatsTrendSeries(granularity, emptyList())
        var cursor = Instant.ofEpochMilli(range.fromWallMs).atZone(zone)
        if (granularity == StatsTrendGranularity.MONTH) cursor = cursor.withDayOfMonth(1)
        fun next(value: ZonedDateTime) = when (granularity) {
            StatsTrendGranularity.HOUR -> value.plusHours(1)
            StatsTrendGranularity.DAY -> value.plusDays(1)
            StatsTrendGranularity.WEEK -> value.plusWeeks(1)
            StatsTrendGranularity.MONTH -> value.plusMonths(1)
        }
        val intervals = mutableListOf<Pair<Long, Long>>()
        while (cursor.toInstant().toEpochMilli() < range.untilWallMs && intervals.size < 400) {
            val from = cursor.toInstant().toEpochMilli()
            cursor = next(cursor)
            intervals += from to minOf(cursor.toInstant().toEpochMilli(), range.untilWallMs)
        }
        val grouped = Array(intervals.size) { mutableListOf<RecordedFlight>() }
        for (record in completed) {
            var left = 0
            var right = intervals.lastIndex
            while (left <= right) {
                val middle = (left + right).ushr(1)
                val interval = intervals[middle]
                when {
                    record.recordedAtWallMs < interval.first -> right = middle - 1
                    record.recordedAtWallMs >= interval.second -> left = middle + 1
                    else -> { grouped[middle] += record; break }
                }
            }
            check(left <= right) { "Completed flight lies outside the trend timeline" }
        }
        val buckets = intervals.mapIndexed { index, interval ->
            val rows = grouped[index]
            val minutes = rows.sumOf(StatsFocusMinutes::value)
            require(minutes.isFinite() && minutes >= 0.0)
            StatsTrendBucket(interval.first, interval.second, rows.size, minutes, StatsScenes.group(rows))
        }
        return StatsTrendSeries(granularity, buckets)
    }
}
