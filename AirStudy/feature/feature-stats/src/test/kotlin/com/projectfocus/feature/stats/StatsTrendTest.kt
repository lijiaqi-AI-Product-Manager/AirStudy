package com.projectfocus.feature.stats

import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.PlannedFlightMetadataCodec
import com.projectfocus.core.model.flight.PlannedScene
import com.projectfocus.core.model.flight.freeflight.*
import com.projectfocus.service.focus.history.RecordedFlight
import com.projectfocus.service.focus.history.RecordedScene
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class StatsTrendTest {
    private val zone=ZoneId.of("Asia/Shanghai")
    private val day=LocalDate.parse("2026-11-03")
    private val wall=day.atStartOfDay(zone).toInstant().toEpochMilli()
    private val owner="statistics-trend-owner"
    private fun flight(id: String,at: Long=wall,minutes: Int=5,status: String="COMPLETED",scene: String?="reading")=RecordedFlight.Countdown(FlightHistoryEntity(
        id,null,"SOLO",null,"PEK","Beijing Airport","北京","CN",40.0,116.0,
        "TSN","Tianjin Airport","天津","CN",39.0,117.0,at,at+300_000,
        25,minutes,99.25,status,null,"01A",false,false,false,null,false,at,at,ownerScope=owner,
        sceneSnapshotJson=PlannedFlightMetadataCodec.encodeScene(scene?.let { PlannedScene("scene-$it",it,it,0,false) })))
    private fun countUp(id: String,at: Long=wall): RecordedFlight.CountUp {
        val engine=FreeFlightEngine();val clock=FreeFlightClockReading(0,0,at,"statistics-trend-clock")
        val start=engine.start(FreeFlightStartRequest(id,owner,PlannedAirport(1,"PEK","Beijing Airport","北京","CN",40.0,116.0),
            90.0,"01A",FreeFlightScene(3,"Reading","reading")),clock)
        val now=clock.copy(elapsedRealtimeMs=90_001,uptimeMillis=90_001,wallMs=at+90_001)
        val selection=engine.prepareLanding(start,now)
        val picked=engine.selectLanding(selection,PlannedAirport(2,"TSN","Tianjin Airport","天津","CN",39.0,117.0),selection.landingSelectionGeneration)
        return RecordedFlight.CountUp(engine.completeLandingWithoutMap(engine.beginLandingWithoutMap(picked,picked.landingSelectionGeneration,now),now))
    }
    private fun range(period: StatsPeriod=StatsPeriod.DAY)=StatsDateSelection(day,period).range(zone)

    @Test fun `a day has hourly points including empty hours and half open boundaries`() {
        val selected=range()
        val result=StatsTrend.calculate(listOf(flight("first",selected.fromWallMs),flight("last",selected.untilWallMs-1),
            flight("before",selected.fromWallMs-1),flight("after",selected.untilWallMs)),selected,zone)
        assertEquals(StatsTrendGranularity.HOUR,result.granularity)
        assertEquals(24,result.buckets.size)
        assertEquals(1,result.buckets.first().completedFlights)
        assertEquals(1,result.buckets.last().completedFlights)
        assertTrue(result.buckets.subList(1,23).all { it.completedFlights==0 && it.focusMinutes==0.0 && it.scenes.isEmpty() })
        assertEquals(2,result.buckets.sumOf { it.completedFlights })
    }
    @Test fun `both modes with a shared ID contribute without per flight minute rounding`() {
        val records=listOf(flight("same"),countUp("same"),countUp("second"))
        val result=StatsTrend.calculate(records,range(),zone)
        assertEquals(3,result.buckets.first().completedFlights)
        assertEquals(8.0+2.0/60_000.0,result.buckets.first().focusMinutes,1e-10)
        assertEquals(3,result.buckets.first().scenes.single().completedFlights)
        assertEquals(3.0,result.peak(StatsTrendMetric.COMPLETED),0.0)
        assertEquals(StatsOverviewMetrics.calculate(records,range(),zone).totalFocusMinutes,result.buckets.sumOf { it.focusMinutes },1e-10)
    }
    @Test fun `cancelled and ongoing records do not create chart data`() {
        val result=StatsTrend.calculate(listOf(flight("cancelled",status="CANCELLED"),flight("active",status="ONGOING")),range(),zone)
        assertTrue(result.buckets.isEmpty())
        assertEquals(0.0,result.peak(StatsTrendMetric.COMPLETED),0.0)
    }
    @Test fun `a zero minute completed flight retains its completed count`() {
        val result=StatsTrend.calculate(listOf(flight("zero",minutes=0)),range(),zone)
        assertEquals(1,result.buckets.first().completedFlights)
        assertEquals(0.0,result.peak(StatsTrendMetric.FOCUS),0.0)
        assertEquals(1,result.buckets.first().scenes.single().completedFlights)
    }
    @Test fun `missing scene metadata keeps the total without inventing a scene segment`() {
        val result=StatsTrend.calculate(listOf(flight("legacy",scene=null)),range(),zone)
        assertEquals(5.0,result.buckets.first().focusMinutes,0.0)
        assertEquals(1,result.buckets.first().completedFlights)
        assertTrue(result.buckets.first().scenes.isEmpty())
    }
    @Test fun `weeks use local dates and count a flight crossing midnight in its start bucket once`() {
        val selected=range(StatsPeriod.WEEK)
        val record=flight("cross-midnight",wall-60_000,minutes=90)
        val result=StatsTrend.calculate(listOf(record),selected,zone)
        assertEquals(StatsTrendGranularity.DAY,result.granularity)
        assertEquals(7,result.buckets.size)
        assertEquals(1,result.buckets[0].completedFlights)
        assertEquals(90.0,result.buckets[0].focusMinutes,0.0)
        assertEquals(0,result.buckets[1].completedFlights)
    }
    @Test fun `annual data uses all twelve local calendar months including leap day`() {
        val leap=LocalDate.parse("2024-02-29")
        val selected=StatsDateSelection(leap,StatsPeriod.YEAR).range(zone)
        val result=StatsTrend.calculate(listOf(flight("leap",leap.atStartOfDay(zone).toInstant().toEpochMilli()),
            flight("year-end",selected.untilWallMs-1),flight("next-year",selected.untilWallMs)),selected,zone)
        assertEquals(StatsTrendGranularity.MONTH,result.granularity)
        assertEquals(12,result.buckets.size)
        assertEquals(1,result.buckets[1].completedFlights)
        assertEquals(1,result.buckets[11].completedFlights)
        assertEquals(2,result.buckets.sumOf { it.completedFlights })
    }
    @Test fun `a monthly custom chart aligns its first label without including data before the selected range`() {
        val first=day.withDayOfMonth(10)
        val last=first.plusDays(250)
        val selected=StatsDateSelection(first).custom(first,last).range(zone)
        val result=StatsTrend.calculate(listOf(flight("before",selected.fromWallMs-1),flight("first",selected.fromWallMs),flight("last",selected.untilWallMs-1)),selected,zone)
        assertEquals(StatsTrendGranularity.MONTH,result.granularity)
        assertEquals(first.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli(),result.buckets.first().fromWallMs)
        assertEquals(selected.untilWallMs,result.buckets.last().untilWallMs)
        assertEquals(2,result.buckets.sumOf { it.completedFlights })
    }
    @Test fun `custom weekly buckets stay anchored to the selected starting date`() {
        val first=day.plusDays(1)
        val selected=StatsDateSelection(first).custom(first,first.plusDays(31)).range(zone)
        val result=StatsTrend.calculate(listOf(flight("first",selected.fromWallMs),flight("next",selected.fromWallMs+7*86_400_000L)),selected,zone)
        assertEquals(StatsTrendGranularity.WEEK,result.granularity)
        assertEquals(5,result.buckets.size)
        assertEquals(1,result.buckets[0].completedFlights)
        assertEquals(1,result.buckets[1].completedFlights)
        assertEquals(selected.untilWallMs,result.buckets.last().untilWallMs)
    }
    @Test fun `daylight saving repeated hours remain separate timeline buckets`() {
        val local=ZoneId.of("America/New_York")
        val selected=StatsDateSelection(LocalDate.parse("2026-11-01"),StatsPeriod.DAY).range(local)
        val first=Instant.parse("2026-11-01T05:30:00Z").toEpochMilli()
        val second=Instant.parse("2026-11-01T06:30:00Z").toEpochMilli()
        val result=StatsTrend.calculate(listOf(flight("first",first),flight("second",second)),selected,local)
        assertEquals(25,result.buckets.size)
        assertEquals(1,result.buckets[1].completedFlights)
        assertEquals(1,result.buckets[2].completedFlights)
        assertEquals(2,result.buckets.sumOf { it.completedFlights })
    }
    @Test fun `daylight saving missing hours do not leave an extra bucket`() {
        val local=ZoneId.of("America/New_York")
        val selected=StatsDateSelection(LocalDate.parse("2026-03-08"),StatsPeriod.DAY).range(local)
        val result=StatsTrend.calculate(listOf(flight("last",selected.untilWallMs-1)),selected,local)
        assertEquals(23,result.buckets.size)
        assertEquals(1,result.buckets.last().completedFlights)
    }
    @Test fun `scene edits change segments while total duration and completed counts remain intact`() {
        val original=flight("same",scene="reading")
        val edited=original.copy(editedScene=RecordedScene(PlannedScene("updated","Current project","work",0,true),1))
        val before=StatsTrend.calculate(listOf(original),range(),zone)
        val after=StatsTrend.calculate(listOf(edited),range(),zone)
        assertEquals(before.buckets.first().focusMinutes,after.buckets.first().focusMinutes,0.0)
        assertEquals(before.buckets.first().completedFlights,after.buckets.first().completedFlights)
        assertEquals("Current project",after.buckets.first().scenes.single().key)
        assertEquals("work",after.buckets.first().scenes.single().iconKey)
    }
}
