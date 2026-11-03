package com.projectfocus.feature.stats

import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.core.model.flight.PlannedAirport
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
    }
}
