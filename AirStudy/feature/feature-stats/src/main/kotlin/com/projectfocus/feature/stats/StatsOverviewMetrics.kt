package com.projectfocus.feature.stats

import com.projectfocus.service.focus.history.RecordedFlight
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class StatsOverviewMetrics(
    val totalFocusMinutes: Double = 0.0,
    val completedFlights: Int = 0,
    val activeDays: Int = 0,
    val longestFocusMinutes: Double = 0.0,
    val bestStreakDays: Int = 0,
) {
    companion object {
        fun calculate(records: List<RecordedFlight>, range: StatsDateRange, zone: ZoneId): StatsOverviewMetrics {
            val completed = records.filter { it.completed && it.recordedAtWallMs in range }
            fun minutes(record: RecordedFlight): Double = when (record) {
                is RecordedFlight.Countdown -> record.record.actualFocusMinutes.coerceAtLeast(0).toDouble()
                is RecordedFlight.CountUp -> record.archive.clock.activeElapsedMs / 60_000.0
            }
            val total = completed.sumOf(::minutes)
            require(total.isFinite() && total >= 0.0)
            val dates = completed.map { Instant.ofEpochMilli(it.recordedAtWallMs).atZone(zone).toLocalDate() }.toSortedSet()
            var previous: LocalDate? = null
            var run = 0
            var best = 0
            for (date in dates) {
                run = if (previous?.plusDays(1) == date) run + 1 else 1
                best = maxOf(best, run)
                previous = date
            }
            return StatsOverviewMetrics(total, completed.size, dates.size, completed.maxOfOrNull(::minutes) ?: 0.0, best)
        }
    }
}
