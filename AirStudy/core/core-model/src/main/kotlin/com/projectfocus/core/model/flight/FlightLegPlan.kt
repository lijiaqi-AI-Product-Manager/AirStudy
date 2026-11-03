package com.projectfocus.core.model.flight

import com.projectfocus.core.common.flight.FlightMath
import kotlinx.serialization.Serializable

/** 用户确认前保存的机场快照；记录与HUD不再重新查机场或代入默认机场。 */
@Serializable
data class PlannedAirport(
    val id: Long,
    val code: String,
    val name: String,
    val city: String,
    val country: String,
    val latitude: Double,
    val longitude: Double,
    val displayName: String = name,
    val displayCity: String = city,
) {
    init {
        require(code.isNotBlank()) { "Airport code is missing" }
        require(latitude.isFinite() && latitude in -90.0..90.0) { "Invalid airport latitude" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "Invalid airport longitude" }
    }
}

/** 一条已确认航段。距离保留原包Float精度，分钟独立按原包四舍五入。 */
@Serializable
data class FlightLegPlan(
    val departure: PlannedAirport,
    val destination: PlannedAirport,
    val distanceKm: Double,
    val durationMinutes: Int,
    val seat: String? = null,
    val categoryId: Long? = null,
    val scene: PlannedScene? = null,
    val plannedDeparture: PlannedDeparture? = null,
) {
    init {
        require(categoryId == null || categoryId >= 0L) { "Invalid focus category" }
        require(departure.id != destination.id && departure.code != destination.code) {
            "Departure and destination must differ"
        }
        require(distanceKm.isFinite() && distanceKm > 0.0) { "Invalid route distance" }
        require(durationMinutes > 0) { "Route is too short for a flight" }
        val measured = FlightMath.airportDistanceKm(
            departure.latitude, departure.longitude, destination.latitude, destination.longitude,
        ).toDouble()
        require(distanceKm == measured) { "Route distance differs from the confirmed airports" }
        require(durationMinutes == FlightMath.minutesForDistanceKm(distanceKm)) {
            "Flight duration differs from the route distance"
        }
    }

    companion object {
        fun between(departure: PlannedAirport, destination: PlannedAirport): FlightLegPlan {
            val km = FlightMath.airportDistanceKm(
                departure.latitude, departure.longitude, destination.latitude, destination.longitude,
            ).toDouble()
            return FlightLegPlan(departure, destination, km, FlightMath.minutesForDistanceKm(km))
        }
    }
}
