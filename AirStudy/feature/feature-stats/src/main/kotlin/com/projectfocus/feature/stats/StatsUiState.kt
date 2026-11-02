package com.projectfocus.feature.stats

import com.projectfocus.core.database.dao.CountryTripCount

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
) {
    val focusHours: Int get() = focusMinutes / 60
    val focusRemainderMinutes: Int get() = focusMinutes % 60
    val hasFocusHours: Boolean get() = focusHours > 0
}
