package com.projectfocus.feature.stats

import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.freeflight.*
import com.projectfocus.service.focus.history.RecordedFlight
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.junit.Assert.*
import org.junit.Test

internal object HeatmapTestRecords {
    fun countdown(id: String, at: Long, minutes: Int = 5, status: String = "COMPLETED") = RecordedFlight.Countdown(FlightHistoryEntity(
        id,null,"SOLO",null,"PEK","Beijing Airport","北京","CN",40.0,116.0,
        "TSN","Tianjin Airport","天津","CN",39.0,117.0,at,at+300_000,25,minutes,99.25,status,
        null,"01A",false,false,false,null,false,at,at,ownerScope="statistics-annual-owner"))
    fun countUp(id: String, at: Long): RecordedFlight.CountUp {
        val engine = FreeFlightEngine()
        val clock = FreeFlightClockReading(0,0,at,"statistics-annual-clock")
        val start = engine.start(FreeFlightStartRequest(id,"statistics-annual-owner",
            PlannedAirport(1,"PEK","Beijing Airport","北京","CN",40.0,116.0),90.0,"01A",FreeFlightScene(3,"Reading","reading")),clock)
        val now = clock.copy(elapsedRealtimeMs=90_001,uptimeMillis=90_001,wallMs=at+90_001)
        val selection = engine.prepareLanding(start,now)
        val picked = engine.selectLanding(selection,PlannedAirport(2,"TSN","Tianjin Airport","天津","CN",39.0,117.0),selection.landingSelectionGeneration)
        return RecordedFlight.CountUp(engine.completeLandingWithoutMap(engine.beginLandingWithoutMap(picked,picked.landingSelectionGeneration,now),now))
    }
}

class StatsAnnualHeatmapTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.parse("2026-11-03")
    private val wall = day.atStartOfDay(zone).toInstant().toEpochMilli()
    @Test fun `both modes retain exact elapsed minutes even with the same record ID`() {
        val result = StatsAnnualHeatmap.calculate(listOf(HeatmapTestRecords.countdown("same",wall),
            HeatmapTestRecords.countUp("same",wall),HeatmapTestRecords.countUp("second",wall)),2026,zone)
        assertEquals(8.0+2.0/60_000,result.minutes(day),1e-10)
        assertEquals(1,result.days.size)
        assertEquals(result.minutes(day),result.maximumMinutes,0.0)
    }
    @Test fun `the selected year is half open and incomplete records do not contribute`() {
        val from=LocalDate.of(2026,1,1).atStartOfDay(zone).toInstant().toEpochMilli()
        val until=LocalDate.of(2027,1,1).atStartOfDay(zone).toInstant().toEpochMilli()
        val records=listOf(HeatmapTestRecords.countdown("first",from,2),HeatmapTestRecords.countdown("last",until-1,3),
            HeatmapTestRecords.countdown("before",from-1,90),HeatmapTestRecords.countdown("after",until,90),
            HeatmapTestRecords.countdown("cancelled",wall,90,"CANCELLED"),HeatmapTestRecords.countdown("running",wall,90,"ONGOING"))
        val result=StatsAnnualHeatmap.calculate(records,2026,zone)
        assertEquals(mapOf(LocalDate.of(2026,1,1) to 2.0,LocalDate.of(2026,12,31) to 3.0),result.days)
    }
    @Test fun `a leap year contains every date once in a Monday based grid`() {
        val result=StatsAnnualHeatmap.calculate(emptyList(),2024,zone)
        assertEquals(378,result.grid.size)
        assertEquals(DayOfWeek.MONDAY,result.grid.first().dayOfWeek)
        assertEquals(LocalDate.parse("2024-01-01"),result.grid.first())
        assertEquals(366,result.grid.count(result::contains))
        assertEquals(1,result.grid.count { it==LocalDate.parse("2024-02-29") })
        assertTrue(result.grid.zipWithNext().all { (a,b)->ChronoUnit.DAYS.between(a,b)==1L })
    }
    @Test fun `a year starting on Sunday still includes its first and last days`() {
        val result=StatsAnnualHeatmap.calculate(emptyList(),2023,zone)
        assertEquals(LocalDate.parse("2022-12-26"),result.gridStart)
        assertEquals(LocalDate.parse("2023-01-01"),result.grid[6])
        assertEquals(365,result.grid.count(result::contains))
        assertEquals(1,result.grid.count { it==LocalDate.parse("2023-12-31") })
    }
    @Test fun `daily groups follow daylight saving dates instead of fixed length days`() {
        val local=ZoneId.of("America/New_York")
        val spring=LocalDate.parse("2026-03-08")
        val fall=LocalDate.parse("2026-11-01")
        val records=listOf(spring,fall).flatMap { date ->
            listOf(HeatmapTestRecords.countdown("$date-start",date.atStartOfDay(local).toInstant().toEpochMilli(),2),
                HeatmapTestRecords.countdown("$date-end",date.plusDays(1).atStartOfDay(local).toInstant().toEpochMilli()-1,3),
                HeatmapTestRecords.countdown("$date-next",date.plusDays(1).atStartOfDay(local).toInstant().toEpochMilli(),7))
        }
        val result=StatsAnnualHeatmap.calculate(records,2026,local)
        assertEquals(5.0,result.minutes(spring),0.0)
        assertEquals(7.0,result.minutes(spring.plusDays(1)),0.0)
        assertEquals(5.0,result.minutes(fall),0.0)
        assertEquals(7.0,result.minutes(fall.plusDays(1)),0.0)
    }
    @Test fun `zero minute completed records remain distinct from an empty year`() {
        val empty=StatsAnnualHeatmap.calculate(emptyList(),2026,zone)
        val zero=StatsAnnualHeatmap.calculate(listOf(HeatmapTestRecords.countdown("zero",wall,0)),2026,zone)
        assertTrue(empty.days.isEmpty())
        assertEquals(mapOf(day to 0.0),zero.days)
        assertEquals(1.0,empty.maximumMinutes,0.0)
        assertEquals(1.0,zero.maximumMinutes,0.0)
    }
    @Test fun `year data is independent from the visible query period`() {
        val january=LocalDate.of(2026,1,3)
        val result=StatsAnnualHeatmap.calculate(listOf(HeatmapTestRecords.countdown("jan",january.atStartOfDay(zone).toInstant().toEpochMilli(),40),
            HeatmapTestRecords.countdown("nov",wall,10),HeatmapTestRecords.countdown("future",day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),60)),2026,zone)
        assertEquals(3,result.days.size)
        assertEquals(60.0,result.maximumMinutes,0.0)
        assertEquals(40.0,result.minutes(january),0.0)
    }
    @Test fun `scroll date is clamped to dates inside the selected year`() {
        val result=StatsAnnualHeatmap.calculate(emptyList(),2026,zone)
        assertEquals(result.grid.indexOf(result.firstDate),result.scrollIndex(LocalDate.of(2025,11,3)))
        assertEquals(result.grid.indexOf(result.untilDate.minusDays(1)),result.scrollIndex(LocalDate.of(2027,11,3)))
        assertEquals(result.grid.indexOf(day),result.scrollIndex(day))
    }
}
