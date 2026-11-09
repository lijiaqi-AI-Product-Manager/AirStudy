package com.projectfocus.feature.record.list

import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.freeflight.*
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.*
import kotlinx.serialization.json.*
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


    private fun summary(id: String, mode: String = "down", status: String = "arrived", extra: String = "") =
        FlightRecordSummary.parse(Json.parseToJsonElement("""{"id":"$id","recordType":"$mode","status":"$status"$extra}""").jsonObject)
    private fun remote(vararg records: FlightRecordSummary) = RecordSummaryState(account, records.toList(), totalCount = records.size)

    @Test fun historicalSummariesDisplayAvailableFactsWithoutBecomingDeletableArchives() {
        val record = summary("historical", extra = """, "flightNumber":"AS321","departureAirportCode":"PEK","arrivalAirportCode":"LHR","durationSeconds":125.0,"flightDistanceKm":42.5,"seatNumber":"03F","focusIconType":3,"focusScenario":"阅读","flightStartTime":"2026-03-29T12:00:00Z"""")
        val page = RecordPageProjection.build(read(emptyList()), query(), wall, zone, remote(record))
        val item = page.items.single()
        assertEquals("AS321", item.flightNumber); assertEquals("PEK", item.fromCode); assertEquals("LHR", item.toCode)
        assertEquals(2, item.focusMinutes); assertEquals(42.5, item.distanceKm!!, 0.0)
        assertEquals("03F", item.seat); assertEquals("阅读", item.sceneTitle); assertEquals(record, item.cloudSummary)
        assertNull(page.deletionTarget(item)); assertEquals(42.5, page.completedDistanceKm!!, 0.0)
    }

    @Test fun unknownSummaryFactsDoNotBecomeCancelledZeroLengthFlightsOrEpochDates() {
        val page = RecordPageProjection.build(read(emptyList()), query(), wall, zone, remote(summary("unknown", status = "future")))
        val item = page.items.single()
        assertEquals(FlightStatus.UNKNOWN, item.status); assertNull(item.recordedAtWallMs)
        assertEquals("—", item.focusText); assertEquals("—", item.distanceText)
        assertNull(item.dateText); assertNull(item.distanceKm); assertNull(page.completedDistanceKm)
        assertEquals(0, RecordPageProjection.build(read(emptyList()), query(RecordStatusFilter.CANCELLED), wall, zone,
            remote(summary("unknown", status = "future"))).matchedCount)
        assertEquals(0, RecordPageProjection.build(read(emptyList()), query(period = RecordPeriod.DAY), wall, zone,
            remote(summary("unknown"))).matchedCount)
    }

    @Test fun matchingFullArchivesReplaceSummaryCardsButEqualIdsAcrossModesStayDistinct() {
        val local = row("local-id").let { it.copy(record = it.record.copy(seat = null)) }
        val summaries = remote(summary("remote-id", extra = """, "flightNumber":"AS321", "seatNumber":"03F""""), summary("remote-id", "up")).copy(
            aliases = mapOf(local.key to (FlightRecordMode.DOWN to "remote-id")))
        val page = RecordPageProjection.build(read(listOf(local)), query(), wall, zone, summaries)
        assertEquals(2, page.items.size); assertEquals(1, page.items.count { it.cloudSummary == null })
        assertEquals(FlightRecordKind.COUNT_UP, page.items.single { it.cloudSummary != null }.kind)
        assertNotNull(page.deletionTarget(page.items.single { it.cloudSummary == null }))
        val complete = page.items.single { it.cloudSummary == null }
        assertEquals("AS321", complete.flightNumber); assertEquals("03F", complete.seat)
        assertEquals(5, complete.focusMinutes); assertEquals(99.25, complete.distanceKm!!, 0.0)
    }

    @Test fun unrelatedSummaryIdsNeverBorrowAnotherCardsLocalActions() {
        val local = row("local-id")
        val summaries = remote(summary("remote-id"), summary("local-id")).copy(
            aliases = mapOf(local.key to (FlightRecordMode.DOWN to "remote-id")))
        val page = RecordPageProjection.build(read(listOf(local)), query(), wall, zone, summaries)
        assertEquals(2, page.items.size); assertEquals(2, page.items.map { it.displayKey }.toSet().size)
        assertNull(page.deletionTarget(page.items.single { it.cloudSummary != null }))
    }

    @Test fun summariesUseTheNormalPagerAndNeverSurviveAnAccountGenerationChange() {
        val records = (1..12).map { summary("old-$it", status = if (it % 2 == 0) "cancelled" else "arrived",
            extra = """, "flightStartTime":"2026-03-29T12:00:00Z"""") }
        val summaries = RecordSummaryState(account, records)
        val page = RecordPageProjection.build(read(emptyList()), query(page = 2), wall, zone, summaries)
        assertEquals(12, page.matchedCount); assertEquals(2, page.pageCount); assertEquals(2, page.items.size)
        assertEquals(6, RecordPageProjection.build(read(emptyList()), query(RecordStatusFilter.ARRIVED), wall, zone, summaries).matchedCount)
        TokenStore.set("second-account")
        assertTrue(RecordPageProjection.build(read(emptyList()), query(), wall, zone, summaries).items.isEmpty())
    }

    @Test fun partialOrUnreadableHistoryNeverShowsTheVisibleSubsetAsACompleteDistanceTotal() {
        val old = summary("known", extra = """, "flightDistanceKm":40.0""")
        for (summaries in listOf(remote(old).copy(complete = false, totalCount = 20), remote(old).copy(failed = true))) {
            val page = RecordPageProjection.build(read(listOf(row("local"))), query(), wall, zone, summaries)
            assertTrue(page.ready); assertEquals(2, page.items.size); assertNull(page.completedDistanceKm)
        }
    }

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
        first.items.filter { it.id == "same-id" }.forEach { item ->
            val target = requireNotNull(first.deletionTarget(item))
            assertEquals(account, target.account)
            assertEquals(item.key, target.record.key)
            assertSame(rows.first { it.key == item.key }, target.record)
        }
        val second = RecordPageProjection.build(read(rows),query(page=2),wall,zone)
        assertEquals(listOf("row-9"),second.items.map { it.id })
        val shrunk = RecordPageProjection.build(read(rows.take(2)),query(page=2),wall,zone)
        assertEquals(1,shrunk.page)
        assertEquals(1,shrunk.pageCount)
        assertEquals(2,shrunk.items.size)
        assertNull(second.deletionTarget(first.items.first()))
        assertNull(first.deletionTarget(first.items.first().copy(sceneRevision = 99)))
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
        for (status in listOf("FUTURE_STATUS", "UNKNOWN")) assertThrows(IllegalArgumentException::class.java) {
            RecordPageProjection.build(read(listOf(row("corrupt",status=status))),query(),wall,zone)
        }
    }
}
