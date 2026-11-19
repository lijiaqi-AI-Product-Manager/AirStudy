package com.projectfocus.feature.flight.hud

import com.projectfocus.core.model.flight.freeflight.FreeFlightArchive
import com.projectfocus.core.model.flight.freeflight.FreeFlightDisplay
import com.projectfocus.core.model.flight.freeflight.FreeFlightMetric
import com.projectfocus.core.model.flight.freeflight.FreeFlightMetricUnit
import com.projectfocus.core.model.flight.freeflight.FreeFlightMetrics

/** Typed values from actual Core/archive progress; formatting follows source a5.e2.d/e. */
data class FlightHudCounters(val metrics: FreeFlightMetrics, val elapsed: Boolean) {
    companion object {
        fun remaining(distanceKm: Double, seconds: Long): FlightHudCounters =
            core(distanceKm, seconds, false)

        fun coreElapsed(distanceKm: Double, seconds: Long): FlightHudCounters =
            core(distanceKm, seconds, true)

        fun freeFlightElapsed(archive: FreeFlightArchive): FlightHudCounters =
            FlightHudCounters(FreeFlightDisplay.elapsed(archive), true)

        fun freeFlightRemaining(archive: FreeFlightArchive): FlightHudCounters? =
            FreeFlightDisplay.remaining(archive)?.let { FlightHudCounters(it, false) }

        private fun core(distanceKm: Double, seconds: Long, elapsed: Boolean): FlightHudCounters {
            require(distanceKm.isFinite() && distanceKm >= 0.0 && seconds >= 0L)
            val metres = Math.round(distanceKm * 1000.0)
            val distance = if (metres < 1_000L) FreeFlightMetric(metres, FreeFlightMetricUnit.METRES)
            else FreeFlightMetric(Math.round(metres / 1000.0), FreeFlightMetricUnit.KILOMETRES)
            val time = if (seconds < (if (elapsed) 100L else 60L)) FreeFlightMetric(seconds, FreeFlightMetricUnit.SECONDS)
            else FreeFlightMetric(seconds / 60L, FreeFlightMetricUnit.MINUTES)
            return FlightHudCounters(FreeFlightMetrics(distance, time), elapsed)
        }
    }
}
