package com.projectfocus.feature.stats

import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.PlannedFlightMetadataCodec
import com.projectfocus.core.model.flight.PlannedScene
import com.projectfocus.core.model.flight.freeflight.*
import com.projectfocus.service.focus.history.RecordedFlight
import com.projectfocus.service.focus.history.RecordedScene
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class StatsScenesTest {
    private val zone=ZoneId.of("Asia/Shanghai")
    private val date=LocalDate.parse("2026-11-03")
    private val wall=date.atStartOfDay(zone).toInstant().toEpochMilli()
    private fun snapshot(name: String,icon: String="reading")=PlannedScene("option-$name",name,icon,0,name.isNotEmpty())
    private fun record(id: String,name: String?="",icon: String="reading",minutes: Int=5,at: Long=wall,status: String="COMPLETED")=RecordedFlight.Countdown(FlightHistoryEntity(
        id,null,"SOLO",null,"PEK","Beijing Airport","北京","CN",40.0,116.0,
        "TSN","Tianjin Airport","天津","CN",39.0,117.0,at,at+300_000,25,minutes,99.25,status,null,"01A",false,false,false,
        null,false,at,at,ownerScope="scene-statistics-owner",sceneSnapshotJson=PlannedFlightMetadataCodec.encodeScene(name?.let { snapshot(it,icon) })))
    private fun countUp(id: String="shared",name: String="Reading",type: Int=3)=run {
        val engine=FreeFlightEngine();val clock=FreeFlightClockReading(0,0,wall,"scene-statistics-clock")
        val start=engine.start(FreeFlightStartRequest(id,"scene-statistics-owner",PlannedAirport(1,"PEK","Beijing Airport","北京","CN",40.0,116.0),90.0,"01A",FreeFlightScene(type,name,"reading")),clock)
        val now=clock.copy(elapsedRealtimeMs=90_001,uptimeMillis=90_001,wallMs=wall+90_001)
        val selection=engine.prepareLanding(start,now)
        val chosen=engine.selectLanding(selection,PlannedAirport(2,"TSN","Tianjin Airport","天津","CN",39.0,117.0),selection.landingSelectionGeneration)
        RecordedFlight.CountUp(engine.completeLandingWithoutMap(engine.beginLandingWithoutMap(chosen,chosen.landingSelectionGeneration,now),now))
    }

    @Test fun `built in names merge while the first supplied key remains available for selection`() {
        val rows=StatsScenes.group(listOf(record("one","学习"),record("two","Study"),record("three","study")))
        assertEquals(1,rows.size)
        assertEquals("学习",rows.single().key)
        assertEquals("study",rows.single().canonicalKey)
        assertEquals("study",rows.single().iconKey)
        assertEquals(3,rows.single().completedFlights)
        assertEquals(15.0,rows.single().focusMinutes,0.0)
    }
    @Test fun `different custom names retain separate identities even when their icons match`() {
        val rows=StatsScenes.group(listOf(record("one","Project A"),record("two","Project B")))
        assertEquals(listOf("Project A","Project B"),rows.map { it.key })
        assertTrue(rows.all { it.iconKey=="reading" })
    }
    @Test fun `canonicalization preserves case and uses Java trim rather than unicode whitespace trimming`() {
        assertEquals("work",StatsScenes.canonical("\t Work\r"))
        assertEquals("WORK",StatsScenes.canonical("WORK"))
        assertEquals("\u00a0Work\u00a0",StatsScenes.canonical("\u00a0Work\u00a0"))
        assertNull(StatsScenes.canonical(null));assertNull(StatsScenes.canonical(""))
        assertEquals("",StatsScenes.canonical(" \t"))
    }
    @Test fun `sleep aliases retain the recorded meditation mapping without rewriting lowercase custom names`() {
        assertEquals("meditation",StatsScenes.canonical("Sleep"))
        assertEquals("meditation",StatsScenes.canonical("睡眠"))
        assertEquals("sleep",StatsScenes.canonical("sleep"))
        assertEquals(2,StatsScenes.group(listOf(record("one","Sleep"),record("two","sleep"))).size)
    }
    @Test fun `empty built in names use valid icon types and missing scene metadata adds no invented category`() {
        assertEquals("reading",StatsScenes.identity(record("built-in"))!!.key)
        assertNull(StatsScenes.identity(record("missing",null)))
        assertNull(StatsScenes.identity(record("unknown","",icon="missing-icon")))
        assertEquals("Legacy custom",StatsScenes.identity(record("custom","Legacy custom",icon="missing-icon"))!!.iconKey)
    }
    @Test fun `shared raw record identifiers from different modes both contribute precise duration`() {
        val row=StatsScenes.group(listOf(record("shared","reading"),countUp())).single()
        assertEquals(2,row.completedFlights)
        assertEquals(5+90_001/60_000.0,row.focusMinutes,1e-10)
    }
    @Test fun `edited scenes take precedence over both original record formats`() {
        val edited=RecordedScene(snapshot("Current project","work"),1)
        val rows=StatsScenes.group(listOf(record("same","reading").copy(editedScene=edited),countUp("same").copy(editedScene=edited)))
        assertEquals(1,rows.size)
        assertEquals("Current project",rows.single().key)
        assertEquals("work",rows.single().iconKey)
        assertEquals(2,rows.single().completedFlights)
    }
    @Test fun `date range and completion status constrain period data while all time data remains independent`() {
        val range=StatsDateSelection(date).custom(date,date).range(zone)
        val records=listOf(record("first","Project",at=range.fromWallMs),record("last","Project",at=range.untilWallMs-1),
            record("before","Project",at=range.fromWallMs-1),record("after","Project",at=range.untilWallMs),
            record("cancelled","Project",status="CANCELLED"),record("ongoing","Project",status="ONGOING"))
        assertEquals(2,StatsScenes.group(records,range).single().completedFlights)
        assertEquals(4,StatsScenes.group(records).single().completedFlights)
    }
    @Test fun `duration sorting preserves insertion order for ties and keeps zero duration rows`() {
        val rows=StatsScenes.group(listOf(record("a","First",minutes=5),record("b","Zero",minutes=0),record("c","Second",minutes=5),record("d","Longest",minutes=8)))
        assertEquals(listOf("Longest","First","Second","Zero"),rows.map { it.key })
    }
    @Test fun `filter matching uses canonical identity while custom names remain case sensitive`() {
        assertTrue(StatsScenes.matches(record("one","Study"),"学习"))
        assertFalse(StatsScenes.matches(record("one","Project A"),"Project B"))
        assertFalse(StatsScenes.matches(record("one","Project A"),"project a"))
        assertTrue(StatsScenes.matches(record("one",null),""))
    }
    @Test fun `display percentages follow duration totals without forcing rounded values to add to one hundred`() {
        val rows=StatsScenes.group(listOf(record("a","First",minutes=1),record("b","Second",minutes=1),record("c","Third",minutes=1)))
        assertEquals(listOf(33L,33L,33L),rows.map { Math.round(StatsScenes.percentage(it,rows)) })
        val zero=StatsScenes.group(listOf(record("none","Project",minutes=0))).single()
        assertEquals(0.0,StatsScenes.percentage(zero,listOf(zero)),0.0)
    }
}
