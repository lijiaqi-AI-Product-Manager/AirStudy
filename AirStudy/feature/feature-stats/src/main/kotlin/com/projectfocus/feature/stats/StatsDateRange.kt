package com.projectfocus.feature.stats

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

enum class StatsPeriod { DAY, WEEK, MONTH, YEAR, CUSTOM }

data class StatsDateRange(
    val firstDate: LocalDate,
    val lastDate: LocalDate,
    val fromWallMs: Long,
    val untilWallMs: Long,
) {
    init {
        require(!lastDate.isBefore(firstDate))
        require(untilWallMs > fromWallMs)
    }

    operator fun contains(wallMs: Long): Boolean = wallMs >= fromWallMs && wallMs < untilWallMs
}

data class StatsDateSelection(
    val anchor: LocalDate,
    val period: StatsPeriod = StatsPeriod.WEEK,
    val customFirst: LocalDate = anchor,
    val customLast: LocalDate = anchor,
) {
    init {
        require(!customLast.isBefore(customFirst))
        require(period != StatsPeriod.CUSTOM || ChronoUnit.DAYS.between(customFirst, customLast) < MAX_CUSTOM_DAYS)
    }

    fun range(zone: ZoneId): StatsDateRange {
        val first = when (period) {
            StatsPeriod.DAY -> anchor
            StatsPeriod.WEEK -> anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            StatsPeriod.MONTH -> anchor.withDayOfMonth(1)
            StatsPeriod.YEAR -> anchor.withDayOfYear(1)
            StatsPeriod.CUSTOM -> customFirst
        }
        val until = when (period) {
            StatsPeriod.DAY -> first.plusDays(1)
            StatsPeriod.WEEK -> first.plusWeeks(1)
            StatsPeriod.MONTH -> first.plusMonths(1)
            StatsPeriod.YEAR -> first.plusYears(1)
            StatsPeriod.CUSTOM -> customLast.plusDays(1)
        }
        return StatsDateRange(first, until.minusDays(1), first.atStartOfDay(zone).toInstant().toEpochMilli(),
            until.atStartOfDay(zone).toInstant().toEpochMilli())
    }

    fun canMoveForward(nowWallMs: Long, zone: ZoneId): Boolean = period != StatsPeriod.CUSTOM &&
        range(zone).untilWallMs <= Instant.ofEpochMilli(nowWallMs).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()

    fun move(direction: Int, nowWallMs: Long, zone: ZoneId): StatsDateSelection {
        require(direction in -1..1)
        if (direction == 0 || period == StatsPeriod.CUSTOM ||
            (direction > 0 && !canMoveForward(nowWallMs, zone))) return this
        val next = when (period) {
            StatsPeriod.DAY -> anchor.plusDays(direction.toLong())
            StatsPeriod.WEEK -> anchor.plusWeeks(direction.toLong())
            StatsPeriod.MONTH -> anchor.plusMonths(direction.toLong())
            StatsPeriod.YEAR -> anchor.plusYears(direction.toLong())
            StatsPeriod.CUSTOM -> anchor
        }
        return copy(anchor = next)
    }

    fun custom(first: LocalDate, last: LocalDate): StatsDateSelection =
        copy(period = StatsPeriod.CUSTOM, customFirst = first, customLast = last)

    fun selectPeriod(next: StatsPeriod, nowWallMs: Long, zone: ZoneId): StatsDateSelection {
        require(next != StatsPeriod.CUSTOM)
        return copy(period = next, anchor = Instant.ofEpochMilli(nowWallMs).atZone(zone).toLocalDate())
    }

    companion object {
        const val MAX_CUSTOM_DAYS = 365L

        fun current(nowWallMs: Long, zone: ZoneId): StatsDateSelection =
            StatsDateSelection(Instant.ofEpochMilli(nowWallMs).atZone(zone).toLocalDate())
    }
}
