package com.projectfocus.feature.stats

import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.core.model.flight.PlannedFlightMetadataCodec
import com.projectfocus.core.model.flight.PlannedScene
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.*
import com.projectfocus.service.focus.history.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class StatsHistoricalFactsTest {
    private val zone=ZoneId.of("UTC")
    private val date=LocalDate.of(2026,10,30)
    private val wall=date.atStartOfDay(zone).plusHours(8).toInstant().toEpochMilli()
    private lateinit var account: HistoryAccount
    @Before fun open() { TokenStore.set("historical-statistics-a");account=HistoryAccount(TokenStore.storageScope(),TokenStore.snapshot().generation) }
    @After fun close() { TokenStore.clear() }
    private fun value(id: String="same",mode: String="down",status: String="arrived",start: Long?=wall,
        seconds: Double?=120.25,distance: Double?=42.5,end: Long?=null,scene: Boolean=true) = FlightRecordSummary.parse(buildJsonObject {
        put("flightRecordId",id);put("recordType",mode);put("flightStatus",status)
        start?.let { put("flightStartTime",Instant.ofEpochMilli(it).toString()) }
        end?.let { put("flightEndTime",Instant.ofEpochMilli(it).toString()) }
        seconds?.let { put("durationSeconds",it) };distance?.let { put("flightDistanceKm",it) }
        if(scene) { put("focusIconType",3);put("focusScenario","阅读") }
        put("arrivalAirport",buildJsonObject { put("code","TSN");put("name","Airport");put("city","天津");put("country","CN") })
    })
    private fun read(vararg rows: RecordedFlight) = HistoryReadState.Loaded(account,rows.toList(),mapOf("CN" to 1),mapOf("CN" to setOf("TSN")))
    private fun remote(vararg rows: FlightRecordSummary) = RecordSummaryState(account,rows.toList(),complete=true,totalCount=rows.size)
    private fun build(remote: RecordSummaryState,read: HistoryReadState=read(),query: StatsQuery=StatsQuery(account.generation,StatsDateSelection(date,StatsPeriod.DAY))) =
        StatsProjection.build(read,query,wall,zone,remote)
    private fun full(id: String="same") = RecordedFlight.Countdown(FlightHistoryEntity(id,null,"SOLO",null,
        "PEK","Airport","北京","CN",40.0,116.0,"TSN","Airport","天津","CN",39.0,117.0,
        wall,wall+300_000,25,5,99.25,"COMPLETED",null,"01A",false,false,false,null,false,wall,wall+300_000,
        ownerScope=account.scope,sceneSnapshotJson=PlannedFlightMetadataCodec.encodeScene(PlannedScene("reading","阅读","reading",0,true))))

    @Test fun bothModesKeepFractionalFocusAndKnownTravelFactsAcrossEveryAggregate() {
        val state=build(remote(value(end=wall+120_250),value(mode="up",seconds=180.5,end=wall+180_500)))
        val total=300.75/60.0
        assertEquals(2,state.flights);assertEquals(total,state.overview!!.totalFocusMinutes,1e-12)
        assertEquals(85.0,state.career!!.distanceKm,0.0);assertEquals(2,state.career!!.completedFlights)
        assertEquals(total,state.periodScenes.single().focusMinutes,1e-12)
        assertEquals(total,state.trend!!.buckets.sumOf { it.focusMinutes },1e-12)
        assertEquals(total,state.annualHeatmap!!.minutes(date),1e-12)
        assertEquals(total,state.hourHabits!!.minutes.sum(),1e-12)
        assertEquals(2,state.periodDetails!!.highlights.size);assertEquals(1,state.career!!.cityCount)
        assertEquals(1,state.countries);assertFalse(state.coverage.incomplete)
    }

    @Test fun availableFlightCountsDoNotTurnMissingFocusAndDistanceIntoZeroMetrics() {
        val state=build(remote(value(seconds=null,distance=null)))
        assertTrue(state.coverage.periodFlights);assertEquals(1,state.flights)
        assertFalse(state.coverage.periodFocus);assertFalse(state.coverage.allDistance)
        assertFalse(state.coverage.comparison);assertNull(state.hourHabits);assertNull(state.annualHeatmap)
        assertTrue(state.periodDetails!!.highlights.isEmpty());assertNull(state.focusComparison)
        assertTrue(state.coverage.periodTravel)
    }

    @Test fun unknownDatesKeepLifetimeFactsWithoutCreatingEpochTrendsOrCurrentDayVisits() {
        val state=build(remote(value(start=null)))
        assertEquals(1,state.career!!.completedFlights);assertEquals(42.5,state.career!!.distanceKm,0.0)
        assertTrue(state.coverage.allFlights);assertTrue(state.coverage.allDistance)
        assertFalse(state.coverage.periodFlights);assertFalse(state.coverage.periodFocus)
        assertFalse(state.coverage.annual);assertNull(state.annualHeatmap)
        assertTrue(state.trend!!.buckets.isEmpty());assertNull(state.periodDetails!!.bestDay)
    }

    @Test fun unknownStatusesCannotBecomeCancelledOrCompletedStatistics() {
        for(status in listOf("future","pending")) {
            val state=build(remote(value(status=status)))
            assertFalse(state.coverage.periodFlights);assertFalse(state.coverage.allFlights)
            assertEquals(0,state.career!!.completedFlights);assertTrue(state.periodScenes.isEmpty())
        }
        val cancelled=build(remote(value(status="cancelled"),value(mode="up",status="landing")))
        assertEquals(0,cancelled.flights);assertTrue(cancelled.coverage.periodFlights)
    }

    @Test fun aFullArchiveReplacesItsMatchingSummaryWithoutHidingTheOtherMode() {
        val state=build(remote(value(),value(mode="up")),read(full()))
        assertEquals(2,state.flights);assertEquals(5.0+120.25/60.0,state.overview!!.totalFocusMinutes,1e-12)
        assertEquals(99.25+42.5,state.career!!.distanceKm,0.0)
        val aliased=remote(value("remote"),value("remote","up")).copy(aliases=mapOf(
            FlightRecordKey(FlightRecordKind.COUNTDOWN,"local") to (FlightRecordMode.DOWN to "remote")))
        assertEquals(2,build(aliased,read(full("local"))).flights)
    }

    @Test fun anUnresolvedImportAliasCannotTemporarilyDoubleCountTheHistoricalRow() {
        val row=full("cloud-summary-down-local")
        val metadata=remote(value("remote"))
        val pending=build(metadata,read(row))
        assertEquals(1,pending.flights);assertFalse(pending.coverage.periodFlights)
        val mapped=metadata.copy(aliases=mapOf(row.key to (FlightRecordMode.DOWN to "remote")))
        val complete=build(mapped,read(row))
        assertEquals(1,complete.flights);assertTrue(complete.coverage.periodFlights)
    }

    @Test fun incompletePagesAndUnreadableCachesKeepPreviouslyKnownFactsButNotFalseTotals() {
        val partial=build(remote(value()).copy(complete=false,totalCount=20))
        assertEquals(1,partial.career!!.completedFlights);assertFalse(partial.coverage.allFlights)
        assertFalse(partial.coverage.periodFocus)
        assertFalse(build(remote(value()).copy(failed=true)).coverage.periodFlights)
    }

    @Test fun periodAndSceneSelectionStayIndependentFromLifetimeFactsAndOtherYears() {
        val old=wall-366L*86_400_000
        val rows=remote(value(),value("old",start=old),value("tomorrow",start=wall+86_400_000))
        val q=StatsQuery(account.generation,StatsDateSelection(date,StatsPeriod.DAY),sceneKey="Reading",heatmapYear=2025)
        val state=build(rows,query=q)
        assertEquals(1,state.flights);assertEquals(3,state.career!!.completedFlights)
        assertEquals(1,state.annualHeatmap!!.days.size);assertTrue(state.coverage.periodFocus)
        assertEquals(0,build(rows,query=q.copy(sceneKey="work")).flights)
    }

    @Test fun partialTimingNeverInventsPauseLocationsAndExactIntervalsRespectDst() {
        val paused=build(remote(value(seconds=120.0,end=wall+180_000)))
        assertTrue(paused.coverage.periodFocus);assertFalse(paused.coverage.hours);assertNull(paused.hourHabits)
        val ny=ZoneId.of("America/New_York");val start=Instant.parse("2026-11-01T05:30:00Z").toEpochMilli()
        val record=value(start=start,seconds=7200.0,end=start+7_200_000)
        val q=StatsQuery(account.generation,StatsDateSelection(LocalDate.of(2026,11,1),StatsPeriod.DAY))
        val state=StatsProjection.build(read(),q,start,ny,remote(record))
        assertEquals(90.0,state.hourHabits!!.minutes[1],0.0);assertEquals(30.0,state.hourHabits!!.minutes[2],0.0)
    }

    @Test fun aLateCacheFromAnotherAccountCannotContributeEvenWithTheSameIdentifier() {
        val previous=remote(value());TokenStore.set("historical-statistics-b")
        val owner=HistoryAccount(TokenStore.storageScope(),TokenStore.snapshot().generation)
        val loaded=HistoryReadState.Loaded(owner,emptyList(),emptyMap(),emptyMap())
        val state=StatsProjection.build(loaded,StatsQuery(owner.generation,StatsDateSelection(date)),wall,zone,previous)
        assertEquals(owner,state.account);assertEquals(0,state.career!!.completedFlights)
        assertFalse(state.coverage.allFlights);assertTrue(state.allTimeScenes.isEmpty())
    }
}
