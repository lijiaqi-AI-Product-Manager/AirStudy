package com.projectfocus.core.network.dto

import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.freeflight.FreeFlightArchive
import com.projectfocus.core.model.flight.freeflight.FreeFlightStatus
import com.projectfocus.core.model.flight.freeflight.FreeFlightPositionSample
import kotlinx.serialization.Serializable

/** Terminal record data; device ownership and category foreign keys stay local. */
@Serializable
data class CountdownRecordArchive(
    val id: String,
    val roomId: String? = null,
    val focusType: String,
    val roomParticipantCount: Int? = null,
    val departure: PlannedAirport,
    val destination: PlannedAirport,
    val startTimeMillis: Long,
    val endTimeMillis: Long? = null,
    val durationMinutes: Int,
    val actualFocusMinutes: Int,
    val distanceKm: Double,
    val status: String,
    val seat: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val logJson: String? = null,
    val quote: String? = null,
    val quoteAuthor: String? = null,
    val sceneSnapshotJson: String? = null,
    val plannedDepartureJson: String? = null,
    val hasAnnouncedDeparture: Boolean = false,
    val hasAnnouncedDescent: Boolean = false,
    val hasAnnouncedArrival: Boolean = false,
) {
    fun validated(): CountdownRecordArchive = apply {
        require(id.isNotBlank() && id.length <= 128 && id == id.trim())
        require(focusType in setOf("SOLO","GROUP","ROOM"))
        require(status in setOf("COMPLETED","CANCELLED"))
        require(durationMinutes > 0 && actualFocusMinutes in 0..durationMinutes)
        require(distanceKm.isFinite() && distanceKm >= 0)
        require(startTimeMillis >= 0 && createdAt >= 0 && updatedAt >= 0)
        require(endTimeMillis == null || endTimeMillis >= 0)
        require(roomParticipantCount == null || roomParticipantCount >= 0)
        require(logJson == null || logJson.length <= 262_144)
        require(sceneSnapshotJson == null || sceneSnapshotJson.length <= 65_536)
        require(plannedDepartureJson == null || plannedDepartureJson.length <= 65_536)
        require(seat == null || seat.length <= 32)
        require(quote == null || quote.length <= 4096)
        require(quoteAuthor == null || quoteAuthor.length <= 512)
    }
}

@Serializable
data class FlightRecordArchiveSnapshot(
    val mode: FlightRecordMode,
    val id: String,
    val countdown: CountdownRecordArchive? = null,
    val countUp: FreeFlightArchive? = null,
    val positions: List<FreeFlightPositionSample> = emptyList(),
) {
    fun validated(): FlightRecordArchiveSnapshot = apply {
        require(id.isNotBlank() && id.length <= 128 && id == id.trim())
        when (mode) {
            FlightRecordMode.DOWN -> {
                require(countUp == null && countdown != null && positions.isEmpty())
                require(countdown.validated().id == id)
            }
            FlightRecordMode.UP -> {
                require(countdown == null && countUp != null)
                countUp.validated()
                require(countUp.status != FreeFlightStatus.ONGOING)
                require(id == (countUp.remoteRecordId?.trim()?.takeIf(String::isNotEmpty) ?: countUp.sessionId))
                require(positions.size <= 100_000)
                var previous = 0L
                for (position in positions) {
                    require(position.sessionId == countUp.sessionId && position.sequence > previous && position.sequence <= countUp.sampleSequence)
                    require(position.activeElapsedMs in 0L..countUp.clock.activeElapsedMs && position.wallMs >= 0L)
                    require(position.bearingDegrees.isFinite() && position.bearingDegrees in 0.0..<360.0)
                    require(position.cumulativeDistanceKm.isFinite() && position.cumulativeDistanceKm >= 0.0)
                    previous = position.sequence
                }
            }
        }
    }

    fun withoutDeviceOwnership(): FlightRecordArchiveSnapshot {
        validated()
        return copy(countUp=countUp?.copy(ownerScope="cloud",shieldingCategoryId=null)).validated()
    }
}

@Serializable
data class CloudFlightRecord(
    val mode: FlightRecordMode,
    val id: String,
    val revision: Long,
    val deleted: Boolean,
    val archive: FlightRecordArchiveSnapshot? = null,
    val scene: FlightRecordScenePatch? = null,
)

@Serializable
data class FlightRecordResponse<T>(val success: Boolean, val message: String = "", val data: T? = null)

@Serializable
data class FlightRecordMutationReceipt(val revision: Long)

@Serializable
data class CloudFlightRecordPage(
    val items: List<CloudFlightRecord>,
    val page: Int = 0,
    val hasMore: Boolean,
    val nextCursor: Long = 0L,
)

@Serializable
data class FlightRecordArchiveUpload(
    val archive: FlightRecordArchiveSnapshot,
    val scene: FlightRecordScenePatch? = null,
)
