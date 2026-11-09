package com.projectfocus.service.focus.history

import com.projectfocus.core.database.AppDatabase
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.PlannedFlightMetadataCodec
import com.projectfocus.core.model.flight.PlannedScene
import com.projectfocus.core.model.flight.freeflight.FreeFlightPositionSample
import com.projectfocus.core.network.dto.*
import com.projectfocus.core.network.record.RecordSceneWireProjection
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

internal data class CapturedCloudRecord(val record: RecordedFlight, val upload: FlightRecordArchiveUpload, val fingerprint: String)

@Singleton
class RecordCloudSnapshots @Inject constructor(private val db: AppDatabase, private val scenes: RecordSceneWireProjection) {
    internal suspend fun capture(account: HistoryAccount, key: FlightRecordKey, remoteId: String? = null): CapturedCloudRecord? {
        val record: RecordedFlight = when (key.kind) {
            FlightRecordKind.COUNTDOWN -> {
                val row = db.flightHistoryDao().findById(key.id) ?: return null
                if (row.ownerScope != account.scope || row.status !in setOf("COMPLETED", "CANCELLED")) return null
                RecordedFlight.Countdown(row, edited(account, key, row.startTimeMillis))
            }
            FlightRecordKind.COUNT_UP -> {
                val row = db.freeFlightArchiveDao().get(key.id, account.scope) ?: return null
                if (row.activeSlot != null || row.status !in setOf("COMPLETED", "CANCELLED")) return null
                val archive = decodeHistoryArchive(row)
                RecordedFlight.CountUp(archive, edited(account, key, archive.startedAtWallMs), row)
            }
        }
        val archive: FlightRecordArchiveSnapshot
        val originalScene: PlannedScene?
        val localMetadata: String
        when (record) {
            is RecordedFlight.Countdown -> {
                val row = record.record
                val id = remoteId ?: row.id
                archive = FlightRecordArchiveSnapshot(FlightRecordMode.DOWN, id, CountdownRecordArchive(
                    id, row.roomId, row.focusType, row.roomParticipantCount,
                    PlannedAirport(0, row.departureAirportCode, row.departureAirportName, row.departureCity,
                        row.departureCountry, row.departureLatitude, row.departureLongitude),
                    PlannedAirport(0, row.destinationAirportCode, row.destinationAirportName, row.destinationCity,
                        row.destinationCountry, row.destinationLatitude, row.destinationLongitude),
                    row.startTimeMillis, row.endTimeMillis, row.durationMinutes, row.actualFocusMinutes, row.distanceKm,
                    row.status, row.seat, row.createdAt, row.updatedAt, row.logJson, row.quote, row.quoteAuthor,
                    row.sceneSnapshotJson, row.plannedDepartureJson, row.hasAnnouncedDeparture, row.hasAnnouncedDescent, row.hasAnnouncedArrival))
                originalScene = PlannedFlightMetadataCodec.decodeSceneOrNull(row.sceneSnapshotJson)
                localMetadata = listOf(row.id, row.ownerScope, row.categoryId, row.isSynced, row.syncedAt).toString()
            }
            is RecordedFlight.CountUp -> {
                val value = record.archive
                val id = remoteId ?: value.remoteRecordId?.trim()?.takeIf(String::isNotBlank) ?: value.sessionId
                val positions = db.freeFlightArchiveDao().positions(key.id, account.scope).map {
                    FreeFlightPositionSample(it.sessionId, it.sequence, it.activeElapsedMs,
                        com.projectfocus.core.model.flight.freeflight.FreeFlightPoint(it.latitude, it.longitude),
                        it.bearingDegrees, it.cumulativeDistanceKm, it.wallMs)
                }
                archive = FlightRecordArchiveSnapshot(FlightRecordMode.UP, id, countUp = value.copy(remoteRecordId = id), positions = positions)
                originalScene = value.scene.iconKey?.let {
                    PlannedScene("history-" + value.scene.type, value.scene.title.orEmpty(), it, 0, true, displayName = value.scene.title)
                }
                localMetadata = requireNotNull(record.storedRow).toString()
            }
        }
        val upload = FlightRecordArchiveUpload(archive.withoutDeviceOwnership(),
            (record.editedScene?.snapshot ?: originalScene)?.let(scenes::project))
        // Locale-dependent wire labels must not invalidate a durable record confirmation.
        val text = Json.encodeToString(FlightRecordArchiveSnapshot.serializer(), upload.archive) + "\n" + localMetadata + "\n" + record.editedScene
        return CapturedCloudRecord(record, upload, digest(text))
    }

    private suspend fun edited(account: HistoryAccount, key: FlightRecordKey, start: Long): RecordedScene? {
        val row = db.flightRecordSceneDao().find(key.kind.name, key.id) ?: return null
        check(row.ownerScope == account.scope && row.startedAtWallMs == start)
        return RecordedScene(requireNotNull(PlannedFlightMetadataCodec.decodeSceneOrNull(row.sceneJson)), row.revision)
    }

    internal companion object {
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
