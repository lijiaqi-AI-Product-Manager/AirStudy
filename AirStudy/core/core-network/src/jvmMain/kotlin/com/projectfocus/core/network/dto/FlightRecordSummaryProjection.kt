package com.projectfocus.core.network.dto

import com.projectfocus.core.model.flight.PlannedAirport
import java.time.Instant
import java.util.Locale
import kotlinx.serialization.json.*

/** List metadata excludes track samples and device ownership. */
fun FlightRecordArchiveSnapshot.summaryJson(scene: FlightRecordScenePatch? = null): JsonObject {
    validated()
    val down = countdown
    val up = countUp
    return buildJsonObject {
        put("flightRecordId", id); put("recordType", mode.path)
        put("flightStatus", when (down?.status ?: up!!.status.name) {
            "COMPLETED" -> "arrived"
            "CANCELLED" -> "cancelled"
            else -> error("Summary requires a terminal archive")
        })
        put("departureAirport", (down?.departure ?: up!!.departure).summaryJson())
        (down?.destination ?: up?.landingAirport)?.let { put("arrivalAirport", it.summaryJson()) }
        put("flightStartTime", Instant.ofEpochMilli(down?.startTimeMillis ?: up!!.startedAtWallMs).toString())
        (down?.endTimeMillis ?: up?.endedAtWallMs)?.let { put("flightEndTime", Instant.ofEpochMilli(it).toString()) }
        put("durationSeconds", down?.actualFocusMinutes?.toDouble()?.times(60.0) ?: up!!.clock.activeElapsedMs / 1000.0)
        put("flightDistanceKm", down?.distanceKm ?: up!!.cumulativeDistanceKm)
        (down?.seat ?: up?.seat)?.let { put("seatNumber", it) }
        up?.flightNumber?.let { put("flightNumber", it) }
        val selected = scene ?: up?.scene?.takeIf { it.type > 0 }?.let { FlightRecordScenePatch(it.type, it.title.orEmpty()) }
        selected?.let { put("focusIconType", it.focusIconType); put("focusScenario", it.focusScenario) }
    }
}

fun JsonObject.withSummaryScene(scene: FlightRecordScenePatch?, revision: Long): JsonObject = buildJsonObject {
    this@withSummaryScene.forEach { (name, value) -> put(name, value) }
    put("revision", revision)
    scene?.let { put("focusIconType", it.focusIconType); put("focusScenario", it.focusScenario) }
}

private fun PlannedAirport.summaryJson() = buildJsonObject {
    put("code", code); put("name", displayName); put("city", displayCity); put("country", country)
    put("latitude", latitude); put("longitude", longitude)
}
