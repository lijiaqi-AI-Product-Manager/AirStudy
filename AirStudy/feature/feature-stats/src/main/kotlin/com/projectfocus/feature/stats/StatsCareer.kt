package com.projectfocus.feature.stats

import com.projectfocus.service.focus.history.HistoryDestination
import com.projectfocus.service.focus.history.RecordedFlight
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class StatsCareerCity(val name: String, val country: String, val arrivals: Int)
data class StatsCareerCountry(val country: String, val visitedAirports: Int, val totalAirports: Int)

data class StatsCareer(
    val distanceKm: Double,
    val completedFlights: Int,
    val onTimeRate: Double?,
    val cityCount: Int,
    val cities: List<StatsCareerCity>,
    val countries: List<StatsCareerCountry>,
    val destinations: List<HistoryDestination>,
) {
    companion object {
        fun calculate(records: List<RecordedFlight>, airportCounts: Map<String, Int>, airportCodes: Map<String, Set<String>>): StatsCareer {
            return calculateFacts(records.map(StatsFlightFact::recorded),airportCounts,airportCodes)
        }
        internal fun calculateFacts(records: List<StatsFlightFact>, airportCounts: Map<String, Int>, airportCodes: Map<String, Set<String>>): StatsCareer {
            val completed = records.filter { it.completed == true }
            val distance = completed.mapNotNull { it.distanceKm }.sum()
            require(distance.isFinite() && distance >= 0.0)
            fun known(value: String?) = !value.isNullOrBlank() && value !in setOf("UNKNOWN", "Unknown", "---", "—")
            val destinations = completed.mapNotNull { flight ->
                flight.destination?.let { place ->
                    if (!known(place.code) || !known(place.country)) null
                    else {
                        val code=requireNotNull(place.code)
                        HistoryDestination(code,place.name ?: code,place.city.orEmpty(),requireNotNull(place.country))
                    }
                }
            }.distinctBy { it.code }
            val cityFlights = completed.mapNotNull { flight ->
                val city = flight.destination?.city?.trim().orEmpty()
                city.takeIf(::known)
                    ?.let { flight.destination?.country.orEmpty() to it }
            }.groupingBy { it }.eachCount()
            val cities = cityFlights.map { (place, count) -> StatsCareerCity(place.second, place.first, count) }
                .sortedWith(compareByDescending<StatsCareerCity> { it.arrivals }.thenBy { it.name }.thenBy { it.country })
            val countries = completed.filter { known(it.destination?.country) }.groupBy { requireNotNull(it.destination?.country) }.map { (country, _) ->
                val codes = destinations.filter { it.country == country }.map { it.code }.toSet()
                val total = if (airportCodes[country].orEmpty().containsAll(codes)) airportCounts[country] ?: 0 else 0
                StatsCareerCountry(country, codes.size, total)
            }.sortedWith(compareByDescending<StatsCareerCountry> { it.visitedAirports }.thenBy { it.country })
            return StatsCareer(distance, completed.size, null, cities.size, cities, countries, destinations)
        }
    }
}

data class StatsFlightStreak(val currentDays: Int, val bestDays: Int) {
    companion object {
        fun calculate(records: List<RecordedFlight>, range: StatsDateRange, nowWallMs: Long, zone: ZoneId): StatsFlightStreak {
            return calculateFacts(records.map(StatsFlightFact::recorded),range,nowWallMs,zone)
        }
        internal fun calculateFacts(records: List<StatsFlightFact>, range: StatsDateRange, nowWallMs: Long, zone: ZoneId): StatsFlightStreak {
            val today = Instant.ofEpochMilli(nowWallMs).atZone(zone).toLocalDate()
            val anchor = minOf(today, range.lastDate)
            val days = records.asSequence().filter { it.completed == true && it.inRange(range) }
                .map { Instant.ofEpochMilli(requireNotNull(it.recordedAtWallMs)).atZone(zone).toLocalDate() }
                .filter { it <= anchor }.toSortedSet()
            var previous: LocalDate? = null
            var run = 0
            var best = 0
            for (day in days) {
                run = if (previous?.plusDays(1) == day) run + 1 else 1
                best = maxOf(best, run)
                previous = day
            }
            val current = if (previous == anchor || previous == anchor.minusDays(1)) run else 0
            return StatsFlightStreak(current, best)
        }
    }
}
