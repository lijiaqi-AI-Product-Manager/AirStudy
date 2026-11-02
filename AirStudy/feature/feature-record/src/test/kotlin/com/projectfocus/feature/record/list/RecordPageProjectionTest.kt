package com.projectfocus.feature.record.list

import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.freeflight.*
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.service.focus.history.*
import java.time.Instant
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RecordPageProjectionTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val wall = Instant.parse("2026-03-29T12:00:00Z").toEpochMilli()
    private lateinit var account: HistoryAccount
    @Before fun account() {
        TokenStore.set("record-page-projection")
        account = HistoryAccount(TokenStore.storageScope(), TokenStore.snapshot().generation)
    }
    @After fun close() { TokenStore.clear() }
    private fun row(id: String, at: Long = wall, status: String = "COMPLETED") = RecordedFlight.Countdown(FlightHistoryEntity(
        id,null,"SOLO",null,"PEK","Beijing Airport","Beijing","CN",40.0,116.0,
        "TSN","Tianjin Airport","Tianjin","CN",39.0,117.0,at,at+300_000,25,5,99.25,
        status,null,"01A",false,false,false,null,false,at+86_400_000,at,ownerScope=account.scope))
    private fun read(rows: List<RecordedFlight>) = HistoryReadState.Loaded(account,rows,emptyMap(),emptyMap())
    private fun query(status: RecordStatusFilter = RecordStatusFilter.ALL, period: RecordPeriod = RecordPeriod.ALL, page: Int = 1) =
        RecordPageQuery(status,period,page,account.generation)

    @Test fun pagesStayBoundedAfterRowsDisappearAndEqualIdentifiersKeepBothModes() {
        val engine = FreeFlightEngine()
        val now = FreeFlightClockReading(0,0,wall,"record-page-clock")
        val start = engine.start(FreeFlightStartRequest("same-id",account.scope,
            PlannedAirport(1,"PEK","Beijing Airport","Beijing","CN",40.0,116.0),90.0,"01A",
            FreeFlightScene(3,"Reading","reading"),"AS42"),now)
        val up = RecordedFlight.CountUp(engine.cancel(start,now.copy(elapsedRealtimeMs=90_000,uptimeMillis=90_000,wallMs=wall+90_000)))
        val rows = listOf(row("same-id"),up) + (1..9).map { row("row-$it",wall-it) }
        val first = RecordPageProjection.build(read(rows),query(),wall,zone)
        assertEquals(10,first.items.size)
        assertEquals(2,first.pageCount)
        assertEquals(2,first.items.count { it.id == "same-id" })
        assertEquals(2,first.items.filter { it.id=="same-id" }.map { it.key }.toSet().size)
        val second = RecordPageProjection.build(read(rows),query(page=2),wall,zone)
        assertEquals(listOf("row-9"),second.items.map { it.id })
        val shrunk = RecordPageProjection.build(read(rows.take(2)),query(page=2),wall,zone)
        assertEquals(1,shrunk.page)
        assertEquals(1,shrunk.pageCount)
        assertEquals(2,shrunk.items.size)
    }

    @Test fun localCalendarRangesUseDepartureDateAndRespectSummerTimeBoundaries() {
        val today = Instant.ofEpochMilli(wall).atZone(zone).toLocalDate()
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(23*3_600_000L,end-start)
        val rows = listOf(row("before",start-1),row("first",start),row("last",end-1),row("after",end))
        val day = RecordPageProjection.build(read(rows),query(period=RecordPeriod.DAY),wall,zone)
        assertEquals(listOf("last","first"),day.items.map { it.id })
        val monday = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
        val week = RecordPageProjection.build(read(listOf(row("sunday-before",monday-1),row("monday",monday),row("sunday",end-1),row("next-monday",end))),
            query(period=RecordPeriod.WEEK),wall,zone)
        assertEquals(listOf("sunday","monday"),week.items.map { it.id })
        val monthStart = today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val nextMonth = today.withDayOfMonth(1).plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val month = RecordPageProjection.build(read(listOf(row("previous-month",monthStart-1),row("month-first",monthStart),row("next-month",nextMonth))),
            query(period=RecordPeriod.MONTH),wall,zone)
        assertEquals(listOf("month-first"),month.items.map { it.id })
    }

    @Test fun statusFiltersUseStoredStateWhileGlobalDistanceIncludesOnlyCompletedFlights() {
        val read = read(listOf(row("arrived"),row("flying",status="ONGOING"),row("cancelled",status="CANCELLED")))
        listOf(RecordStatusFilter.ARRIVED to "arrived",RecordStatusFilter.IN_PROGRESS to "flying",RecordStatusFilter.CANCELLED to "cancelled")
            .forEach { (filter,id) ->
                val page = RecordPageProjection.build(read,query(status=filter),wall,zone)
                assertEquals(listOf(id),page.items.map { it.id })
                assertEquals(99.25,requireNotNull(page.completedDistanceKm),0.0)
            }
    }

    @Test fun staleIdentityAndReadFailureCannotBecomeAnEmptySuccessfulPage() {
        val loaded = read(listOf(row("private-record")))
        val failure = RecordPageProjection.build(HistoryReadState.Failure(account,IllegalStateException("unreadable")),query(),wall,zone)
        assertFalse(failure.loading); assertTrue(failure.failed); assertFalse(failure.ready)
        TokenStore.set("another-record-page-account")
        val stale = RecordPageProjection.build(loaded,query(),wall,zone)
        assertFalse(stale.ready); assertTrue(stale.items.isEmpty()); assertNull(stale.completedDistanceKm)
        val fresh = HistoryAccount(TokenStore.storageScope(),TokenStore.snapshot().generation)
        val oldQuery = query(page=2)
        account = fresh
        val page = RecordPageProjection.build(read((1..11).map { row("new-$it") }),oldQuery,wall,zone)
        assertEquals(1,page.page)
    }

    @Test fun anUnknownStoredStatusIsRejectedInsteadOfLabelledCancelled() {
        assertThrows(IllegalArgumentException::class.java) {
            RecordPageProjection.build(read(listOf(row("corrupt",status="FUTURE_STATUS"))),query(),wall,zone)
        }
    }
}
