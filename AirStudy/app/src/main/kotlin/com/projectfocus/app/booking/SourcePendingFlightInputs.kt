package com.projectfocus.app.booking

import com.projectfocus.core.network.dto.FlightPlanLegSnapshot


internal data class SourcePendingFlightInputs(
    val departureNumberInput: String,
    val arrivalNumberInput: String,
    val departureDisplay: String,
    val arrivalDisplay: String,
) {
    fun flightNumber(): String = SourceFlightNumber.generate(
        departureNumberInput, arrivalNumberInput, upMode = false,
    )

    companion object {
        fun from(snapshot: FlightPlanLegSnapshot): SourcePendingFlightInputs? {
            val departure = snapshot.departureAirport ?: return null
            val arrival = snapshot.arrivalAirport ?: return null
            val from = sourceText(departure.city, sourceText(departure.code, "DEP"))
            val to = sourceText(arrival.city, sourceText(arrival.code, "ARR"))
            return SourcePendingFlightInputs(from, to,
                sourceText(departure.name, from), sourceText(arrival.name, to))
        }

        private fun sourceText(value: String?, fallback: String): String =
            value?.trim { it <= '\u0020' }?.takeIf { it.isNotEmpty() } ?: fallback
    }
}
