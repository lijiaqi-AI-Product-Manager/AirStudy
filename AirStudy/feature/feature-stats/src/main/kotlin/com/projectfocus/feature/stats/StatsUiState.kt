package com.projectfocus.feature.stats

import com.projectfocus.core.database.dao.CountryTripCount
import com.projectfocus.service.focus.history.HistoryAccount
import com.projectfocus.service.focus.history.HistoryDestination

/**
 * 统计页的纯数据状态。
 *
 * 把 5 个聚合值收在一个不可变对象里，Fragment 只负责渲染；
 * 时长拆成“小时 + 分钟”是纯计算，可单元测试，不依赖 Android 资源。
 */
data class StatsUiState(
    val flights: Int = 0,
    val distanceKm: Double = 0.0,
    val focusMinutes: Int = 0,
    val airports: Int = 0,
    val countries: Int = 0,
    val countryBreakdown: List<CountryTripCount> = emptyList(),
    val isLoading: Boolean = true,
    val account: HistoryAccount? = null,
    val destinations: List<HistoryDestination> = emptyList(),
    val readFailed: Boolean = false,
    val dates: StatsDateSelection? = null,
    val dateRange: StatsDateRange? = null,
    val canNextPeriod: Boolean = false,
    val queryRevision: Long = 0,
    val nowWallMs: Long = 0,
    val overview: StatsOverviewMetrics? = null,
    val focusComparison: StatsFocusComparison? = null,
    val selectedSceneKey: String = "",
    val periodScenes: List<StatsSceneEntry> = emptyList(),
    val allTimeScenes: List<StatsSceneEntry> = emptyList(),
    val trend: StatsTrendSeries? = null,
    val heatmapYear: Int = 0,
    val heatmapRevision: Long = 0,
    val canNextHeatmapYear: Boolean = false,
    val annualHeatmap: StatsAnnualHeatmap? = null,
    val career: StatsCareer? = null,
    val flightStreak: StatsFlightStreak? = null,
) {
    val focusHours: Int get() = focusMinutes / 60
    val focusRemainderMinutes: Int get() = focusMinutes % 60
    val hasFocusHours: Boolean get() = focusHours > 0
}

data class StatsFocusComparison(val deltaMinutes: Double, val ratio: Double?) {
    init { require(deltaMinutes.isFinite() && (ratio == null || ratio.isFinite())) }
}
