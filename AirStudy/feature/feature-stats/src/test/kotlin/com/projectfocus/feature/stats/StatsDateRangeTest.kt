package com.projectfocus.feature.stats

import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsDateRangeTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val newYork = ZoneId.of("America/New_York")
    private fun date(value: String) = LocalDate.parse(value)
    private fun wall(value: String, zone: ZoneId = shanghai) = date(value).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test fun `default week begins Monday and excludes next Monday`() {
        val selected = StatsDateSelection.current(wall("2026-11-03"), shanghai)
        assertEquals(StatsPeriod.WEEK, selected.period)
        val range = selected.range(shanghai)
        assertEquals(date("2026-11-02"), range.firstDate)
        assertEquals(date("2026-11-08"), range.lastDate)
        assertTrue(wall("2026-11-02") in range)
        assertTrue(wall("2026-11-09") - 1 in range)
        assertFalse(wall("2026-11-02") - 1 in range)
        assertFalse(wall("2026-11-09") in range)
    }

    @Test fun `Sunday belongs to the week beginning six days earlier`() {
        val range = StatsDateSelection(date("2026-11-01")).range(shanghai)
        assertEquals(date("2026-10-26"), range.firstDate)
        assertEquals(date("2026-11-01"), range.lastDate)
    }

    @Test fun `month spans whole leap February`() {
        val range = StatsDateSelection(date("2024-02-13"), StatsPeriod.MONTH).range(shanghai)
        assertEquals(wall("2024-02-01"), range.fromWallMs)
        assertEquals(wall("2024-03-01"), range.untilWallMs)
        assertEquals(date("2024-02-29"), range.lastDate)
    }

    @Test fun `year spans all leap days and remains independent of anchor month`() {
        val range = StatsDateSelection(date("2024-11-03"), StatsPeriod.YEAR).range(shanghai)
        assertEquals(wall("2024-01-01"), range.fromWallMs)
        assertEquals(wall("2025-01-01"), range.untilWallMs)
        assertEquals(366L, Duration.ofMillis(range.untilWallMs - range.fromWallMs).toDays())
    }

    @Test fun `spring daylight saving day contains twenty three hours`() {
        val range = StatsDateSelection(date("2026-03-08"), StatsPeriod.DAY).range(newYork)
        assertEquals(23L, Duration.ofMillis(range.untilWallMs - range.fromWallMs).toHours())
        assertEquals(wall("2026-03-09", newYork), range.untilWallMs)
    }

    @Test fun `autumn daylight saving day contains twenty five hours`() {
        val range = StatsDateSelection(date("2026-11-01"), StatsPeriod.DAY).range(newYork)
        assertEquals(25L, Duration.ofMillis(range.untilWallMs - range.fromWallMs).toHours())
        assertEquals(wall("2026-11-02", newYork), range.untilWallMs)
    }

    @Test fun `custom last date includes its complete local day`() {
        val selected = StatsDateSelection(date("2026-10-20")).custom(date("2026-10-31"), date("2026-11-01"))
        val range = selected.range(newYork)
        assertEquals(StatsPeriod.CUSTOM, selected.period)
        assertEquals(49L, Duration.ofMillis(range.untilWallMs - range.fromWallMs).toHours())
        assertTrue(wall("2026-11-02", newYork) - 1 in range)
        assertFalse(wall("2026-11-02", newYork) in range)
    }

    @Test fun `custom single date is allowed`() {
        val selected = StatsDateSelection(date("2026-11-03")).custom(date("2026-11-03"), date("2026-11-03"))
        assertEquals(1L, Duration.ofMillis(selected.range(shanghai).untilWallMs - selected.range(shanghai).fromWallMs).toDays())
    }

    @Test(expected = IllegalArgumentException::class) fun `reversed custom dates are rejected`() {
        StatsDateSelection(date("2026-11-03")).custom(date("2026-11-03"), date("2026-11-02"))
    }

    @Test fun `current period cannot navigate into the future`() {
        val today = wall("2026-11-03")
        for (period in listOf(StatsPeriod.DAY, StatsPeriod.WEEK, StatsPeriod.MONTH, StatsPeriod.YEAR)) {
            val selected = StatsDateSelection(date("2026-11-03"), period)
            assertFalse(selected.canMoveForward(today, shanghai))
            assertSame(selected, selected.move(1, today, shanghai))
        }
    }

    @Test fun `previous week returns to current week and stops there`() {
        val today = wall("2026-11-03")
        val selected = StatsDateSelection.current(today, shanghai)
        val previous = selected.move(-1, today, shanghai)
        assertEquals(date("2026-10-26"), previous.range(shanghai).firstDate)
        assertTrue(previous.canMoveForward(today, shanghai))
        assertEquals(selected, previous.move(1, today, shanghai))
    }

    @Test fun `custom range does not expose period navigation`() {
        val selected = StatsDateSelection(date("2026-11-03")).custom(date("2025-01-01"), date("2025-12-31"))
        assertFalse(selected.canMoveForward(wall("2026-11-03"), shanghai))
        assertSame(selected, selected.move(-1, wall("2026-11-03"), shanghai))
        assertSame(selected, selected.move(1, wall("2026-11-03"), shanghai))
    }

    @Test fun `selecting another period returns to its current dates`() {
        val historical = StatsDateSelection(date("2025-01-02"), StatsPeriod.DAY)
        val current = historical.selectPeriod(StatsPeriod.MONTH, wall("2026-11-03"), shanghai)
        assertEquals(date("2026-11-01"), current.range(shanghai).firstDate)
        assertEquals(date("2026-11-30"), current.range(shanghai).lastDate)
        assertFalse(current.canMoveForward(wall("2026-11-03"), shanghai))
    }

    @Test fun `custom range accepts exactly three hundred sixty five calendar dates`() {
        val range = StatsDateSelection(date("2024-01-01")).custom(date("2024-01-01"), date("2024-12-30")).range(newYork)
        assertEquals(date("2024-12-30"), range.lastDate)
        assertTrue(wall("2024-12-31", newYork) - 1 in range)
    }

    @Test(expected = IllegalArgumentException::class) fun `custom range rejects a full leap year`() {
        StatsDateSelection(date("2024-01-01")).custom(date("2024-01-01"), date("2024-12-31"))
    }
}
