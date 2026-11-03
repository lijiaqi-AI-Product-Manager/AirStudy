package com.projectfocus.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.service.focus.history.FlightHistoryReader
import com.projectfocus.service.focus.history.HistoryAccount
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
) : ViewModel() {
    private val now = MutableStateFlow(clock.millis())
    private val query = MutableStateFlow(StatsQuery(TokenStore.snapshot().generation, StatsDateSelection.current(now.value, zone)))

    init {
        viewModelScope.launch {
            TokenStore.identities.collect { identity ->
                if (identity == TokenStore.snapshot()) query.update { current ->
                    if (current.generation == identity.generation) current
                    else StatsQuery(identity.generation, StatsDateSelection.current(clock.millis(), zone), current.revision + 1)
                }
            }
        }
    }

    val state: StateFlow<StatsUiState> = combine(history.records, query, now) { loaded, selected, wall ->
        try { StatsProjection.build(loaded, selected, wall, zone) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { StatsUiState(account = loaded.account, readFailed = true, queryRevision = selected.revision,
            nowWallMs = wall, selectedSceneKey = selected.sceneKey.takeIf { selected.generation == loaded.account.generation }.orEmpty()) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    fun retry() { history.retry() }
    fun refreshNow() { now.value = clock.millis() }
    fun today(): LocalDate = Instant.ofEpochMilli(clock.millis()).atZone(zone).toLocalDate()
    fun isCurrentQuery(account: HistoryAccount, revision: Long): Boolean = account.isCurrent() &&
        query.value.let { it.generation == account.generation && it.revision == revision }

    fun selectPeriod(account: HistoryAccount, period: StatsPeriod) {
        if (period == StatsPeriod.CUSTOM) return
        change(account) { it.selectPeriod(period, clock.millis(), zone) }
    }

    fun movePeriod(account: HistoryAccount, direction: Int) = change(account) { it.move(direction, clock.millis(), zone) }

    fun selectCustom(account: HistoryAccount, revision: Long, first: LocalDate, last: LocalDate) {
        if (last.isAfter(today()) || !account.isCurrent()) return
        query.update { current ->
            if (current.generation != account.generation || current.revision != revision || !account.isCurrent()) current
            else current.copy(dates = current.dates.custom(first, last), revision = current.revision + 1)
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

    private fun change(account: HistoryAccount, transform: (StatsDateSelection) -> StatsDateSelection) {
        if (!account.isCurrent()) return
        query.update { current ->
            if (!account.isCurrent()) current
            else {
                val base = if (current.generation == account.generation) current.dates else StatsDateSelection.current(clock.millis(), zone)
                val next = transform(base)
                if (next == base && current.generation == account.generation) current
                else if (current.generation == account.generation) current.copy(dates = next, revision = current.revision + 1)
                else StatsQuery(account.generation, next, current.revision + 1)
            }
        }
        refreshNow()
        retryFailedRead()
    }

    private fun retryFailedRead() {
        if (state.value.readFailed && state.value.account?.isCurrent() == true) history.retry()
    }
}
