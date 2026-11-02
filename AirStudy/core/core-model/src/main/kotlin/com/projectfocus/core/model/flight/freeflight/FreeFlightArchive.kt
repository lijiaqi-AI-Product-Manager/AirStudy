package com.projectfocus.core.model.flight.freeflight

import com.projectfocus.core.model.flight.PlannedAirport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json


data class FreeFlightStartRequest(
    val sessionId: String,
    val ownerScope: String,
    val departure: PlannedAirport,
    val headingDegrees: Double,
    val seat: String,
    val scene: FreeFlightScene,
    val flightNumber: String? = null,
    /** Own shielding adapter; scene type is not a category ID. */
    val shieldingCategoryId: Long? = null,
) {
    init {
        require(sessionId.isNotBlank() && ownerScope.isNotBlank())
        require(headingDegrees.isFinite())
        require(seat.isNotBlank())
        require(shieldingCategoryId == null || shieldingCategoryId >= 0L)
    }
}

@Serializable
data class FreeFlightScene(val type: Int, val title: String?, val iconKey: String?) {
    init { require(type >= 0) }
}

@Serializable
data class FreeFlightPoint(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
    }
}

/** Both real Android clocks are needed: source l4.l.d removes deep sleep. */
data class FreeFlightClockReading(
    val elapsedRealtimeMs: Long,
    val uptimeMillis: Long,
    val wallMs: Long,
    val epochId: String,
) {
    init {
        require(elapsedRealtimeMs >= 0L && uptimeMillis in 0L..elapsedRealtimeMs)
        require(wallMs >= 0L && epochId.isNotBlank())
    }
}

@Serializable
enum class FreeFlightPhase { CRUISING, LANDING_SELECTION, FINAL_APPROACH, NO_MAP_ARRIVAL }

@Serializable
enum class FreeFlightStatus { ONGOING, COMPLETED, CANCELLED }

@Serializable
enum class FreeFlightPause { USER, LANDING_SELECTION, RETURN_CAMERA, RECOVERY_REQUIRES_RESUME, TERMINAL }

@Serializable
data class FreeFlightClock(
    val activeElapsedMs: Long,
    val lastElapsedRealtimeMs: Long,
    val lastUptimeMillis: Long,
    val epochId: String,
    val pause: FreeFlightPause?,
) {
    init {
        require(activeElapsedMs >= 0L)
        require(lastElapsedRealtimeMs >= 0L && lastUptimeMillis in 0L..lastElapsedRealtimeMs)
        require(epochId.isNotBlank())
    }
}

@Serializable
data class FreeFlightCruiseAnchor(
    val point: FreeFlightPoint,
    val bearingDegrees: Double,
    val activeElapsedMs: Long,
    val cumulativeDistanceKm: Double,
    val boundaryCrossings: Long = 0L,
) {
    init {
        require(bearingDegrees.isFinite() && bearingDegrees >= 0.0 && bearingDegrees < 360.0)
        require(activeElapsedMs >= 0L && cumulativeDistanceKm.isFinite() && cumulativeDistanceKm >= 0.0)
        require(boundaryCrossings >= 0L)
    }
}

@Serializable
data class FreeFlightApproach(
    val departurePoint: FreeFlightPoint,
    val airport: PlannedAirport,
    val activeElapsedAtStartMs: Long,
    val cumulativeDistanceAtStartKm: Double,
    /** Exact source/vendor distance, not a Float countdown-plan projection. */
    val routeDistanceKm: Double,
    val durationMs: Long,
    val initialBearingDegrees: Double,
) {
    init {
        require(activeElapsedAtStartMs >= 0L && cumulativeDistanceAtStartKm.isFinite())
        require(cumulativeDistanceAtStartKm >= 0.0)
        require(routeDistanceKm.isFinite() && routeDistanceKm >= 0.0 && durationMs >= 0L)
        require(initialBearingDegrees.isFinite())
    }
}

/**
 * Own durable representation of source freeFlightMode fields.
 * The append-only position samples live in a separate table, not a rewritten JSON array.
 */
