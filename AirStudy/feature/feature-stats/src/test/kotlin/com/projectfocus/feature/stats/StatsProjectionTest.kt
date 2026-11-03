package com.projectfocus.feature.stats

import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.PlannedFlightMetadataCodec
import com.projectfocus.core.model.flight.PlannedScene
import com.projectfocus.core.model.flight.freeflight.*
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.service.focus.history.*
import java.time.LocalDate
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class StatsProjectionTest {
    @Test fun `heatmap year is independent from the summary period and keeps exact scene filtered minutes`() {
        val before=date.minusYears(1)
        val loaded=read(legacy("current",now),independent("precise",now),legacy("old",before.atStartOfDay(zone).toInstant().toEpochMilli()))
        val selected=StatsQuery(account.generation,StatsDateSelection(date,StatsPeriod.DAY),7,heatmapYear=2025,heatmapRevision=9)
        val state=StatsProjection.build(loaded,selected,now,zone)
        assertEquals(2,state.flights)
        assertEquals(2025,state.heatmapYear)
        assertEquals(7L,state.queryRevision)
        assertEquals(9L,state.heatmapRevision)
        assertTrue(state.canNextHeatmapYear)
        assertEquals(mapOf(before to 5.0),state.annualHeatmap!!.days)
        val filtered=StatsProjection.build(loaded,selected.copy(sceneKey="study",heatmapYear=2026),now,zone)
        assertEquals(1,filtered.flights)
        assertEquals(1.5+1.0/60_000,filtered.annualHeatmap!!.minutes(date),1e-10)
        val failed=StatsProjection.build(HistoryReadState.Failure(account,IllegalStateException("unavailable")),selected,now,zone)
        assertTrue(failed.readFailed)
        assertEquals(2025,failed.heatmapYear)
        assertNull(failed.annualHeatmap)
    }

    @Test fun `a cross year week can select the ending year while a new account resets it`() {
        val ending=StatsDateSelection(LocalDate.parse("2026-12-31"))
        assertEquals(2027,ending.range(zone).lastDate.year)
        val selected=StatsQuery(account.generation,ending,4,heatmapYear=2027,heatmapRevision=3)
        val result=StatsProjection.build(read(),selected,now,zone)
        assertEquals(2027,result.heatmapYear)
        assertFalse(result.canNextHeatmapYear)
        assertTrue(result.annualHeatmap!!.days.isEmpty())
        TokenStore.set("statistics-projection-next-year-owner")
        val next=HistoryAccount(TokenStore.storageScope(),TokenStore.snapshot().generation)
        val nextState=StatsProjection.build(HistoryReadState.Loaded(next,emptyList(),emptyMap(),emptyMap()),selected,now,zone)
        assertEquals(2026,nextState.heatmapYear)
        assertEquals(0L,nextState.heatmapRevision)
        assertEquals(StatsDateSelection.current(now,zone),nextState.dates)
    }

    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.parse("2026-11-03")
    private val now = date.atStartOfDay(zone).toInstant().toEpochMilli()
    private lateinit var account: HistoryAccount
    @Before fun start() {
        TokenStore.set("statistics-projection-owner")
        account = HistoryAccount(TokenStore.storageScope(), TokenStore.snapshot().generation)
    }
    @After fun finish() { TokenStore.clear() }

    private fun legacy(id: String, at: Long, status: String = "COMPLETED") = RecordedFlight.Countdown(FlightHistoryEntity(
        id,null,"SOLO",null,"PEK","Beijing Airport","北京","CN",40.0,116.0,
        "TSN","Tianjin Airport","天津","CN",39.0,117.0,at,at+300_000,
        25,5,99.25,status,null,"01A",false,false,false,null,false,at,at,ownerScope=account.scope))
    private fun independent(id: String, at: Long): RecordedFlight.CountUp {
        val engine = FreeFlightEngine()
        val clock = FreeFlightClockReading(0, 0, at, "statistics-projection-clock")
        val start = engine.start(FreeFlightStartRequest(id, account.scope,
            PlannedAirport(1,"PEK","Beijing Airport","北京","CN",40.0,116.0),90.0,"01A",FreeFlightScene(1,"学习","study")),clock)
        val arrival = clock.copy(elapsedRealtimeMs=90_001,uptimeMillis=90_001,wallMs=at+90_001)
        val selection = engine.prepareLanding(start,arrival)
        val destination = engine.selectLanding(selection,PlannedAirport(2,"TSN","Tianjin Airport","天津","CN",39.0,117.0),selection.landingSelectionGeneration)
        return RecordedFlight.CountUp(engine.completeLandingWithoutMap(engine.beginLandingWithoutMap(destination,destination.landingSelectionGeneration,arrival),arrival))
    }
    private fun read(vararg records: RecordedFlight) = HistoryReadState.Loaded(account,records.toList(),mapOf("CN" to 2),mapOf("CN" to setOf("PEK","TSN")))
    private fun query(dates: StatsDateSelection = StatsDateSelection.current(now,zone)) = StatsQuery(account.generation,dates)

    @Test fun `both modes retain shared IDs and completed values inside the selected range`() {
        val range = query().dates.range(zone)
        val up = independent("same-id",now)
        val state = StatsProjection.build(read(legacy("same-id",range.fromWallMs),up,
            legacy("next-week",range.untilWallMs),legacy("last-week",range.fromWallMs-1),
            legacy("ongoing",now,"ONGOING"),legacy("cancelled",now,"CANCELLED")),query(),now,zone)
        assertFalse(state.isLoading)
        assertEquals(2,state.flights)
        assertEquals(99.25+up.distanceKm,state.distanceKm,1e-8)
        assertEquals(5+up.focusMinutes,state.focusMinutes)
        assertEquals(1,state.airports)
        assertEquals(1,state.countries)
        assertEquals(2,state.countryBreakdown.single().tripCount)
    }

    @Test fun `custom same-date query includes the last millisecond and excludes next midnight`() {
        val dates = StatsDateSelection(date).custom(date,date)
        val range = dates.range(zone)
        val state = StatsProjection.build(read(legacy("first",range.fromWallMs),legacy("last",range.untilWallMs-1),
            legacy("next-day",range.untilWallMs)),query(dates),now,zone)
        assertEquals(2,state.flights)
    }

    @Test fun `account change clears old results and ignores old date filters`() {
        val old = read(legacy("old-account",now))
        val oldQuery = query(StatsDateSelection(date).custom(date,date))
        TokenStore.set("statistics-projection-next-owner")
        val obsolete = StatsProjection.build(old,oldQuery,now,zone)
        assertNull(obsolete.account)
        assertNull(obsolete.dates)
        assertEquals(0,obsolete.flights)
        val next = HistoryAccount(TokenStore.storageScope(),TokenStore.snapshot().generation)
        val current = StatsProjection.build(HistoryReadState.Loaded(next,emptyList(),emptyMap(),emptyMap()),oldQuery,now,zone)
        assertEquals(StatsPeriod.WEEK,current.dates!!.period)
        assertFalse(current.isLoading)
        assertEquals(0,current.flights)
    }

    @Test fun `read failures retain retry state without successful zero totals`() {
        val state = StatsProjection.build(HistoryReadState.Failure(account,IllegalStateException("fixture read error")),query(),now,zone)
        assertTrue(state.isLoading)
        assertTrue(state.readFailed)
        assertNotNull(state.dateRange)
        assertNull(state.overview)
        assertNull(state.trend)
    }

    @Test fun `headline rounds precise dual mode totals after aggregation`() {
        val state=StatsProjection.build(read(independent("first",now),independent("second",now)),query(),now,zone)
        assertEquals(2,state.flights)
        assertEquals(3,state.focusMinutes)
        assertEquals(3.0+2.0/60_000.0,state.overview!!.totalFocusMinutes,1e-10)
        assertEquals(1,state.overview!!.activeDays)
        assertEquals(state.overview!!.totalFocusMinutes,state.focusComparison!!.deltaMinutes,1e-10)
        assertNull(state.focusComparison!!.ratio)
    }

    @Test fun `scene filtering changes the headline while both distributions remain unfiltered`() {
        val today = StatsDateSelection(date).custom(date,date)
        val range = today.range(zone)
        fun study(id: String, at: Long, title: String) = legacy(id,at).let { row ->
            row.copy(record=row.record.copy(sceneSnapshotJson=PlannedFlightMetadataCodec.encodeScene(PlannedScene("study-option",title,"study",0,true))))
        }
        val loaded = read(study("before",range.fromWallMs-1,"Study"),study("now",now,"学习"),
            legacy("no-scene",now),independent("other-scene",now).let { row ->
                row.copy(archive=row.archive.copy(scene=FreeFlightScene(3,"reading","reading")))
            })
        val state = StatsProjection.build(loaded,query(today).copy(sceneKey="Study"),now,zone)
        assertEquals("Study",state.selectedSceneKey)
        assertEquals(1,state.flights)
        assertEquals(5,state.focusMinutes)
        assertEquals(setOf("学习","reading"),state.periodScenes.map { it.key }.toSet())
        assertEquals(2,state.allTimeScenes.first { it.canonicalKey=="study" }.completedFlights)
        assertEquals(1,state.periodScenes.first { it.canonicalKey=="study" }.completedFlights)
        assertEquals(2,state.periodScenes.sumOf { it.completedFlights })
        assertEquals(3,state.allTimeScenes.sumOf { it.completedFlights })
        assertEquals(1,state.trend!!.buckets.sumOf { it.completedFlights })
        assertEquals(5.0,state.trend!!.buckets.sumOf { it.focusMinutes },0.0)
        assertEquals(0.0,state.focusComparison!!.deltaMinutes,0.0)
        assertEquals(0.0,state.focusComparison!!.ratio!!,0.0)
    }

    @Test fun `an absent scene retains a clearable filter without inventing a distribution row`() {
        val selected = query().copy(sceneKey="Removed project")
        val state = StatsProjection.build(read(legacy("no-scene",now)),selected,now,zone)
        assertEquals(0,state.flights)
        assertEquals("Removed project",state.selectedSceneKey)
        assertTrue(state.periodScenes.isEmpty())
        val failure = StatsProjection.build(HistoryReadState.Failure(account,IllegalStateException("fixture")),selected,now,zone)
        assertEquals("Removed project",failure.selectedSceneKey)
        assertTrue(failure.readFailed)
        assertNull(failure.overview)
    }

    @Test fun `an account transition discards the scene filter and all old distributions`() {
        val selected=query().copy(sceneKey="学习")
        TokenStore.set("statistics-next-scene-owner")
        val next=HistoryAccount(TokenStore.storageScope(),TokenStore.snapshot().generation)
        val state=StatsProjection.build(HistoryReadState.Loaded(next,emptyList(),emptyMap(),emptyMap()),selected,now,zone)
        assertEquals("",state.selectedSceneKey)
        assertTrue(state.periodScenes.isEmpty())
        assertTrue(state.allTimeScenes.isEmpty())
    }
}
