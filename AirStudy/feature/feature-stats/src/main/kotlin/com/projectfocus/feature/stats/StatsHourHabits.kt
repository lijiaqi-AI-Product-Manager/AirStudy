package com.projectfocus.feature.stats

import com.projectfocus.service.focus.history.RecordedFlight
import java.time.Instant
import java.time.ZoneId
import java.util.Collections

class StatsHourHabits private constructor(values: List<Double>) {
    val minutes: List<Double> = Collections.unmodifiableList(ArrayList(values))
    val peakHour: Int? = minutes.maxOrNull()?.takeIf { it > 0.0 }?.let(minutes::indexOf)
    val activeHours: Int = minutes.count { it > 0.0 }
    override fun equals(other: Any?) = other is StatsHourHabits && minutes == other.minutes
    override fun hashCode() = minutes.hashCode()

    companion object {
        private const val HOUR = 3_600_000L
        private const val DAY = HOUR * 24
        fun calculate(records: List<RecordedFlight>, range: StatsDateRange, zone: ZoneId): StatsHourHabits {
            val bins = DoubleArray(24)
            for (record in records) {
                if (!record.completed || record.recordedAtWallMs !in range) continue
                val duration = when (record) {
                    is RecordedFlight.Countdown -> record.record.actualFocusMinutes.coerceAtLeast(0).toLong() * 60_000L
                    is RecordedFlight.CountUp -> record.archive.clock.activeElapsedMs
                }
                var cursor = record.recordedAtWallMs
                val until = Math.addExact(cursor, duration)
                while (cursor < until) {
                    val instant = Instant.ofEpochMilli(cursor)
                    val offset = zone.rules.getOffset(instant).totalSeconds * 1000L
                    val nextTransition = zone.rules.nextTransition(instant)?.instant?.toEpochMilli()
                    val segmentEnd = minOf(until, nextTransition?.takeIf { it > cursor } ?: until)
                    val local = Math.addExact(cursor, offset)
                    val wholeDays = if (Math.floorMod(local, HOUR) == 0L) (segmentEnd - cursor) / DAY else 0L
                    if (wholeDays > 0) {
                        for (hour in 0..23) bins[hour] += wholeDays * 60.0
                        cursor += wholeDays * DAY
                    } else {
                        val hour = Math.floorMod(Math.floorDiv(local, HOUR), 24L).toInt()
                        val nextHour = (Math.floorDiv(local, HOUR) + 1L) * HOUR - offset
                        val end = minOf(segmentEnd, nextHour)
                        check(end > cursor)
                        bins[hour] += (end - cursor) / 60_000.0
                        cursor = end
                    }
                }
            }
            require(bins.all { it.isFinite() && it >= 0.0 })
            return StatsHourHabits(bins.toList())
        }
    }
}
