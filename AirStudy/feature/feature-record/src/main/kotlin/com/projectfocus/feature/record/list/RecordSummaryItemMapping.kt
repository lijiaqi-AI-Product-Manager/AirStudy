package com.projectfocus.feature.record.list

import com.projectfocus.core.network.dto.*
import com.projectfocus.service.focus.history.FlightRecordKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object RecordSummaryItemMapping {
    fun item(record: FlightRecordSummary, zone: ZoneId): RecordItem {
        val start = record.startedAtWallMs?.let { Instant.ofEpochMilli(it).atZone(zone) }
        val minutes = record.durationSeconds?.let { Math.round(it / 60.0).coerceIn(0, Int.MAX_VALUE.toLong()).toInt() }
        val status = when (record.status) {
            SummaryFlightStatus.ARRIVED -> FlightStatus.COMPLETED
            SummaryFlightStatus.CANCELLED -> FlightStatus.CANCELLED
            SummaryFlightStatus.IN_PROGRESS, SummaryFlightStatus.LANDING -> FlightStatus.ONGOING
            SummaryFlightStatus.PENDING, SummaryFlightStatus.UNKNOWN -> FlightStatus.UNKNOWN
        }
        val arrival = record.arrival?.code?.takeIf { it.isNotBlank() }
        return RecordItem(record.id, record.departure?.code ?: "---", arrival ?: "---",
            record.departure?.city ?: record.departure?.name ?: record.departure?.code ?: "—",
            record.arrival?.city ?: record.arrival?.name ?: arrival.orEmpty(),
            start?.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)) ?: "—", "",
            record.distanceKm?.let { "${RecordListBuilder.km(it)} km" } ?: "—",
            minutes?.let(RecordListBuilder::formatMinutes) ?: "—", status, if (status == FlightStatus.COMPLETED) 1f else 0f,
            kind = if (record.mode == FlightRecordMode.DOWN) FlightRecordKind.COUNTDOWN else FlightRecordKind.COUNT_UP,
            dateText = start?.format(DateTimeFormatter.ofPattern("ddMMM", Locale.ENGLISH))?.uppercase(Locale.ENGLISH),
            flightNumber = record.flightNumber, seat = record.seat, sceneTitle = record.sceneLabel,
            sceneType = record.sceneType, distanceKm = record.distanceKm, focusMinutes = minutes,
            arrivalSelected = arrival != null, recordedAtWallMs = record.startedAtWallMs, cloudSummary = record)
    }
}
