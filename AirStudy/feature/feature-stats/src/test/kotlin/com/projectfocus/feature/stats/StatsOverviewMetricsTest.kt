package com.projectfocus.feature.stats

import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.freeflight.*
import com.projectfocus.service.focus.history.RecordedFlight
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class StatsOverviewMetricsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.parse("2026-11-03")
    private val wall = date.atStartOfDay(zone).toInstant().toEpochMilli()
    private val owner = "statistics-overview-owner"
    private fun legacy(id: String, at: Long, minutes: Int = 5, status: String = "COMPLETED") = RecordedFlight.Countdown(FlightHistoryEntity(
        id,null,"SOLO",null,"PEK","Beijing Airport","北京","CN",40.0,116.0,
        "TSN","Tianjin Airport","天津","CN",39.0,117.0,at,at+300_000,
        25,minutes,99.25,status,null,"01A",false,false,false,null,false,at,at,ownerScope=owner))
    private fun countUp(id: String, at: Long): RecordedFlight.CountUp {
        val engine = FreeFlightEngine()
        val clock = FreeFlightClockReading(0,0,at,"statistics-overview-clock")
        val start = engine.start(FreeFlightStartRequest(id,owner,
            PlannedAirport(1,"PEK","Beijing Airport","北京","CN",40.0,116.0),90.0,"01A",FreeFlightScene(1,"学习","study")),clock)
        val arrival = clock.copy(elapsedRealtimeMs=90_001,uptimeMillis=90_001,wallMs=at+90_001)
        val selection = engine.prepareLanding(start,arrival)
        val chosen = engine.selectLanding(selection,PlannedAirport(2,"TSN","Tianjin Airport","天津","CN",39.0,117.0),selection.landingSelectionGeneration)
        return RecordedFlight.CountUp(engine.completeLandingWithoutMap(engine.beginLandingWithoutMap(chosen,chosen.landingSelectionGeneration,arrival),arrival))
    }

    @Test fun `shared identifiers retain both modes and precise count up duration`() {
        val result = StatsOverviewMetrics.calculate(listOf(legacy("same-id",wall),countUp("same-id",wall)),StatsDateSelection(date).range(zone),zone)
        assertEquals(2,result.completedFlights)
        assertEquals(5.0+90_001/60_000.0,result.totalFocusMinutes,1e-10)
        assertEquals(5.0,result.longestFocusMinutes,0.0)
        assertEquals(1,result.activeDays)
        assertEquals(1,result.bestStreakDays)
    }

    @Test fun `rounding happens after summing precise durations`() {
        val result = StatsOverviewMetrics.calculate(listOf(countUp("one",wall),countUp("two",wall)),StatsDateSelection(date).range(zone),zone)
        assertEquals(3L,Math.round(result.totalFocusMinutes))
        assertEquals(90_001/60_000.0,result.longestFocusMinutes,1e-10)
    }

    @Test fun `calendar range boundaries and incomplete flights do not inflate the summary`() {
        val range = StatsDateSelection(date).custom(date,date).range(zone)
        val result = StatsOverviewMetrics.calculate(listOf(legacy("first",range.fromWallMs,3),legacy("last",range.untilWallMs-1,8),
            legacy("before",range.fromWallMs-1),legacy("after",range.untilWallMs),
            legacy("cancelled",wall,status="CANCELLED"),legacy("ongoing",wall,status="ONGOING")),range,zone)
        assertEquals(2,result.completedFlights)
        assertEquals(11.0,result.totalFocusMinutes,0.0)
        assertEquals(8.0,result.longestFocusMinutes,0.0)
        assertEquals(1,result.activeDays)
    }

    @Test fun `duplicate dates and missing days determine the longest consecutive run`() {
        val first = date.withDayOfMonth(1)
        val records = listOf(0L,0L,1L,3L,4L,5L,7L).mapIndexed { index, offset ->
            legacy("flight-$index",first.plusDays(offset).atStartOfDay(zone).toInstant().toEpochMilli())
        }
        val result = StatsOverviewMetrics.calculate(records,StatsDateSelection(date,StatsPeriod.MONTH).range(zone),zone)
        assertEquals(7,result.completedFlights)
        assertEquals(6,result.activeDays)
        assertEquals(3,result.bestStreakDays)
    }

    @Test fun `repeated daylight saving hours belong to one local date`() {
        val newYork = ZoneId.of("America/New_York")
        val selected = LocalDate.parse("2026-11-01")
        val result = StatsOverviewMetrics.calculate(listOf(legacy("first-hour",Instant.parse("2026-11-01T05:30:00Z").toEpochMilli()),
            legacy("second-hour",Instant.parse("2026-11-01T06:30:00Z").toEpochMilli())),StatsDateSelection(selected).custom(selected,selected).range(newYork),newYork)
        assertEquals(2,result.completedFlights)
        assertEquals(1,result.activeDays)
        assertEquals(1,result.bestStreakDays)
        assertEquals(StatsOverviewMetrics(),StatsOverviewMetrics.calculate(emptyList(),StatsDateSelection(date).range(zone),zone))
    }
}
