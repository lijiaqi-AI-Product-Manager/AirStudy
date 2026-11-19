package com.projectfocus.feature.stats

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

data class StatsCalendarSelection(
    val first: LocalDate,
    val last: LocalDate,
    val maximum: LocalDate,
    val editingFirst: Boolean = true,
    val month: YearMonth = YearMonth.from(last),
) {
    init {
        require(!last.isBefore(first) && !last.isAfter(maximum))
        require(ChronoUnit.DAYS.between(first, last) < StatsDateSelection.MAX_CUSTOM_DAYS)
        require(month <= YearMonth.from(maximum))
    }
    val days: Long get() = ChronoUnit.DAYS.between(first, last) + 1
    val canNextMonth: Boolean get() = month < YearMonth.from(maximum)
    fun edit(firstField: Boolean) = copy(editingFirst = firstField)
    fun moveMonth(direction: Int): StatsCalendarSelection {
        require(direction in -1..1)
        if (direction > 0 && !canNextMonth) return this
        return copy(month = month.plusMonths(direction.toLong()))
    }
    fun select(date: LocalDate): StatsCalendarSelection {
        if (date.isAfter(maximum)) return this
        if (editingFirst) {
            val end = if (last.isBefore(date) || ChronoUnit.DAYS.between(date, last) >= StatsDateSelection.MAX_CUSTOM_DAYS) date else last
            return copy(first = date, last = end, editingFirst = false)
        }
        if (date.isBefore(first)) return copy(first = date, last = date)
        return copy(last = minOf(date, maximum, first.plusDays(StatsDateSelection.MAX_CUSTOM_DAYS - 1)))
    }
    companion object {
        fun open(range: StatsDateRange, maximum: LocalDate): StatsCalendarSelection {
            val end = minOf(range.lastDate, maximum)
            val start = maxOf(minOf(range.firstDate, end), end.minusDays(StatsDateSelection.MAX_CUSTOM_DAYS - 1))
            return StatsCalendarSelection(start, end, maximum)
        }
    }
}
