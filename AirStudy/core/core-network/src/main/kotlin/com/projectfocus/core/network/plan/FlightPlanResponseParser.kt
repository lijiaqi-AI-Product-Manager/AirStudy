package com.projectfocus.core.network.plan

import com.projectfocus.core.network.dto.DailyFlightPlanSnapshot
import com.projectfocus.core.network.dto.FlightPlanAirportDto
import com.projectfocus.core.network.dto.FlightPlanLegSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.json.JSONArray
import org.json.JSONObject


object FlightPlanResponseParser {
    data class Envelope(
        val requestId: String,
        val action: String,
        val success: Boolean,
        val message: String,
        val data: JSONObject?,
        val code: String = "",
    )

    fun envelope(text: String): Envelope {
        val root = JSONObject(text)
        val data = root.optJSONObject("data")
        val action = data?.optString("action", "").orEmpty().ifEmpty { root.optString("action", "") }
        // The source defaults missing success to true; do not silently replace its contract.
        return Envelope(root.optString("request_id", "").javaTrim(), action,
            root.optBoolean("success", true), root.optString("message", ""), data,
            data?.optString("code", "").orEmpty())
    }

    /** Success with a missing plan is a valid null result in the source. */
    fun daily(data: JSONObject?): DailyFlightPlanSnapshot? {
        data ?: return null
        val deleted = data.optInt("deletedCount", data.optInt("deleted_count", -1))
        val plan = data.optJSONObject("legs")
        if (plan != null) {
            val date = plan.optString("local_plan_date", plan.optString("plan_date", ""))
            return DailyFlightPlanSnapshot(date, plan.optString("timezone", ""),
                legs(plan.optJSONArray("legs")), plan.optString("plan_date", date), deleted)
        }
        val remaining = data.optJSONArray("remaining_legs") ?: return null
        val date = data.optString("targetLocalPlanDate",
            data.optString("local_plan_date", data.optString("plan_date", "")))
        return DailyFlightPlanSnapshot(date, data.optString("timezone", ""), legs(remaining),
            data.optString("plan_date", date), deleted)
    }

    /** Status reply has only data.legs as a plan object; no remaining_legs fallback. */
    fun status(data: JSONObject?): DailyFlightPlanSnapshot? {
        val plan = data?.optJSONObject("legs") ?: return null
        val date = plan.optString("local_plan_date", plan.optString("plan_date", ""))
        return DailyFlightPlanSnapshot(date, plan.optString("timezone", ""),
            legs(plan.optJSONArray("legs")), plan.optString("plan_date", date))
    }

    fun history(data: JSONObject?): List<DailyFlightPlanSnapshot> {
        val plans = data?.optJSONArray("plans") ?: return emptyList()
        val timezone = data.optString("timezone", "")
        return buildList {
            for (index in 0 until plans.length()) {
                val plan = plans.optJSONObject(index) ?: continue
                val date = text(plan, "local_plan_date", "localPlanDate", "plan_date", "planDate")
                val zone = text(plan, "timezone").ifEmpty { timezone }
                add(DailyFlightPlanSnapshot(date, zone, legs(plan.optJSONArray("legs"),
                    flag(plan, true, "archived"), flag(plan, true, "readonly", "readOnly")),
                    text(plan, "plan_date", "planDate").ifEmpty { date }))
            }
        }
    }

    fun legs(array: JSONArray?, archived: Boolean = false, readOnly: Boolean = false): List<FlightPlanLegSnapshot> {
        array ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val leg = array.optJSONObject(index) ?: continue
                add(FlightPlanLegSnapshot(
                    legIndex = if (leg.has("leg_index")) leg.optInt("leg_index", index + 1) else leg.optInt("legIndex", index + 1),
                    departureAirport = airport(leg.optJSONObject("departure_airport") ?: leg.optJSONObject("departureAirport")),
                    arrivalAirport = airport(leg.optJSONObject("arrival_airport") ?: leg.optJSONObject("arrivalAirport")),
                    scheduledDepartureAt = text(leg, "scheduled_departure_at", "scheduledDepartureAt"),
                    legStatus = text(leg, "leg_status", "legStatus"),
                    distanceKm = if (leg.has("distance_km")) leg.optDouble("distance_km", 0.0) else leg.optDouble("distanceKm", 0.0),
                    flightNo = text(leg, "flight_no", "flightNo"),
                    seatNo = text(leg, "seat_no", "seatNo"),
                    focusIconCode = if (leg.has("focus_icon_code")) leg.optInt("focus_icon_code", 0) else leg.optInt("focusIconCode", 0),
                    focusSceneText = text(leg, "focus_scene_text", "focusSceneText"),
                    actualArrivalAt = text(leg, "actual_arrival_at", "actualArrivalAt", "completed_at", "completedAt",
                        "finished_at", "finishedAt", "landed_at", "landedAt"),
                    expectedArrivalAt = text(leg, "expected_arrival_at", "expectedArrivalAt", "scheduled_arrival_at", "scheduledArrivalAt"),
                    scheduledArrivalAt = text(leg, "scheduled_arrival_at", "scheduledArrivalAt"),
                    arrivalDiffMinutes = leg.optInt("arrival_diff_minutes", Int.MIN_VALUE),
                    archived = flag(leg, archived || text(leg, "archived_at", "archivedAt").isNotEmpty(), "archived"),
                    readOnly = flag(leg, readOnly, "readonly", "readOnly"),
                ))
            }
        }
    }

    private fun airport(value: JSONObject?): FlightPlanAirportDto? {
        value ?: return null
        return FlightPlanAirportDto(value.optInt("id", 0), value.optString("code", ""),
            value.optString("name", ""), value.optString("city", ""),
            value.opt("latitude")?.toString().orEmpty(), value.opt("longitude")?.toString().orEmpty(),
            value.optString("region", ""), locale(value, "name_i18n", "nameI18n"),
            locale(value, "city_i18n", "cityI18n"), value.optInt("country_region_id", 0))
    }

    private fun locale(value: JSONObject, vararg keys: String): JsonObject? {
        for (key in keys) {
            value.optJSONObject(key)?.let { return Json.parseToJsonElement(it.toString()) as JsonObject }
            val raw = value.opt(key) as? String ?: continue
            val candidate = raw.javaTrim()
            if (!candidate.startsWith('{') || !candidate.endsWith('}')) continue
            try { return Json.parseToJsonElement(JSONObject(candidate).toString()) as JsonObject }
            catch (_: org.json.JSONException) { /* Source tries the next alias. */ }
        }
        return null
    }

    private fun text(value: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val text = value.optString(key, "").javaTrim()
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    private fun flag(value: JSONObject, default: Boolean, vararg keys: String): Boolean {
        for (key in keys) {
            if (!value.has(key)) continue
            when (val raw = value.opt(key)) {
                is Boolean -> return raw
                is Number -> return raw.toInt() != 0
                is String -> when (val text = raw.javaTrim()) {
                    "1" -> return true
                    "0" -> return false
                    else -> {
                        if (text.equals("true", ignoreCase = true)) return true
                        if (text.equals("false", ignoreCase = true)) return false
                    }
                }
            }
        }
        return default
    }

    internal fun String.javaTrim(): String = trim { it <= '\u0020' }
}
