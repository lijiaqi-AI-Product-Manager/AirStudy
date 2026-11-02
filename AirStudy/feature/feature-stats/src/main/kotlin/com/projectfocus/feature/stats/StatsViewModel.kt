package com.projectfocus.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.projectfocus.core.database.dao.FlightHistoryDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 统计页 ViewModel：把 5 条 DAO Flow 合并成一个 [StatsUiState]。
 *
 * 不在 Fragment 里直接 combine，是为了：
 * 1. 旋转屏幕时聚合结果不用重订阅；
 * 2. 纯状态可被测试/预览；
 * 3. Fragment 只做“把 state 写到 TextView”。
 */
@HiltViewModel
class StatsViewModel @Inject constructor(
    dao: FlightHistoryDao,
) : ViewModel() {

    private val summary: Flow<StatsUiState> = combine(
        dao.observeCompletedCount(),
        dao.observeTotalDistanceKm(),
        dao.observeTotalFocusMinutes(),
        dao.observeVisitedAirportCount(),
        dao.observeVisitedCountryCount(),
    ) { count, km, minutes, airportCount, countryCount ->
        StatsUiState(
            flights = count,
            distanceKm = km,
            focusMinutes = minutes,
            airports = airportCount,
            countries = countryCount,
            isLoading = false,
        )
    }

    val state: StateFlow<StatsUiState> = combine(
        summary,
        dao.observeCountryBreakdown(),
    ) { base, breakdown ->
        base.copy(countryBreakdown = breakdown)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = StatsUiState(),
    )
}
