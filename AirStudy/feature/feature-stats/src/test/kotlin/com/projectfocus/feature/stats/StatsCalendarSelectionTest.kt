package com.projectfocus.feature.stats

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class StatsCalendarSelectionTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun date(value: String) = LocalDate.parse(value)
    private fun open(first: String, last: String, maximum: String = "2026-11-03") =
        StatsCalendarSelection.open(StatsDateSelection(date(first)).custom(date(first), date(last)).range(zone), date(maximum))

    @Test fun `opening a leap year clamps future dates and limits to 365 dates`() {
        val range = StatsDateSelection(date("2024-07-10"), StatsPeriod.YEAR).range(zone)
        val calendar = StatsCalendarSelection.open(range, date("2024-12-31"))
        assertEquals(date("2024-01-02"), calendar.first)
        assertEquals(date("2024-12-31"), calendar.last)
        assertEquals(365L, calendar.days)
        val current = StatsCalendarSelection.open(range, date("2024-07-10"))
        assertEquals(date("2024-07-10"), current.last)
        assertEquals(YearMonth.of(2024, 7), current.month)
    }

    @Test fun `changing start beyond end collapses range and activates end`() {
        val next = open("2026-10-01", "2026-10-10").select(date("2026-10-15"))
        assertEquals(date("2026-10-15"), next.first)
        assertEquals(next.first, next.last)
        assertFalse(next.editingFirst)
    }

    @Test fun `changing start to a date more than 365 dates earlier collapses end`() {
        val next = open("2026-10-01", "2026-10-10").select(date("2025-01-01"))
        assertEquals(date("2025-01-01"), next.first)
        assertEquals(next.first, next.last)
    }

    @Test fun `end before start resets both and retains active end field`() {
        val next = open("2026-10-01", "2026-10-10").edit(false).select(date("2026-09-15"))
        assertEquals(date("2026-09-15"), next.first)
        assertEquals(next.first, next.last)
        assertFalse(next.editingFirst)
    }

    @Test fun `end selection is clamped to 365 dates without moving start`() {
        val next = open("2025-01-01", "2025-02-01").edit(false).select(date("2026-10-01"))
        assertEquals(date("2025-01-01"), next.first)
        assertEquals(date("2025-12-31"), next.last)
        assertEquals(365L, next.days)
    }

    @Test fun `future dates and future month navigation do not change selection`() {
        val current = open("2026-11-01", "2026-11-03")
        assertSame(current, current.select(date("2026-11-04")))
        assertFalse(current.canNextMonth)
        assertSame(current, current.moveMonth(1))
        assertEquals(YearMonth.of(2026, 10), current.moveMonth(-1).month)
    }

    @Test fun `historical calendar has no imposed 1970 boundary`() {
        val value = open("1960-01-01", "1960-01-10").moveMonth(-1)
        assertEquals(YearMonth.of(1959, 12), value.month)
    }
}
