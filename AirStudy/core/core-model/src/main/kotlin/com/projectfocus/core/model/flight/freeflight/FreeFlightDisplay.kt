package com.projectfocus.core.model.flight.freeflight

import kotlin.math.ceil
import kotlin.math.max

enum class FreeFlightMetricUnit { METRES, KILOMETRES, SECONDS, MINUTES }
data class FreeFlightMetric(val value: Long, val unit: FreeFlightMetricUnit)
data class FreeFlightMetrics(val distance: FreeFlightMetric, val time: FreeFlightMetric)

/** Source a5.e2.e thresholds; retain elapsed and remaining as separate metrics. */
object FreeFlightDisplay {
    fun elapsed(archive: FreeFlightArchive): FreeFlightMetrics = metrics(
        archive.cumulativeDistanceKm, archive.clock.activeElapsedMs / 1000L, true,
    )

    fun remaining(archive: FreeFlightArchive): FreeFlightMetrics? {
        val leg = archive.approach ?: return null
        val flown = max(0L, archive.clock.activeElapsedMs - leg.activeElapsedAtStartMs)
        val remainingMs = max(0L, leg.durationMs - flown)
        val progress = if (leg.durationMs <= 0L) 1.0 else flown.toDouble().div(leg.durationMs).coerceIn(0.0, 1.0)
        return metrics(
            leg.routeDistanceKm * max(0.0, 1.0 - progress),
            ceil(remainingMs.toDouble() / 1000.0).toLong(), false,
        )
    }

    private fun metrics(km: Double, seconds: Long, elapsed: Boolean): FreeFlightMetrics {
        require(km.isFinite() && km >= 0.0 && seconds >= 0L)
        val metres = Math.round(km * 1000.0).coerceAtLeast(0L)
        val distance = if (metres < (if (elapsed) 10_000L else 1_000L))
            FreeFlightMetric(metres, FreeFlightMetricUnit.METRES)
        else FreeFlightMetric(Math.round(metres / 1000.0), FreeFlightMetricUnit.KILOMETRES)
        val time = if (seconds < (if (elapsed) 100L else 60L))
            FreeFlightMetric(seconds, FreeFlightMetricUnit.SECONDS)
        else FreeFlightMetric(seconds / 60L, FreeFlightMetricUnit.MINUTES)
        return FreeFlightMetrics(distance, time)
    }
}
