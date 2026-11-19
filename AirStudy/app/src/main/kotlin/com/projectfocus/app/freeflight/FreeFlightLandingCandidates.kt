package com.projectfocus.app.freeflight

import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.freeflight.FreeFlightPoint
import com.projectfocus.core.network.di.NetworkCore
import com.projectfocus.core.network.dto.AirportLocationRequest
import com.projectfocus.core.network.freeflight.HttpAirportLocationGateway
import com.projectfocus.core.network.freeflight.AirportLocationGateway
import com.projectfocus.service.focus.freeflight.FreeFlightLandingDistance
import com.projectfocus.service.focus.freeflight.FreeFlightLandingSearch
import javax.inject.Inject
import javax.inject.Singleton

data class FreeFlightLandingOption(val airport: PlannedAirport, val distanceKm: Double, val minutes: Int)

/** Actual API response order, raw range/HMS-distance filter and case-insensitive origin exclusion. */
class FreeFlightLandingCandidates(
    private val gateway: AirportLocationGateway,
    private val distance: FreeFlightLandingDistance,
) {
    suspend fun query(search: FreeFlightLandingSearch, departureCode: String): List<FreeFlightLandingOption> {
        val response = gateway.search(AirportLocationRequest(search.center.latitude, search.center.longitude, search.queryRadiusKm))
        val rawRadius = search.rangeMinutes / 60.0 * 800.0
        val options = ArrayList<FreeFlightLandingOption>()
        for (value in response) {
            if (value.code.equals(departureCode, ignoreCase = true)) continue
            val km = distance.kilometres(search.center, FreeFlightPoint(value.latitude, value.longitude))
            check(km.isFinite() && km >= 0.0) { "Actual distance is unavailable" }
            if (km > rawRadius) continue
            val airport = PlannedAirport(value.id, value.code, value.name, value.city, value.country, value.latitude, value.longitude)
            options += FreeFlightLandingOption(airport, km, minutes(km))
        }
        return options
    }

    companion object {
        // Bound to a4.t.G(distance,2) separately from the radius arithmetic.
        internal fun minutes(distanceKm: Double): Int = Math.round(distanceKm / 800.0 * 60.0).toInt()
    }
}

@Singleton
class FreeFlightLandingCandidatesFactory @Inject constructor(network: NetworkCore, private val distance: FreeFlightLandingDistance) {
    private val gateway = HttpAirportLocationGateway(network.baseClient, network.baseUrl)
    fun create(): FreeFlightLandingCandidates = FreeFlightLandingCandidates(gateway, distance)
}
