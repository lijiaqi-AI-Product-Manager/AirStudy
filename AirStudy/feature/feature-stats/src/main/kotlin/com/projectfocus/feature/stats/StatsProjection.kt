package com.projectfocus.feature.stats

import com.projectfocus.service.focus.history.HistoryReadState
import com.projectfocus.service.focus.history.HistoryStatistics
import java.time.ZoneId

data class StatsQuery(val generation: Long, val dates: StatsDateSelection, val revision: Long = 0, val sceneKey: String = "")

object StatsProjection {
    fun build(read: HistoryReadState, query: StatsQuery, nowWallMs: Long, zone: ZoneId): StatsUiState {
        val sameAccount = query.generation == read.account.generation
        val dates = if (sameAccount) query.dates else StatsDateSelection.current(nowWallMs, zone)
        val sceneKey = if (sameAccount) query.sceneKey else ""
        val range = dates.range(zone)
        val base = StatsUiState(account = read.account, dates = dates, dateRange = range,
            canNextPeriod = dates.canMoveForward(nowWallMs, zone), queryRevision = query.revision, nowWallMs = nowWallMs,
            selectedSceneKey = sceneKey)
        if (!read.account.isCurrent()) return StatsUiState()
        if (read is HistoryReadState.Loading) return base
        if (read is HistoryReadState.Failure) return base.copy(readFailed = true)
        read as HistoryReadState.Loaded
        val filtered = read.records.filter { StatsScenes.matches(it, sceneKey) }
        val summary = HistoryStatistics.calculate(filtered.filter { it.recordedAtWallMs in range }, read.airportCounts, read.airportCodes)
        val overview = StatsOverviewMetrics.calculate(filtered, range, zone)
        return base.copy(flights = summary.flights, distanceKm = summary.distanceKm,
            focusMinutes = Math.round(overview.totalFocusMinutes).coerceIn(0,Int.MAX_VALUE.toLong()).toInt(), overview = overview,
            airports = summary.destinations.size, countries = summary.countries.size, countryBreakdown = summary.countries,
            destinations = summary.destinations, isLoading = false,
            periodScenes = StatsScenes.group(read.records, range), allTimeScenes = StatsScenes.group(read.records),
            trend = StatsTrend.calculate(filtered, range, zone),
            focusComparison = StatsPeriodComparison.calculate(filtered,overview.totalFocusMinutes,dates,zone))
    }
}
