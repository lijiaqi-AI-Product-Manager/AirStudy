package com.projectfocus.core.network.plan

import com.projectfocus.core.network.dto.FlightPlanHistoryRequest
import com.projectfocus.core.network.dto.CancelBatchDeleteFlightPlanRequest
import com.projectfocus.core.network.dto.FlightPlanLegDto
import com.projectfocus.core.network.dto.GetFlightPlanRequest
import com.projectfocus.core.network.dto.UpsertFlightPlanRequest
import com.projectfocus.core.network.dto.UpdateFlightPlanLegStatusRequest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Source c0.c: rewrite indices, retain cancelled legs, clamp scene code only on output. */
object FlightPlanRequestCodec {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    fun encode(request: GetFlightPlanRequest): String = json.encodeToString(GetFlightPlanRequest.serializer(), request)
    fun encode(request: FlightPlanHistoryRequest): String = json.encodeToString(FlightPlanHistoryRequest.serializer(), request)
    fun encode(request: CancelBatchDeleteFlightPlanRequest): String = buildJsonObject {
        val normalized = request.copy(planDate = request.planDate.javaTrim(),
            localPlanDate = request.localPlanDate.javaTrim(),
            userScopeId = request.userScopeId?.javaTrim()?.takeIf(String::isNotEmpty))
        json.encodeToJsonElement(CancelBatchDeleteFlightPlanRequest.serializer(), normalized).jsonObject
            .forEach { (key, value) -> put(key, value) }
        // These simultaneous aliases are actually written by the original client.
        put("localPlanDate", normalized.localPlanDate)
        put("target_local_plan_date", normalized.localPlanDate)
        put("targetLocalPlanDate", normalized.localPlanDate)
        put("localToday", normalized.localToday)
        put("localTomorrow", normalized.localTomorrow)
        normalized.userScopeId?.let { put("userScopeId", it) }
    }.toString()
    fun encode(request: UpsertFlightPlanRequest): String = json.encodeToString(UpsertFlightPlanRequest.serializer(),
        request.copy(legs = normalize(request.legs)))

    /** Source trims optional text and omits blank/Int.MIN_VALUE, rather than sending nulls. */
    fun encode(request: UpdateFlightPlanLegStatusRequest): String = json.encodeToString(
        UpdateFlightPlanLegStatusRequest.serializer(), request.copy(
            localPlanDate = request.localPlanDate.javaTrim(),
            targetStatus = request.targetStatus.javaTrim(),
            actualArrivalAt = request.actualArrivalAt?.javaTrim()?.takeIf(String::isNotEmpty),
            arrivalDiffMinutes = request.arrivalDiffMinutes?.takeUnless { it == Int.MIN_VALUE },
            flightNo = request.flightNo?.javaTrim()?.takeIf(String::isNotEmpty),
            seatNo = request.seatNo?.javaTrim()?.takeIf(String::isNotEmpty),
        ))

    fun normalize(legs: List<FlightPlanLegDto>): List<FlightPlanLegDto> = legs.mapIndexed { index, leg ->
        leg.copy(legIndex = index + 1, focusIconCode = leg.focusIconCode.coerceIn(1, 20),
            scheduledDepartureLocal = leg.scheduledDepartureLocal?.takeIf(String::isNotEmpty),
            arrivalDiffMinutes = leg.arrivalDiffMinutes?.takeUnless { it == Int.MIN_VALUE },
            actualArrivalAt = leg.actualArrivalAt?.takeIf(String::isNotEmpty),
            expectedArrivalAt = leg.expectedArrivalAt?.takeIf(String::isNotEmpty))
    }

    private fun String.javaTrim(): String = trim { it <= '\u0020' }
}
