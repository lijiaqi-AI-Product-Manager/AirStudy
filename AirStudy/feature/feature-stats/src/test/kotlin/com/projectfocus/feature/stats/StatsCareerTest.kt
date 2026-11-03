package com.projectfocus.feature.stats

import com.projectfocus.service.focus.history.RecordedFlight
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class StatsCareerTest {
    private val zone = ZoneId.of("America/New_York")
    private val today = LocalDate.parse("2026-11-03")
    private fun at(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()
    private val now get() = at(today) + 12 * 3_600_000
    private fun flight(id: String, date: LocalDate = today, city: String = "天津", country: String = "CN", code: String = "TSN", status: String = "COMPLETED") =
        HeatmapTestRecords.countdown(id, at(date), status = status).let { it.copy(record = it.record.copy(destinationCity = city, destinationCountry = country, destinationAirportCode = code)) }
    private fun career(vararg records: RecordedFlight) = StatsCareer.calculate(records.toList(), mapOf("CN" to 3), mapOf("CN" to setOf("PEK", "TSN", "SHA")))

    @Test fun `both modes and old dates contribute while abandoned and running flights do not`() {
        val up = HeatmapTestRecords.countUp("shared", at(today))
        val result = career(flight("shared"), up, flight("old", today.minusYears(3)), flight("cancelled", status = "CANCELLED"), flight("running", status = "ONGOING"))
        assertEquals(3, result.completedFlights)
        assertEquals(198.5 + up.distanceKm, result.distanceKm, 1e-8)
        assertEquals(3, result.cities.single().arrivals)
        assertEquals(1, result.cityCount)
        assertEquals(1, result.countries.single().visitedAirports)
        assertEquals(3, result.countries.single().totalAirports)
    }
    @Test fun `repeated cities and multiple airports are counted separately from unique visited places`() {
        val result = career(flight("a"), flight("b"), flight("c", code = "OTHER"), flight("d", city = "上海", code = "SHA"))
        assertEquals(2, result.cityCount)
        assertEquals(3, result.cities.first().arrivals)
        assertEquals(3, result.destinations.size)
        assertEquals(3, result.countries.single().visitedAirports)
        assertEquals(0, result.countries.single().totalAirports)
    }
    @Test fun `cities sharing a name across countries remain different places with stable ordering`() {
        val result = career(flight("ca", city = "London", country = "CA", code = "YXU"), flight("gb", city = "London", country = "GB", code = "LHR"))
        assertEquals(2, result.cityCount)
        assertEquals(listOf("CA", "GB"), result.cities.map { it.country })
        assertEquals(listOf("CA", "GB"), result.countries.map { it.country })
    }
    @Test fun `country order depends on unique airports and repeated arrivals cannot inflate coverage`() {
        val result=career(flight("cn1"),flight("cn2"),flight("cn3"),
            flight("us1",country="US",code="JFK"),flight("us2",country="US",code="LAX"))
        assertEquals(listOf("US","CN"),result.countries.map { it.country })
        assertEquals(listOf(2,1),result.countries.map { it.visitedAirports })
    }
    @Test fun `unknown geography leaves successful mileage and completed count intact`() {
        val result = career(flight("missing", city = "—", country = "UNKNOWN", code = "---"))
        assertEquals(1, result.completedFlights)
        assertEquals(99.25, result.distanceKm, 0.0)
        assertEquals(0, result.cityCount)
        assertTrue(result.cities.isEmpty()); assertTrue(result.countries.isEmpty()); assertTrue(result.destinations.isEmpty())
        assertNull(result.onTimeRate)
    }
    @Test fun `legacy completion cannot manufacture an on time classification`() {
        val valid = flight("valid")
        val noEnd = valid.copy(record = valid.record.copy(id = "no-end", endTimeMillis = null))
        val noPlan = valid.copy(record = valid.record.copy(id = "no-plan", plannedDepartureJson = null))
        assertNull(career(valid, noEnd, noPlan, HeatmapTestRecords.countUp("up", at(today))).onTimeRate)
        assertNull(career().onTimeRate)
    }
    @Test fun `lifetime mileage is independent from a focus minute integer total`() {
        val valid = flight("first")
        val first = valid.copy(record = valid.record.copy(actualFocusMinutes = Int.MAX_VALUE))
        val second = valid.copy(record = valid.record.copy(id = "second", actualFocusMinutes = Int.MAX_VALUE))
        val result = career(first, second)
        assertEquals(2, result.completedFlights)
        assertEquals(198.5, result.distanceKm, 0.0)
    }
    @Test fun `current streak accepts yesterday and deduplicates multiple daily flights across daylight saving`() {
        val range = StatsDateSelection(today, StatsPeriod.MONTH).range(zone)
        val records = listOf(flight("nov1", today.minusDays(2)), flight("nov2a", today.minusDays(1)), flight("nov2b", today.minusDays(1)))
        assertEquals(StatsFlightStreak(2, 2), StatsFlightStreak.calculate(records, range, now, zone))
        assertEquals(StatsFlightStreak(3, 3), StatsFlightStreak.calculate(records + flight("nov3"), range, now, zone))
    }
    @Test fun `a gap breaks the current run and future cancelled or out of range records do not extend it`() {
        val range = StatsDateSelection(today, StatsPeriod.YEAR).range(zone)
        val records = listOf(flight("old1", today.minusDays(6)), flight("old2", today.minusDays(5)), flight("old3", today.minusDays(4)),
            flight("cancelled", today.minusDays(1), status = "CANCELLED"), flight("future", today.plusDays(1)), flight("last-year", today.minusYears(1)))
        assertEquals(StatsFlightStreak(0, 3), StatsFlightStreak.calculate(records, range, now, zone))
    }
    @Test fun `an earlier custom period anchors its run to its final date and uses half open bounds`() {
        val last = today.minusMonths(1)
        val range = StatsDateSelection(last).custom(last.minusDays(2), last).range(zone)
        val records = listOf(flight("a", last.minusDays(2)), flight("b", last.minusDays(1)), flight("c", last), flight("after", last.plusDays(1)))
        assertEquals(StatsFlightStreak(3, 3), StatsFlightStreak.calculate(records, range, now, zone))
        assertEquals(StatsFlightStreak(0, 0), StatsFlightStreak.calculate(emptyList(), range, now, zone))
    }
}
