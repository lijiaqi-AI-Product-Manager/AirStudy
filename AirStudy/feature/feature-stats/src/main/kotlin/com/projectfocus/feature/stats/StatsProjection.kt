package com.projectfocus.feature.stats

import com.projectfocus.service.focus.history.HistoryReadState
import com.projectfocus.service.focus.history.HistoryStatistics
import java.time.ZoneId

data class StatsQuery(val generation: Long, val dates: StatsDateSelection, val revision: Long = 0)

object StatsProjection {
    fun build(read: HistoryReadState, query: StatsQuery, nowWallMs: Long, zone: ZoneId): StatsUiState {
        val dates = if (query.generation == read.account.generation) query.dates else StatsDateSelection.current(nowWallMs, zone)
        val range = dates.range(zone)
        val base = StatsUiState(account = read.account, dates = dates, dateRange = range,
            canNextPeriod = dates.canMoveForward(nowWallMs, zone), queryRevision = query.revision, nowWallMs = nowWallMs)
        if (!read.account.isCurrent()) return StatsUiState()
        if (read is HistoryReadState.Loading) return base
        if (read is HistoryReadState.Failure) return base.copy(readFailed = true)
        read as HistoryReadState.Loaded
        val summary = HistoryStatistics.calculate(read.records.filter { it.recordedAtWallMs in range }, read.airportCounts, read.airportCodes)
        return base.copy(flights = summary.flights, distanceKm = summary.distanceKm, focusMinutes = summary.focusMinutes,
            airports = summary.destinations.size, countries = summary.countries.size, countryBreakdown = summary.countries,
            destinations = summary.destinations, isLoading = false)
    }
}
