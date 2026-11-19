package com.projectfocus.feature.stats

import com.projectfocus.service.focus.history.FlightRecordKey
import com.projectfocus.service.focus.history.RecordedFlight
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections

enum class StatsDayPart { DAWN, MORNING, AFTERNOON, EVENING }
data class StatsBestDay(val date: LocalDate, val focusMinutes: Double)
data class StatsHighlight(val key: FlightRecordKey, val startedAtWallMs: Long,
                          val focusMinutes: Double, val scene: StatsSceneEntry?)

class StatsPeriodDetails private constructor(
    val bestDay: StatsBestDay?,
    val topScene: StatsSceneEntry?,
    val topPeriod: StatsDayPart?,
    highlights: List<StatsHighlight>,
) {
    val highlights: List<StatsHighlight> = Collections.unmodifiableList(ArrayList(highlights))
    override fun equals(other: Any?) = other is StatsPeriodDetails && bestDay == other.bestDay &&
        topScene == other.topScene && topPeriod == other.topPeriod && highlights == other.highlights
    override fun hashCode() = arrayOf(bestDay,topScene,topPeriod,highlights).contentHashCode()

    companion object {
        fun calculate(records: List<RecordedFlight>, range: StatsDateRange, zone: ZoneId,
                      hours: StatsHourHabits = StatsHourHabits.calculate(records,range,zone)): StatsPeriodDetails {
            return calculateFacts(records.map(StatsFlightFact::recorded),range,zone,hours)
        }
        internal fun calculateFacts(records: List<StatsFlightFact>, range: StatsDateRange, zone: ZoneId,
            hours: StatsHourHabits?, focusKnown: Boolean = true, scenesKnown: Boolean = true): StatsPeriodDetails {
            val completed = records.filter { it.completed == true && it.inRange(range) && it.focusMinutes != null }
            val days = sortedMapOf<LocalDate,Double>()
            for (record in completed) {
                val date = Instant.ofEpochMilli(requireNotNull(record.recordedAtWallMs)).atZone(zone).toLocalDate()
                days[date] = (days[date] ?: 0.0) + requireNotNull(record.focusMinutes)
            }
            require(days.values.all { it.isFinite() && it >= 0.0 })
            val best = days.entries.maxByOrNull { it.value }?.takeIf { it.value > 0.0 }
                ?.let { StatsBestDay(it.key,it.value) }
            val scene = StatsScenes.groupFacts(completed).sortedWith(compareByDescending<StatsSceneEntry> { it.focusMinutes }
                .thenBy { it.canonicalKey }).firstOrNull { it.focusMinutes > 0.0 }
            val periods = hours?.minutes.orEmpty().chunked(6).map { it.sum() }
            val period = periods.maxOrNull()?.takeIf { it > 0.0 }
                ?.let { StatsDayPart.entries[periods.indexOf(it)] }
            val ranked = completed.map { StatsHighlight(it.key,requireNotNull(it.recordedAtWallMs),requireNotNull(it.focusMinutes),it.scene) }
                .sortedWith(compareByDescending<StatsHighlight> { it.focusMinutes }.thenByDescending { it.startedAtWallMs }
                    .thenBy { it.key.kind.ordinal }.thenBy { it.key.id }).take(5)
            return StatsPeriodDetails(best.takeIf { focusKnown },scene.takeIf { scenesKnown },period,ranked.takeIf { focusKnown }.orEmpty())
        }
    }
}
