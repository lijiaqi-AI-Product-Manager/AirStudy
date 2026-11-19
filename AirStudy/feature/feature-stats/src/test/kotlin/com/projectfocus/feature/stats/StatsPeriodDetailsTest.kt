package com.projectfocus.feature.stats

import com.projectfocus.core.model.flight.PlannedScene
import com.projectfocus.feature.scene.source.SourceSceneCatalog
import com.projectfocus.service.focus.history.RecordedFlight
import com.projectfocus.service.focus.history.RecordedScene
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

internal object PeriodDetailsTestRecords {
    fun countdown(id: String, at: Long, minutes: Int, icon: String="study", name: String=icon): RecordedFlight.Countdown {
        val builtin=SourceSceneCatalog.builtins.first { it.iconKey==icon }
        val scene=PlannedScene("details-$icon",name,icon,builtin.color,false)
        return HeatmapTestRecords.countdown(id,at,minutes).copy(editedScene=RecordedScene(scene,1))
    }
    fun sample(day: LocalDate, zone: ZoneId): List<RecordedFlight> {
        val wall=day.atStartOfDay(zone).toInstant().toEpochMilli()
        return listOf(2 to 25,8 to 45,10 to 90,14 to 35,16 to 55,20 to 75).mapIndexed { index,(hour,minutes) ->
            countdown("flight-$index",wall+hour*3_600_000L,minutes)
        }+HeatmapTestRecords.countUp("flight-0",wall+3_600_000L)
    }
}

class StatsPeriodDetailsTest {
    private val day=LocalDate.parse("2026-11-03")
    private val zone=ZoneId.of("Asia/Shanghai")
    private val wall=day.atStartOfDay(zone).toInstant().toEpochMilli()
    private val range=StatsDateSelection(day,StatsPeriod.WEEK).range(zone)
    private fun flight(id: String,hour: Int,minutes: Int,icon: String="study")=PeriodDetailsTestRecords.countdown(id,wall+hour*3_600_000L,minutes,icon)
    private fun data(records: List<RecordedFlight>)=StatsPeriodDetails.calculate(records,range,zone)

    @Test fun `both modes retain separate identities and exact focus minutes`() {
        val result=data(listOf(flight("same",0,5),HeatmapTestRecords.countUp("same",wall)))
        assertEquals(2,result.highlights.size);assertNotEquals(result.highlights[0].key,result.highlights[1].key)
        assertEquals(6.5+1.0/60_000,result.bestDay!!.focusMinutes,1e-10)
        assertEquals(1.5+1.0/60_000,result.highlights[1].focusMinutes,1e-10)
        assertEquals(day,result.bestDay!!.date)
    }
    @Test fun `incomplete flights and starts outside the half open period are excluded`() {
        val cancelled=flight("cancelled",0,100).let { it.copy(record=it.record.copy(status="CANCELLED")) }
        val running=flight("running",0,100).let { it.copy(record=it.record.copy(status="ONGOING")) }
        val records=listOf(cancelled,running,PeriodDetailsTestRecords.countdown("before",range.fromWallMs-1,100),
            PeriodDetailsTestRecords.countdown("after",range.untilWallMs,100),PeriodDetailsTestRecords.countdown("last",range.untilWallMs-1,1))
        val result=data(records)
        assertEquals(listOf("last"),result.highlights.map { it.key.id });assertEquals(1.0,result.bestDay!!.focusMinutes,0.0)
        assertEquals(range.lastDate,result.bestDay!!.date)
    }
    @Test fun `best day and scenario ties remain stable when read order changes`() {
        val first=flight("first",0,5,"work")
        val second=PeriodDetailsTestRecords.countdown("second",wall-86_400_000L,5,"reading")
        val a=data(listOf(first,second));val b=data(listOf(second,first))
        assertEquals(a,b);assertEquals(a.hashCode(),b.hashCode())
        assertEquals(day.minusDays(1),a.bestDay!!.date);assertEquals("reading",a.topScene!!.canonicalKey)
    }
    @Test fun `highlights keep the five longest sessions with deterministic ties`() {
        val records=(1..7).map { flight("flight-$it",it,5) }
        val a=data(records);val b=data(records.reversed())
        assertEquals(a,b);assertEquals(listOf("flight-7","flight-6","flight-5","flight-4","flight-3"),a.highlights.map { it.key.id })
        try { (a.highlights as MutableList<StatsHighlight>).clear();fail("Highlights must remain immutable") } catch(expected: UnsupportedOperationException) { }
    }
    @Test fun `scene edits update the insight and highlight without changing recorded focus or identity`() {
        val original=flight("edited",2,30,"study")
        val changed=PeriodDetailsTestRecords.countdown("edited",original.recordedAtWallMs,30,"work","私人工作")
        val result=data(listOf(changed,flight("reading",3,5,"reading")))
        assertEquals("私人工作",result.topScene!!.key);assertEquals("work",result.topScene!!.iconKey)
        assertEquals(original.key,result.highlights.first().key);assertEquals(30.0,result.highlights.first().focusMinutes,0.0)
        assertEquals(original.recordedAtWallMs,result.highlights.first().startedAtWallMs)
    }
    @Test fun `day parts aggregate local hour durations and choose the first strict maximum`() {
        assertEquals(StatsDayPart.DAWN,data(listOf(flight("dawn",0,60),flight("morning",6,60))).topPeriod)
        val records=listOf(flight("dawn",0,30),flight("morning-one",6,40),flight("morning-two",8,40),flight("night",18,70))
        assertEquals(StatsDayPart.MORNING,data(records).topPeriod)
        assertEquals(StatsDayPart.AFTERNOON,data(listOf(flight("afternoon",12,5))).topPeriod)
        assertEquals(StatsDayPart.EVENING,data(listOf(flight("evening",23,5))).topPeriod)
    }
    @Test fun `empty and zero focus keep absent insights separate from existing records`() {
        for(result in listOf(data(emptyList()),data(listOf(flight("zero",0,0))))) {
            assertNull(result.bestDay);assertNull(result.topScene);assertNull(result.topPeriod)
        }
        assertTrue(data(emptyList()).highlights.isEmpty());assertEquals(1,data(listOf(flight("zero",0,0))).highlights.size)
    }
    @Test fun `best days use the selected local timezone across daylight saving boundaries`() {
        val local=ZoneId.of("America/New_York");val date=LocalDate.parse("2026-11-01")
        val selected=StatsDateSelection(date,StatsPeriod.DAY).range(local)
        val first=PeriodDetailsTestRecords.countdown("first",selected.fromWallMs,3)
        val last=PeriodDetailsTestRecords.countdown("last",selected.untilWallMs-1,5)
        val outside=PeriodDetailsTestRecords.countdown("outside",selected.untilWallMs,100)
        val result=StatsPeriodDetails.calculate(listOf(first,last,outside),selected,local)
        assertEquals(date,result.bestDay!!.date);assertEquals(8.0,result.bestDay!!.focusMinutes,0.0);assertEquals(2,result.highlights.size)
    }
}
