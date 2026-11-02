package com.projectfocus.core.common.seat

import java.util.Locale

/**
 * 座位几何的唯一常量源。测试只依赖这里，不依赖 View。
 *
 * 放在 core-common：数据层（SeatSelectionStore）也要校验座位，
 * 不能为了一个正则让 booking → core-ui 产生 UI 依赖。
 */
object SeatMapGeometry {
    const val ROWS: Int = 30
    val COLUMNS: List<String> = listOf("A", "C", "D", "F")

    fun label(rowIndex: Int, columnIndex: Int): String {
        require(rowIndex in 0 until ROWS) { "rowIndex=$rowIndex" }
        require(columnIndex in COLUMNS.indices) { "columnIndex=$columnIndex" }
        return String.format(Locale.US, "%02d%s", rowIndex + 1, COLUMNS[columnIndex])
    }

    private val ALL_SEATS: Set<String> by lazy {
        (0 until ROWS).flatMap { row ->
            COLUMNS.indices.map { column -> label(row, column) }
        }.toSet()
    }

    /** 记录/草稿里的座位是否是这架飞机上真实存在的座位。 */
    fun isValid(seat: String): Boolean = seat in ALL_SEATS
}
