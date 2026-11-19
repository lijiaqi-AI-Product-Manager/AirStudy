package com.projectfocus.core.network.dto

import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

data class FlightRecordSummaryQuery(val page: Int = 1, val limit: Int = 100,
    val timerType: String = "all", val status: String = "all") {
    fun validated() = apply {
        require(page in 1..1_000_000 && limit in 1..100)
        require(timerType in setOf("all", "down", "up"))
        require(status in setOf("all", "arrived", "in_progress", "cancelled"))
    }
}

@Serializable
data class FlightRecordSummaryPagination(val totalPages: Int, val totalCount: Int,
    val page: Int = 1, val limit: Int = 100)

@Serializable
data class FlightRecordSummaryPage(val records: List<JsonObject>, val pagination: FlightRecordSummaryPagination)

enum class SummaryFlightStatus { ARRIVED, IN_PROGRESS, LANDING, CANCELLED, PENDING, UNKNOWN }

data class SummaryAirport(val code: String?, val name: String?, val city: String?, val country: String?,
    val latitude: Double?, val longitude: Double?)

/** A list entry can contain useful facts without containing a resumable flight archive. */
data class FlightRecordSummary private constructor(
    val raw: JsonObject,
    val mode: FlightRecordMode,
    val id: String,
    val status: SummaryFlightStatus,
    val departure: SummaryAirport?,
    val arrival: SummaryAirport?,
    val flightNumber: String?,
    val seat: String?,
    val startedAtWallMs: Long?,
    val endedAtWallMs: Long?,
    val durationSeconds: Double?,
    val distanceKm: Double?,
    val sceneType: Int?,
    val sceneLabel: String?,
    val delayMinutes: Int?,
    val delayed: Boolean?,
    val revision: Long?,
) {
    companion object {
        fun parse(value: JsonObject): FlightRecordSummary {
            fun text(vararg names: String) = names.firstNotNullOfOrNull { value.text(it) }
            val id = requireNotNull(text("flightRecordId", "id"))
            require(id.length <= 128 && id.none(Char::isISOControl))
            val type = text("recordType", "record_type", "timerType", "timer_type", "flightType", "flight_type")
                ?.lowercase(Locale.ROOT)
            val free = listOf("freeFlightMode", "free_flight_mode", "isFreeFlight", "is_free_flight")
                .any { (value[it] as? JsonPrimitive)?.booleanOrNull == true }
            val mode = if (type in setOf("up", "countup", "count_up", "free", "free_flight", "flight_up") || type == null && free)
                FlightRecordMode.UP else FlightRecordMode.DOWN
            val status = normalizeStatus(text("flightStatus", "status"))
            fun airport(nested: String, fallbackCode: String): SummaryAirport? {
                val body = value[nested] as? JsonObject
                val code = body?.text("code") ?: text(fallbackCode)
                val lat = body?.number("latitude")?.takeIf { it in -90.0..90.0 }
                val lon = body?.number("longitude")?.takeIf { it in -180.0..180.0 }
                if (body == null && code == null) return null
                return SummaryAirport(code, body?.text("name"), body?.text("city"), body?.text("country"), lat, lon)
            }
            return FlightRecordSummary(value, mode, id, status, airport("departureAirport", "departureAirportCode"),
                airport("arrivalAirport", "arrivalAirportCode"), text("flightNumber"), text("seatNumber"),
                instant(text("flightStartTime")), instant(text("flightEndTime", "endTime", "landingTime")),
                value.number("durationSeconds")?.takeIf { it >= 0.0 },
                (value.number("flightDistanceKm") ?: value.number("distanceKm"))?.takeIf { it >= 0.0 },
                (value["focusIconType"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }, text("focusScenario"),
                (value["delayMinutes"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 },
                (value["isDelayed"] as? JsonPrimitive)?.booleanOrNull,
                (value["revision"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0L })
        }

        fun normalizeStatus(text: String?): SummaryFlightStatus = when (text?.trim()?.lowercase(Locale.ROOT)) {
            "arrived", "completed", "已抵达", "已完成" -> SummaryFlightStatus.ARRIVED
            "in_progress", "flying", "inflight", "飞行中" -> SummaryFlightStatus.IN_PROGRESS
            "landing", "降落中" -> SummaryFlightStatus.LANDING
            "cancelled", "canceled", "已取消", "取消", "abandoned" -> SummaryFlightStatus.CANCELLED
            "pending" -> SummaryFlightStatus.PENDING
            else -> SummaryFlightStatus.UNKNOWN
        }

        private fun instant(text: String?): Long? {
            if (text == null) return null
            return try { Instant.parse(text).toEpochMilli().takeIf { it >= 0L } }
                catch (_: Exception) {
                    try { OffsetDateTime.parse(text).toInstant().toEpochMilli().takeIf { it >= 0L } }
                    catch (_: Exception) { null }
                }
        }
    }
}

private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }
    ?.content?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", true) }

private fun JsonObject.number(name: String): Double? = (this[name] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
