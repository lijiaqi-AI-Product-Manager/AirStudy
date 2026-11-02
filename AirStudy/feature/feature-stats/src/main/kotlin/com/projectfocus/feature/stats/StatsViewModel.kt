package com.projectfocus.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.projectfocus.service.focus.history.FlightHistoryReader
import com.projectfocus.service.focus.history.HistoryReadState
import com.projectfocus.service.focus.history.HistoryStatistics
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * 统计页观察当前账号的两种飞行存档，生成一个 [StatsUiState]。
 *
 * 不在 Fragment 里直接 combine，是为了：
 * 1. 旋转屏幕时聚合结果不用重订阅；
 * 2. 纯状态可被测试/预览；
 * 3. Fragment 只做“把 state 写到 TextView”。
 */
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val history: FlightHistoryReader,
) : ViewModel() {

    val state: StateFlow<StatsUiState> = history.records.map { loaded ->
        when (loaded) {
            is HistoryReadState.Loading -> StatsUiState(account = loaded.account)
            is HistoryReadState.Failure -> StatsUiState(account = loaded.account, readFailed = true)
            is HistoryReadState.Loaded -> {
                try {
                    val summary = HistoryStatistics.calculate(loaded.records, loaded.airportCounts, loaded.airportCodes)
                    StatsUiState(summary.flights, summary.distanceKm, summary.focusMinutes,
                        summary.destinations.size, summary.countries.size, summary.countries, false,
                        loaded.account, summary.destinations)
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { StatsUiState(account = loaded.account, readFailed = true) }
            }
        }
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = StatsUiState(),
    )

    fun retry() { history.retry() }
}
