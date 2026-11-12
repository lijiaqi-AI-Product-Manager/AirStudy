package com.projectfocus.core.model.flight.freeflight

import com.projectfocus.core.model.flight.PlannedAirport

class CompletedFreeFlight private constructor(val archive: FreeFlightArchive) {
    val sessionId: String get() = archive.sessionId
    val ownerScope: String get() = archive.ownerScope
    val departure: PlannedAirport get() = archive.departure
    val destination: PlannedAirport get() = requireNotNull(archive.landingAirport)
    val elapsedMs: Long get() = archive.clock.activeElapsedMs
    val durationSeconds: Long get() = elapsedMs / 1_000L
    val distanceKm: Double get() = archive.cumulativeDistanceKm
    val endedAtWallMs: Long get() = requireNotNull(archive.endedAtWallMs)
    val displayFocusMinutes: Int get() = Math.max(1L,Math.round(elapsedMs / 60_000.0)).toInt()

    companion object {
        fun from(archive: FreeFlightArchive): CompletedFreeFlight {
            archive.validated()
            require(archive.status == FreeFlightStatus.COMPLETED) { "The flight has no committed arrival result" }
            return CompletedFreeFlight(archive)
        }
    }
}
