package com.projectfocus.feature.record.list

import com.projectfocus.core.model.flight.PlannedFlightMetadataCodec
import com.projectfocus.service.focus.history.RecordedFlight
import com.projectfocus.feature.scene.source.SourceSceneCatalog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object RecordItemMapping {
    private val time = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val date = DateTimeFormatter.ofPattern("ddMMM", Locale.ENGLISH)

    fun item(record: RecordedFlight, zone: ZoneId): RecordItem {
        val legacy = (record as? RecordedFlight.Countdown)?.record
        val archive = (record as? RecordedFlight.CountUp)?.archive
        val start = Instant.ofEpochMilli(legacy?.startTimeMillis ?: requireNotNull(archive).startedAtWallMs).atZone(zone)
        val state = legacy?.status ?: requireNotNull(archive).status.name
        val status = FlightStatus.entries.firstOrNull { it.dbValue == state && it != FlightStatus.UNKNOWN }
            ?: error("Unsupported stored flight status")
        val scene = record.editedScene?.snapshot
            ?: legacy?.let { PlannedFlightMetadataCodec.decodeSceneOrNull(it.sceneSnapshotJson) }
        val fromCode = legacy?.departureAirportCode ?: requireNotNull(archive).departure.code
        val fromCity = legacy?.departureCity ?: requireNotNull(archive).departure.displayCity
        val toCode = record.destinationCode.orEmpty()
        val toCity = legacy?.destinationCity ?: archive?.landingAirport?.displayCity.orEmpty()
        val selected = toCode.isNotBlank() && toCode !in setOf("UNKNOWN", "---", "—")
        val ratio = when {
            legacy == null -> if (record.completed) 1f else 0f
            legacy.durationMinutes <= 0 -> 1f
            else -> (legacy.actualFocusMinutes.toFloat() / legacy.durationMinutes).coerceIn(0f, 1f)
        }
        return RecordItem(
            id = record.key.id,
            fromCode = fromCode.ifBlank { "---" },
            toCode = if (selected) toCode else "---",
            fromCity = fromCity.ifBlank { fromCode },
            toCity = if (selected) toCity.ifBlank { toCode } else "",
            startTime = time.format(start),
            durationText = legacy?.let { RecordListBuilder.formatMinutes(it.durationMinutes) }.orEmpty(),
            distanceText = "${RecordListBuilder.km(record.distanceKm)} km",
            focusText = RecordListBuilder.formatMinutes(record.focusMinutes),
            status = status,
            completionRatio = ratio,
            kind = record.key.kind,
            dateText = date.format(start).uppercase(Locale.ENGLISH),
            flightNumber = archive?.flightNumber?.takeIf { it.isNotBlank() },
            seat = (legacy?.seat ?: archive?.seat)?.takeIf { it.isNotBlank() },
            sceneTitle = (scene?.displayName?.takeIf { it.isNotBlank() } ?: scene?.name ?: archive?.scene?.title)
                ?.takeIf { it.isNotBlank() },
            sceneIconKey = (scene?.iconKey ?: archive?.scene?.iconKey)?.takeIf { it.isNotBlank() },
            sceneType = scene?.let { SourceSceneCatalog.type(it.iconKey).takeIf { type -> type > 0 } ?: 1 }
                ?: archive?.scene?.type,
            distanceKm = record.distanceKm,
            focusMinutes = record.focusMinutes,
            arrivalSelected = selected,
            recordedAtWallMs = record.recordedAtWallMs,
            sceneRevision = record.editedScene?.revision ?: 0L,
            sceneOptionId = scene?.optionId,
        )
    }
}
