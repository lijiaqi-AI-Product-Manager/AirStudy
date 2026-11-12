package com.projectfocus.feature.stats

import com.projectfocus.service.focus.history.HistoryReadState
import com.projectfocus.service.focus.history.RecordSummaryState
import com.projectfocus.core.database.dao.CountryTripCount
import java.time.ZoneId

data class StatsQuery(val generation: Long, val dates: StatsDateSelection, val revision: Long = 0, val sceneKey: String = "", val heatmapYear: Int? = null, val heatmapRevision: Long = 0)

object StatsProjection {
    fun build(read: HistoryReadState, query: StatsQuery, nowWallMs: Long, zone: ZoneId, summaries: RecordSummaryState? = null): StatsUiState {
        val sameAccount = query.generation == read.account.generation
        val dates = if (sameAccount) query.dates else StatsDateSelection.current(nowWallMs, zone)
        val sceneKey = if (sameAccount) query.sceneKey else ""
        val currentYear = java.time.Instant.ofEpochMilli(nowWallMs).atZone(zone).year
        val year = if (sameAccount) query.heatmapYear ?: currentYear else currentYear
        val range = dates.range(zone)
        val base = StatsUiState(account = read.account, dates = dates, dateRange = range,
            canNextPeriod = dates.canMoveForward(nowWallMs, zone), queryRevision = query.revision, nowWallMs = nowWallMs,
            selectedSceneKey = sceneKey, heatmapYear = year, canNextHeatmapYear = year < currentYear,
            heatmapRevision = if (sameAccount) query.heatmapRevision else 0)
        if (!read.account.isCurrent()) return StatsUiState()
        if (read is HistoryReadState.Loading) return base
        if (read is HistoryReadState.Failure) return base.copy(readFailed = true)
        read as HistoryReadState.Loaded
        val facts = StatsFactSet.merge(read,summaries)
        val annualRange = StatsDateSelection(java.time.LocalDate.of(year,1,1),StatsPeriod.YEAR).range(zone)
        val coverage = facts.coverage(range,StatsPeriodComparison.previousRange(dates,zone),annualRange,sceneKey)
        val filtered = facts.records.filter { it.matchesScene(sceneKey) }
        val period = filtered.filter { it.inRange(range) }
        val travel = StatsCareer.calculateFacts(period,read.airportCounts,read.airportCodes)
        val countryCounts = travel.countries.map { country -> CountryTripCount(country.country,
            period.count { it.completed == true && it.destination?.country == country.country },country.visitedAirports,country.totalAirports) }
        val overview = StatsOverviewMetrics.calculateFacts(filtered, range, zone)
        val hours = StatsHourHabits.calculateFacts(filtered, range, zone).takeIf { coverage.hours }
        return base.copy(flights = travel.completedFlights, distanceKm = travel.distanceKm,
            focusMinutes = Math.round(overview.totalFocusMinutes).coerceIn(0,Int.MAX_VALUE.toLong()).toInt(), overview = overview,
            airports = travel.destinations.size, countries = countryCounts.size, countryBreakdown = countryCounts,
            destinations = travel.destinations, isLoading = false,
            periodScenes = StatsScenes.groupFacts(facts.records, range), allTimeScenes = StatsScenes.groupFacts(facts.records),
            trend = StatsTrend.calculateFacts(filtered, range, zone),
            focusComparison = StatsPeriodComparison.calculateFacts(filtered,overview.totalFocusMinutes,dates,zone).takeIf { coverage.comparison },
            annualHeatmap = StatsAnnualHeatmap.calculateFacts(filtered, year, zone).takeIf { coverage.annual },
            career = StatsCareer.calculateFacts(facts.records, read.airportCounts, read.airportCodes),
            flightStreak = StatsFlightStreak.calculateFacts(filtered, range, nowWallMs, zone).takeIf { coverage.periodDates },
            hourHabits = hours,
            periodDetails = StatsPeriodDetails.calculateFacts(filtered, range, zone, hours,coverage.periodFocus,coverage.periodScenes),
            coverage = coverage)
    }
}
