package com.projectfocus.feature.stats

import com.projectfocus.service.focus.history.RecordedFlight
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Collections

data class StatsAnnualHeatmap private constructor(
    val year: Int,
    val zone: ZoneId,
    val days: Map<LocalDate, Double>,
) {
    val firstDate: LocalDate = LocalDate.of(year, 1, 1)
    val untilDate: LocalDate = firstDate.plusYears(1)
    val gridStart: LocalDate = firstDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val grid: List<LocalDate> = Collections.unmodifiableList(List(54 * 7) { gridStart.plusDays(it.toLong()) })
    val maximumMinutes: Double = (days.values.maxOrNull() ?: 0.0).coerceAtLeast(1.0)

    fun minutes(date: LocalDate): Double = days[date] ?: 0.0
    fun contains(date: LocalDate): Boolean = date >= firstDate && date < untilDate
    fun scrollIndex(date: LocalDate): Int = java.time.temporal.ChronoUnit.DAYS.between(
        gridStart, date.coerceIn(firstDate, untilDate.minusDays(1)),
    ).toInt()

    companion object {
        fun calculate(records: List<RecordedFlight>, year: Int, zone: ZoneId): StatsAnnualHeatmap {
            val first = LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
            val until = LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
            val daily = sortedMapOf<LocalDate, Double>()
            records.forEach { record ->
                if (record.completed && record.recordedAtWallMs >= first && record.recordedAtWallMs < until) {
                    val date = Instant.ofEpochMilli(record.recordedAtWallMs).atZone(zone).toLocalDate()
                    val minutes = (daily[date] ?: 0.0) + StatsFocusMinutes.value(record)
                    require(minutes.isFinite() && minutes >= 0.0)
                    daily[date] = minutes
                }
            }
            return StatsAnnualHeatmap(year, zone, Collections.unmodifiableMap(daily))
        }
    }
}