@Serializable
data class FreeFlightArchive(
    val formatVersion: Int = 1,
    val sessionId: String,
    val ownerScope: String,
    val revision: Long,
    val departure: PlannedAirport,
    val seat: String,
    val scene: FreeFlightScene,
    val flightNumber: String?,
    val shieldingCategoryId: Long?,
    val startedAtWallMs: Long,
    val updatedAtWallMs: Long,
    val endedAtWallMs: Long?,
    val status: FreeFlightStatus,
    val phase: FreeFlightPhase,
    val clock: FreeFlightClock,
    val position: FreeFlightPoint,
    val bearingDegrees: Double,
    val cumulativeDistanceKm: Double,
    val cruiseAnchor: FreeFlightCruiseAnchor,
    val landingAirport: PlannedAirport? = null,
    val landingRangeMinutes: Int? = null,
    val landingSelectionGeneration: Long = 0L,
    val approach: FreeFlightApproach? = null,
    val sampleSequence: Long = 0L,
    val remoteRecordId: String? = null,
    /** Source persists CRUISING before u(...1500ms) resumes E/K. Own durable callback fence. */
    val cruiseReturnPending: Boolean = false,
) {
    fun validated(): FreeFlightArchive = apply {
        require(formatVersion == 1)
        require(sessionId.isNotBlank() && ownerScope.isNotBlank() && seat.isNotBlank())
        require(revision >= 0L && sampleSequence >= 0L && landingSelectionGeneration >= 0L)
        require(startedAtWallMs >= 0L && updatedAtWallMs >= 0L)
        require(cumulativeDistanceKm.isFinite() && cumulativeDistanceKm >= 0.0)
        require(bearingDegrees.isFinite() && bearingDegrees >= 0.0 && bearingDegrees < 360.0)
        require(shieldingCategoryId == null || shieldingCategoryId >= 0L)
        require(clock.activeElapsedMs >= cruiseAnchor.activeElapsedMs)
        if (cruiseReturnPending) {
            require(status == FreeFlightStatus.ONGOING && phase == FreeFlightPhase.CRUISING)
            require(clock.pause in setOf(null,FreeFlightPause.USER,FreeFlightPause.RETURN_CAMERA,FreeFlightPause.RECOVERY_REQUIRES_RESUME))
        }
        if (clock.pause == FreeFlightPause.RETURN_CAMERA) require(cruiseReturnPending)
        require(landingRangeMinutes == null || landingRangeMinutes >= 0)
        if (status == FreeFlightStatus.ONGOING) require(endedAtWallMs == null)
        else require(endedAtWallMs != null && clock.pause != null)
        if (phase == FreeFlightPhase.LANDING_SELECTION) {
            require(clock.pause != null && landingRangeMinutes != null && approach == null)
        }
        if (phase == FreeFlightPhase.FINAL_APPROACH) {
            require(approach != null && landingAirport == approach.airport)
            require(clock.activeElapsedMs >= approach.activeElapsedAtStartMs)
        } else require(approach == null)
        if (phase == FreeFlightPhase.NO_MAP_ARRIVAL) require(landingAirport != null && landingRangeMinutes == null)
        if (status == FreeFlightStatus.COMPLETED) {
            require(phase in setOf(FreeFlightPhase.FINAL_APPROACH, FreeFlightPhase.NO_MAP_ARRIVAL) && landingAirport != null)
        }
    }
}

data class FreeFlightPositionSample(
    val sessionId: String,
    val sequence: Long,
    val activeElapsedMs: Long,
    val point: FreeFlightPoint,
    val bearingDegrees: Double,
    val cumulativeDistanceKm: Double,
    val wallMs: Long,
)

/** No wall-clock/offline catch-up is hidden in JSON deserialization. */
object FreeFlightArchiveCodec {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    fun encode(archive: FreeFlightArchive): String = json.encodeToString(FreeFlightArchive.serializer(), archive.validated())
    fun decode(text: String): FreeFlightArchive =
        json.decodeFromString(FreeFlightArchive.serializer(), text).validated()
}
