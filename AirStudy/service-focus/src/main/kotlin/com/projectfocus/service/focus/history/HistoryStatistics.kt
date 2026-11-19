package com.projectfocus.service.focus.history

import com.projectfocus.core.database.dao.CountryTripCount

data class HistoryDestination(val code: String, val name: String, val city: String, val country: String)

data class HistoryStatistics(
    val flights: Int,
    val distanceKm: Double,
    val focusMinutes: Int,
    val destinations: List<HistoryDestination>,
    val countries: List<CountryTripCount>,
) {
    companion object {
        fun calculate(records: List<RecordedFlight>, airportCounts: Map<String, Int>, airportCodes: Map<String, Set<String>>): HistoryStatistics {
            val completed = records.filter { it.completed }
            val distance = completed.sumOf { it.distanceKm }
            require(distance.isFinite() && distance >= 0.0)
            val minutes = completed.fold(0) { total, flight -> Math.addExact(total, flight.focusMinutes) }
            val visits = completed.mapNotNull { record ->
                when (record) {
                    is RecordedFlight.Countdown -> record.record.let {
                        destination(it.destinationAirportCode, it.destinationAirportName, it.destinationCity, it.destinationCountry)
                    }
                    is RecordedFlight.CountUp -> record.archive.landingAirport?.let {
                        destination(it.code, it.displayName, it.displayCity, it.country)
                    }
                }
            }
            val countries = completed.filter { knownCountry(it.destinationCountry) }.groupBy { it.destinationCountry!! }.map { (country, rows) ->
                val codes = visits.filter { it.country == country }.map { it.code }.toSet()
                val directory = airportCodes[country].orEmpty()
                val total = if (directory.containsAll(codes)) airportCounts[country] ?: 0 else 0
                CountryTripCount(country, rows.size, codes.size, total)
            }.sortedWith(compareByDescending<CountryTripCount> { it.tripCount }.thenBy { it.country })
            return HistoryStatistics(completed.size, distance, minutes, visits.distinctBy { it.code }, countries)
        }

        private fun destination(code: String, name: String, city: String, country: String): HistoryDestination? {
            if (code.isBlank() || code in setOf("UNKNOWN", "---", "—") || !knownCountry(country)) return null
            return HistoryDestination(code, name, city, country)
        }

        private fun knownCountry(country: String?) = !country.isNullOrBlank() && country !in setOf("UNKNOWN", "Unknown", "—")
    }
}
