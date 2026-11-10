package com.projectfocus.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.service.focus.history.FlightHistoryReader
import com.projectfocus.service.focus.history.HistoryAccount
import com.projectfocus.service.focus.history.RecordSummaryStore
import com.projectfocus.service.focus.history.RecordSummaryState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val history: FlightHistoryReader,
    private val clock: Clock,
    val zone: ZoneId,
    private val summaries: RecordSummaryStore? = null,
) : ViewModel() {
    private val now = MutableStateFlow(clock.millis())
    private val query = MutableStateFlow(StatsQuery(TokenStore.snapshot().generation, StatsDateSelection.current(now.value, zone),
        heatmapYear = Instant.ofEpochMilli(now.value).atZone(zone).year))

    init {
        viewModelScope.launch {
            TokenStore.identities.collect { identity ->
                if (identity == TokenStore.snapshot()) query.update { current ->
                    if (current.generation == identity.generation) current
                    else StatsQuery(identity.generation, StatsDateSelection.current(clock.millis(), zone), current.revision + 1,
                        heatmapYear = today().year)
                }
            }
        }
    }

    val state: StateFlow<StatsUiState> = combine(history.records, query, now,
        summaries?.observe() ?: kotlinx.coroutines.flow.flowOf<RecordSummaryState?>(null)) { loaded, selected, wall, remote ->
        try { StatsProjection.build(loaded, selected, wall, zone, remote) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { StatsUiState(account = loaded.account, readFailed = true, queryRevision = selected.revision,
            nowWallMs = wall, selectedSceneKey = selected.sceneKey.takeIf { selected.generation == loaded.account.generation }.orEmpty(),
            heatmapYear = if (selected.generation == loaded.account.generation) selected.heatmapYear ?: today().year else today().year,
            heatmapRevision = selected.heatmapRevision.takeIf { selected.generation == loaded.account.generation } ?: 0) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    fun retry() { history.retry() }
    fun refreshNow() { now.value = clock.millis() }
    fun today(): LocalDate = Instant.ofEpochMilli(clock.millis()).atZone(zone).toLocalDate()
    fun currentWallMs(): Long = clock.millis()
    fun isCurrentQuery(account: HistoryAccount, revision: Long): Boolean = account.isCurrent() &&
        query.value.let { it.generation == account.generation && it.revision == revision }

    fun isCurrentHeatmapQuery(account: HistoryAccount, revision: Long, heatmapRevision: Long): Boolean =
        isCurrentQuery(account, revision) && query.value.heatmapRevision == heatmapRevision

    fun moveHeatmapYear(account: HistoryAccount, revision: Long, heatmapRevision: Long, direction: Int) {
        require(direction in -1..1)
        if (direction == 0 || !isCurrentHeatmapQuery(account, revision, heatmapRevision)) return
        val currentYear = today().year
        query.update { current ->
            if (!account.isCurrent() || current.generation != account.generation || current.revision != revision ||
                current.heatmapRevision != heatmapRevision) current
            else {
                val base = current.heatmapYear ?: currentYear
                val next = base.toLong() + direction
                if (next > currentYear || next < Int.MIN_VALUE) current
                else current.copy(heatmapYear = next.toInt(), heatmapRevision = current.heatmapRevision + 1)
            }
        }
        refreshNow()
        retryFailedRead()
    }

    fun selectPeriod(account: HistoryAccount, period: StatsPeriod) {
        if (period == StatsPeriod.CUSTOM) return
        change(account, true) { it.selectPeriod(period, clock.millis(), zone) }
    }

    fun movePeriod(account: HistoryAccount, direction: Int) = change(account) { it.move(direction, clock.millis(), zone) }

    fun selectCustom(account: HistoryAccount, revision: Long, first: LocalDate, last: LocalDate) {
        if (last.isAfter(today()) || !account.isCurrent()) return
        query.update { current ->
            if (current.generation != account.generation || current.revision != revision || !account.isCurrent()) current
            else current.copy(dates = current.dates.custom(first, last), revision = current.revision + 1, heatmapYear = last.year)
        }
        refreshNow()
        retryFailedRead()
    }

    fun selectScene(account: HistoryAccount, revision: Long, key: String) {
        if (!isCurrentQuery(account, revision)) return
        val loaded = state.value
        if (key.isNotEmpty() && (loaded.account != account || loaded.isLoading || loaded.queryRevision != revision ||
            (loaded.allTimeScenes + loaded.periodScenes).none { it.key == key })) return
        query.update { current ->
            if (!account.isCurrent() || current.generation != account.generation || current.revision != revision) current
            else current.copy(sceneKey = if (current.sceneKey == key) "" else key, revision = current.revision + 1)
        }
        refreshNow()
        retryFailedRead()
    }

    private fun change(account: HistoryAccount, alignYearWhenUnchanged: Boolean = false, transform: (StatsDateSelection) -> StatsDateSelection) {
        if (!account.isCurrent()) return
        query.update { current ->
            if (!account.isCurrent()) current
            else {
                val base = if (current.generation == account.generation) current.dates else StatsDateSelection.current(clock.millis(), zone)
                val next = transform(base)
                val year = if (next != base || alignYearWhenUnchanged) next.range(zone).lastDate.year else current.heatmapYear
                if (next == base && current.generation == account.generation && year == current.heatmapYear) current
                else if (current.generation == account.generation) current.copy(dates = next, revision = current.revision + 1, heatmapYear = year)
                else StatsQuery(account.generation, next, current.revision + 1, heatmapYear = year ?: today().year)
            }
        }
        refreshNow()
        retryFailedRead()
    }

    private fun retryFailedRead() {
        if (state.value.readFailed && state.value.account?.isCurrent() == true) history.retry()
    }
}
