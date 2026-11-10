package com.projectfocus.feature.stats

import com.projectfocus.service.focus.history.RecordedFlight
import java.time.ZoneId
import java.time.temporal.ChronoUnit

internal object StatsPeriodComparison {
    fun previousRange(selection: StatsDateSelection,zone: ZoneId): StatsDateRange {
        if(selection.period==StatsPeriod.CUSTOM) {
            val days=ChronoUnit.DAYS.between(selection.customFirst,selection.customLast)+1
            return selection.custom(selection.customFirst.minusDays(days),selection.customFirst.minusDays(1)).range(zone)
        }
        val previous=when(selection.period) {
            StatsPeriod.DAY -> selection.anchor.minusDays(1)
            StatsPeriod.WEEK -> selection.anchor.minusWeeks(1)
            StatsPeriod.MONTH -> selection.anchor.minusMonths(1)
            StatsPeriod.YEAR -> selection.anchor.minusYears(1)
            StatsPeriod.CUSTOM -> error("Custom range is handled separately")
        }
        return selection.copy(anchor=previous).range(zone)
    }
    fun calculate(records: List<RecordedFlight>,currentMinutes: Double,selection: StatsDateSelection,zone: ZoneId): StatsFocusComparison {
        return calculateFacts(records.map(StatsFlightFact::recorded),currentMinutes,selection,zone)
    }
    internal fun calculateFacts(records: List<StatsFlightFact>,currentMinutes: Double,selection: StatsDateSelection,zone: ZoneId): StatsFocusComparison {
        require(currentMinutes.isFinite() && currentMinutes>=0.0)
        val previous=previousRange(selection,zone)
        val minutes=records.asSequence().filter { it.completed == true && it.inRange(previous) }.mapNotNull { it.focusMinutes }.sum()
        require(minutes.isFinite() && minutes>=0.0)
        val difference=currentMinutes-minutes
        return StatsFocusComparison(difference,if(minutes>0.0)difference/minutes else null)
    }
}
