package com.projectfocus.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject


object FlightPlanActions {
    const val GET = "service_user_flight_plan_get_v2"
    const val UPSERT = "service_user_flight_plan_upsert_v2"
    const val HISTORY = "service_user_flight_plan_history_get"
    const val CANCEL_BATCH_DELETE = "service_user_flight_plan_cancel_batch_delete_v2"
    const val LEG_STATUS_UPDATE = "service_user_flight_plan_leg_status_update_v2"
}

/** Source airport JSON uses coordinate strings and two locale objects. */
@Serializable
data class FlightPlanAirportDto(
    val id: Int,
    val code: String,
    val name: String,
    val city: String,
    val latitude: String,
    val longitude: String,
    val region: String,
    @SerialName("name_i18n") val nameI18n: JsonObject? = null,
    @SerialName("city_i18n") val cityI18n: JsonObject? = null,
    @SerialName("country_region_id") val countryRegionId: Int,
)

/** Incoming values are deliberately not validated as a newly created route. */
@Serializable
data class FlightPlanLegSnapshot(
    val legIndex: Int,
    val departureAirport: FlightPlanAirportDto?,
    val arrivalAirport: FlightPlanAirportDto?,
    val scheduledDepartureAt: String,
    val legStatus: String,
    val distanceKm: Double,
    val flightNo: String,
    val seatNo: String,
    val focusIconCode: Int,
    val focusSceneText: String,
    val actualArrivalAt: String = "",
    val expectedArrivalAt: String = "",
    val scheduledArrivalAt: String = "",
    val arrivalDiffMinutes: Int = Int.MIN_VALUE,
    val archived: Boolean = false,
    val readOnly: Boolean = false,
)

@Serializable
data class DailyFlightPlanSnapshot(
    val localPlanDate: String,
    val timezone: String,
    val legs: List<FlightPlanLegSnapshot>,
    val planDate: String = localPlanDate,
    val deletedCount: Int = -1,
)

/** Full-list upsert rebuilds indices and retains cancelled legs and terminal metadata. */
@Serializable
data class FlightPlanLegDto(
    @SerialName("leg_index") val legIndex: Int,
    @SerialName("departure_airport") val departureAirport: FlightPlanAirportDto,
    @SerialName("arrival_airport") val arrivalAirport: FlightPlanAirportDto,
    @SerialName("scheduled_departure_at") val scheduledDepartureAt: String,
    @SerialName("leg_status") val legStatus: String,
    @SerialName("distance_km") val distanceKm: Double,
    @SerialName("flight_no") val flightNo: String,
    @SerialName("seat_no") val seatNo: String,
    @SerialName("focus_icon_code") val focusIconCode: Int,
    @SerialName("focus_scene_text") val focusSceneText: String,
    @SerialName("scheduled_departure_local") val scheduledDepartureLocal: String? = null,
    @SerialName("arrival_diff_minutes") val arrivalDiffMinutes: Int? = null,
    @SerialName("actual_arrival_at") val actualArrivalAt: String? = null,
    @SerialName("expected_arrival_at") val expectedArrivalAt: String? = null,
)

/** Flat message, not ApiEnvelope or a nested data request. */
@Serializable
data class GetFlightPlanRequest(
    @SerialName("plan_date") val planDate: String,
    @SerialName("local_plan_date") val localPlanDate: String,
    val timezone: String,
    @SerialName("timezone_offset_minutes") val timezoneOffsetMinutes: Int,
    @SerialName("request_id") val requestId: String,
    val action: String = FlightPlanActions.GET,
)

@Serializable
data class UpsertFlightPlanRequest(
    @SerialName("plan_date") val planDate: String,
    @SerialName("local_plan_date") val localPlanDate: String,
    val timezone: String,
    @SerialName("timezone_offset_minutes") val timezoneOffsetMinutes: Int,
    @SerialName("request_id") val requestId: String,
    val legs: List<FlightPlanLegDto>,
    val action: String = FlightPlanActions.UPSERT,
)

/** Source history uses ISO local dates, including the selected month's range. */
@Serializable
data class FlightPlanHistoryRequest(
    val startDate: String,
    val endDate: String,
    val timezone: String,
    @SerialName("request_id") val requestId: String,
    val limit: Int = 200,
    val action: String = FlightPlanActions.HISTORY,
)

/** Source x4.d case1 sends date scope, never a client-selected list of leg IDs. */
@Serializable
data class CancelBatchDeleteFlightPlanRequest(
    @SerialName("plan_date") val planDate: String,
    @SerialName("local_plan_date") val localPlanDate: String,
    val timezone: String,
    @SerialName("timezone_offset_minutes") val timezoneOffsetMinutes: Int,
    @SerialName("local_today") val localToday: String,
    @SerialName("local_tomorrow") val localTomorrow: String,
    @SerialName("request_id") val requestId: String,
    @SerialName("user_scope_id") val userScopeId: String? = null,
    val action: String = FlightPlanActions.CANCEL_BATCH_DELETE,
)

/**
 * Observed c4.h.s flat status frame. Active cancel targets waiting_takeoff; it
 * does not delete a row. Arrival fields are optional even for completed.
 * This DTO adds no session, plan_date, airport, focus or ownership field.
 */
@Serializable
data class UpdateFlightPlanLegStatusRequest(
    @SerialName("local_plan_date") val localPlanDate: String,
    val timezone: String,
    @SerialName("timezone_offset_minutes") val timezoneOffsetMinutes: Int,
    @SerialName("leg_index") val legIndex: Int,
    @SerialName("target_status") val targetStatus: String,
    @SerialName("request_id") val requestId: String,
    @SerialName("actual_arrival_at") val actualArrivalAt: String? = null,
    @SerialName("arrival_diff_minutes") val arrivalDiffMinutes: Int? = null,
    @SerialName("flight_no") val flightNo: String? = null,
    @SerialName("seat_no") val seatNo: String? = null,
    val action: String = FlightPlanActions.LEG_STATUS_UPDATE,
)
