package com.projectfocus.feature.stats

import com.projectfocus.service.focus.history.RecordedFlight
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class StatsHourHabitsTest {
    private val day=LocalDate.parse("2026-11-03")
    private val zone=ZoneId.of("Asia/Shanghai")
    private fun at(date: LocalDate=day,hour: Int=0,minute: Int=0,local: ZoneId=zone)=date.atTime(hour,minute).atZone(local).toInstant().toEpochMilli()
    private fun flight(id: String,at: Long,minutes: Int)=HeatmapTestRecords.countdown(id,at,minutes)
    private fun data(records: List<RecordedFlight>,local: ZoneId=zone,date: LocalDate=day)=StatsHourHabits.calculate(records,StatsDateSelection(date,StatsPeriod.DAY).range(local),local)
    @Test fun `both modes split exact actual duration across hours without merging shared IDs`() {
        val result=data(listOf(flight("same",at(hour=1,minute=50),20),HeatmapTestRecords.countUp("same",at(hour=1,minute=59))))
        assertEquals(11.0,result.minutes[1],1e-10)
        assertEquals(10.5+1.0/60_000,result.minutes[2],1e-10)
        assertEquals(21.5+1.0/60_000,result.minutes.sum(),1e-10)
        assertEquals(2,result.activeHours);assertEquals(1,result.peakHour)
    }
    @Test fun `midnight wraps hour bins while retaining the whole selected flight`() {
        val result=data(listOf(flight("overnight",at(hour=23,minute=30),120)))
        assertEquals(30.0,result.minutes[23],0.0);assertEquals(60.0,result.minutes[0],0.0);assertEquals(30.0,result.minutes[1],0.0)
        assertEquals(120.0,result.minutes.sum(),0.0)
    }
    @Test fun `incomplete and out of range starts do not contribute even when a duration overlaps`() {
        val range=StatsDateSelection(day,StatsPeriod.DAY).range(zone)
        val cancelled=flight("cancelled",at(),50).let { it.copy(record=it.record.copy(status="CANCELLED")) }
        val running=flight("running",at(),50).let { it.copy(record=it.record.copy(status="ONGOING")) }
        val result=data(listOf(cancelled,running,flight("before",range.fromWallMs-1,100),flight("after",range.untilWallMs,100),flight("last-ms",range.untilWallMs-1,1)))
        assertEquals(1.0,result.minutes.sum(),1e-10)
        assertEquals(1.0/60_000,result.minutes[23],1e-10)
        assertEquals(1.0-1.0/60_000,result.minutes[0],1e-10)
    }
    @Test fun `empty and zero minute records have no peak or active hours`() {
        for(result in listOf(data(emptyList()),data(listOf(flight("zero",at(),0))))) {
            assertEquals(24,result.minutes.size);assertTrue(result.minutes.all { it==0.0 })
            assertNull(result.peakHour);assertEquals(0,result.activeHours)
        }
    }
    @Test fun `the earliest strict maximum wins a tie and returned bins cannot be mutated`() {
        val first=data(listOf(flight("six",at(hour=6),5),flight("one",at(hour=1),5)))
        val second=data(listOf(flight("one",at(hour=1),5),flight("six",at(hour=6),5)))
        assertEquals(1,first.peakHour);assertEquals(first,second);assertEquals(first.hashCode(),second.hashCode())
        try { (first.minutes as MutableList<Double>)[1]=900.0;fail("Hour bins must remain immutable") } catch(expected: UnsupportedOperationException) { }
    }
    @Test fun `spring forward omits the missing local hour and preserves actual elapsed minutes`() {
        val local=ZoneId.of("America/New_York");val date=LocalDate.parse("2026-03-08")
        val result=data(listOf(flight("spring",at(date,0,30,local),180)),local,date)
        assertEquals(listOf(30.0,60.0,0.0,60.0,30.0),result.minutes.take(5))
        assertEquals(180.0,result.minutes.sum(),0.0);assertEquals(1,result.peakHour)
    }
    @Test fun `fall back combines both physical occurrences of a repeated hour`() {
        val local=ZoneId.of("America/New_York");val date=LocalDate.parse("2026-11-01")
        val result=data(listOf(flight("fall",at(date,0,30,local),180)),local,date)
        assertEquals(listOf(30.0,120.0,30.0),result.minutes.take(3))
        assertEquals(180.0,result.minutes.sum(),0.0);assertEquals(1,result.peakHour)
    }
    @Test fun `a half hour daylight saving jump still stops at the next local hour boundary`() {
        val local=ZoneId.of("Australia/Lord_Howe");val date=LocalDate.parse("2026-10-04")
        val result=data(listOf(flight("half-hour-jump",at(date,1,30,local),120)),local,date)
        assertEquals(30.0,result.minutes[1],0.0);assertEquals(30.0,result.minutes[2],0.0);assertEquals(60.0,result.minutes[3],0.0)
        assertEquals(120.0,result.minutes.sum(),0.0)
    }
    @Test fun `quarter hour timezone offsets split against local boundaries`() {
        val local=ZoneId.of("Asia/Kathmandu")
        val result=data(listOf(flight("nepal",at(day,6,45,local),30)),local)
        assertEquals(15.0,result.minutes[6],0.0);assertEquals(15.0,result.minutes[7],0.0)
        assertEquals(30.0,result.minutes.sum(),0.0)
    }
    @Test fun `whole days allocate once to every hour and preserve the residual interval`() {
        val result=data(listOf(flight("multi-day",at(hour=1),2970)))
        assertEquals(24,result.activeHours);assertEquals(180.0,result.minutes[1],0.0);assertEquals(150.0,result.minutes[2],0.0)
        assertEquals(120.0,result.minutes[0],0.0);assertEquals(2970.0,result.minutes.sum(),0.0)
    }
}
