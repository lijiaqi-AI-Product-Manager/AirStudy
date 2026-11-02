package com.projectfocus.service.focus.history

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.projectfocus.core.database.AppDatabase
import com.projectfocus.core.database.entity.AirportEntity
import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.freeflight.*
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.service.focus.freeflight.RoomFreeFlightArchiveStore
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class FlightHistoryReaderTest {
    private lateinit var db: AppDatabase
    private lateinit var reader: FlightHistoryReader
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wall = Instant.parse("2026-11-03T04:00:00Z").toEpochMilli()
    private val destination = PlannedAirport(43341, "FR-0001", "Emile Muller Hospital Heliport", "Mulhouse", "FR", 47.725328, 7.344995)

    @Before fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        reader = FlightHistoryReader(db.historyReadDao())
    }

    @After fun close() = runBlocking {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        TokenStore.clear()
        db.close()
    }

    private fun legacy(id: String, owner: String?, created: Long = wall) = FlightHistoryEntity(
        id, null, "SOLO", null, "PEK", "Beijing Airport", "Beijing", "CN", 40.0, 116.0,
        "TSN", "Tianjin Airport", "Tianjin", "CN", 39.0, 117.0, created, created + 300_000,
        10, 5, 99.25, "COMPLETED", null, "01A", false, false, false, null, false, created, created,
        ownerScope = owner,
    )

    private fun arrived(id: String, owner: String, elapsed: Long = 90_001, started: Long = wall): FreeFlightArchive {
        val engine = FreeFlightEngine()
        val now = FreeFlightClockReading(0, 0, started, "history-read-clock")
        val departure = PlannedAirport(1, "PEK", "Beijing Airport", "Beijing", "CN", 40.0, 116.0)
        val start = engine.start(FreeFlightStartRequest(id, owner, departure, 90.0, "01A", FreeFlightScene(2, "Reading", "book")), now)
        val at = now.copy(elapsedRealtimeMs = elapsed, uptimeMillis = elapsed, wallMs = started + elapsed)
        val selection = engine.prepareLanding(start, at)
        val picked = engine.selectLanding(selection, destination, selection.landingSelectionGeneration)
        return engine.completeLandingWithoutMap(engine.beginLandingWithoutMap(picked, picked.landingSelectionGeneration, at), at)
    }

    private suspend fun loaded() = withTimeout(5_000) {
        reader.records.first { it is HistoryReadState.Loaded } as HistoryReadState.Loaded
    }

    private suspend fun persist(archive: FreeFlightArchive): FreeFlightArchive {
        val started = FreeFlightEngine().start(checkNotNull(archive.originalStartRequest()),
            FreeFlightClockReading(0, 0, archive.startedAtWallMs, "history-read-clock"))
        val store = RoomFreeFlightArchiveStore(db.freeFlightArchiveDao())
        assertTrue(store.commit(null, started, null))
        val committed = archive.copy(revision = 1)
        assertTrue(store.commit(0, committed, null))
        return committed
    }

    @Test fun actualRoomReadsBothArchiveTypesWithoutAssigningUnknownOrOtherAccountRecords() = runBlocking(Dispatchers.IO) {
        TokenStore.setCancellable("history-account-one")
        val owner = TokenStore.storageScope()
        val other = TokenStore.storageScope(TokenStore.Snapshot("history-account-two", 0))
        db.flightHistoryDao().insert(legacy("same-id", owner))
        db.flightHistoryDao().insert(legacy("unassigned", null))
        db.flightHistoryDao().insert(legacy("other-owner", other))
        db.airportDao().upsertAll(listOf(
            AirportEntity(1,"TSN","ZBTJ","Tianjin","Tianjin","CN",39.0,117.0,0,"AS","medium_airport",1,false),
            AirportEntity(2,"TSN","TEST","Directory duplicate fixture","Tianjin","CN",39.0,117.0,0,"AS","small_airport",1,false),
        ))
        val actual = persist(arrived("same-id", owner))
        val loaded = loaded()
        assertEquals(2, loaded.records.size)
        assertEquals(setOf(FlightRecordKind.COUNTDOWN, FlightRecordKind.COUNT_UP), loaded.records.map { it.key.kind }.toSet())
        assertEquals(actual, (loaded.records.single { it is RecordedFlight.CountUp } as RecordedFlight.CountUp).archive)
        assertNull(db.airportDao().findByCode(destination.code))
        val statistics = HistoryStatistics.calculate(loaded.records, loaded.airportCounts, loaded.airportCodes)
        assertEquals(2, statistics.flights)
        assertEquals(7, statistics.focusMinutes)
        assertEquals(99.25 + actual.cumulativeDistanceKm, statistics.distanceKm, 1e-10)
        assertEquals(setOf("CN", "FR"), statistics.countries.map { it.country }.toSet())
        assertEquals(2, loaded.airportCounts["CN"])
        assertEquals(setOf("TSN"), loaded.airportCodes["CN"])
        assertEquals(2, statistics.countries.single { it.country == "CN" }.totalAirports)
        assertEquals(0, statistics.countries.single { it.country == "FR" }.totalAirports)
        TokenStore.setCancellable("history-account-two")
        assertFalse(loaded.account.isCurrent())
        assertEquals(listOf("other-owner"), loaded().records.map { it.key.id })
        TokenStore.clear()
        assertEquals(listOf("unassigned"), loaded().records.map { it.key.id })
        assertEquals(actual, RoomFreeFlightArchiveStore(db.freeFlightArchiveDao()).load("same-id", owner))
    }

    @Test fun activeIndependentArchiveRemainsBrowseableWithoutBecomingACompletedFlight() = runBlocking(Dispatchers.IO) {
        TokenStore.setCancellable("active-history-account")
        val owner = TokenStore.storageScope()
        val now = FreeFlightClockReading(0,0,wall,"active-record-clock")
        val departure = PlannedAirport(1,"PEK","Beijing Airport","Beijing","CN",40.0,116.0)
        val engine = FreeFlightEngine()
        val start = engine.start(FreeFlightStartRequest("active-record",owner,departure,90.0,"01A",
            FreeFlightScene(3,"Reading","reading")),now)
        val store = RoomFreeFlightArchiveStore(db.freeFlightArchiveDao())
        assertTrue(store.commit(null,start,null))
        val sampled = engine.sample(start,now.copy(elapsedRealtimeMs=61_000,uptimeMillis=61_000,wallMs=wall+61_000))
            .copy(revision=1)
        assertTrue(store.commit(0,sampled,null))
        val read = loaded()
        val actual = read.records.single() as RecordedFlight.CountUp
        assertEquals(sampled,actual.archive)
        assertFalse(actual.completed)
        assertNull(actual.destinationCode)
        val summary = HistoryStatistics.calculate(read.records,read.airportCounts,read.airportCodes)
        assertEquals(0,summary.flights)
        assertEquals(0,summary.focusMinutes)
        assertEquals(0.0,summary.distanceKm,0.0)
    }

    @Test fun corruptPayloadIsAReadFailureAndRetryRecoversTheSameLiveReader() = runBlocking(Dispatchers.IO) {
        TokenStore.setCancellable("history-corruption-account")
        val owner = TokenStore.storageScope()
        db.flightHistoryDao().insert(legacy("kept", owner))
        persist(arrived("corrupt", owner))
        db.openHelper.writableDatabase.execSQL("UPDATE free_flight_archive SET payloadJson = ? WHERE sessionId = ?", arrayOf("broken-json", "corrupt"))
        val observed = Channel<HistoryReadState>(Channel.UNLIMITED)
        val collector = scope.launch { reader.records.collect { observed.send(it) } }
        try {
            val failed = withTimeout(5_000) { var next: HistoryReadState; do { next = observed.receive() } while (next !is HistoryReadState.Failure); next }
            assertTrue(failed is HistoryReadState.Failure)
            db.openHelper.writableDatabase.execSQL("DELETE FROM free_flight_archive WHERE sessionId = ?", arrayOf("corrupt"))
            reader.retry()
            val recovered = withTimeout(5_000) { var next: HistoryReadState; do { next = observed.receive() } while (next !is HistoryReadState.Loaded); next as HistoryReadState.Loaded }
            assertEquals(listOf("kept"), recovered.records.map { it.key.id })
        } finally { collector.cancelAndJoin(); observed.close() }
    }

    @Test fun dailyTotalsUseCalendarMidnightAndIncludeTheCurrentCommittedResultExactlyOnce() {
        val zone = ZoneId.of("Europe/Berlin")
        val now = Instant.parse("2026-03-29T12:00:00Z").toEpochMilli()
        val midnight = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val current = CompletedFreeFlight.from(arrived("current", "scope", started = midnight - 10_000))
        val records = listOf(
            RecordedFlight.Countdown(legacy("included", "scope", midnight)),
            RecordedFlight.Countdown(legacy("yesterday", "scope", midnight - 1)),
            RecordedFlight.Countdown(legacy("future", "scope", now + 1)),
            RecordedFlight.Countdown(legacy("cancelled", "scope", midnight).copy(status = "CANCELLED")),
            RecordedFlight.CountUp(current.archive),
        )
        val totals = ArrivalHistoryTotals.calculate(records, current, now, zone)
        assertEquals(7, totals.focusMinutes)
        assertEquals(99.25 + current.distanceKm, totals.distanceKm, 1e-10)
        val alreadyToday = CompletedFreeFlight.from(arrived("today", "scope", started = midnight))
        val once = ArrivalHistoryTotals.calculate(listOf(RecordedFlight.CountUp(alreadyToday.archive)), alreadyToday, now, zone)
        assertEquals(alreadyToday.displayFocusMinutes, once.focusMinutes)
        assertEquals(alreadyToday.distanceKm, once.distanceKm, 0.0)
        val stale = arrived("today", "scope", elapsed = 60_001, started = midnight)
        val toppedUp = ArrivalHistoryTotals.calculate(listOf(RecordedFlight.CountUp(stale)), alreadyToday, now, zone)
        assertEquals(alreadyToday.displayFocusMinutes, toppedUp.focusMinutes)
        assertEquals(alreadyToday.distanceKm, toppedUp.distanceKm, 1e-10)
    }

    @Test fun restoredHistoryUsesItsDepartureDateAndShortRecordsKeepTheirOwnDuration() {
        val midnight = Instant.ofEpochMilli(wall).atZone(ZoneId.of("UTC")).toLocalDate()
            .atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val current = CompletedFreeFlight.from(arrived("short-arrival", "scope", elapsed = 5_000))
        val short = RecordedFlight.CountUp(current.archive)
        assertEquals(0, short.focusMinutes)
        assertEquals(1, current.displayFocusMinutes)
        assertEquals(0, HistoryStatistics.calculate(listOf(short), emptyMap(), emptyMap()).focusMinutes)
        val restoredToday = legacy("restored-today", "scope", wall).copy(createdAt = 0)
        val savedLate = legacy("saved-after-midnight", "scope", midnight - 1)
            .copy(createdAt = wall, actualFocusMinutes = 9, distanceKm = 333.0)
        val totals = ArrivalHistoryTotals.calculate(listOf(RecordedFlight.Countdown(restoredToday),
            RecordedFlight.Countdown(savedLate), short), current, wall + 100_000, ZoneId.of("UTC"))
        assertEquals(6, totals.focusMinutes)
        assertEquals(99.25 + current.distanceKm, totals.distanceKm, 1e-10)
    }

    @Test fun dailyHistoryFallbackRoundsItsStoredDistanceAndCancelledUnselectedFlightsInventNoDestination() {
        val current = CompletedFreeFlight.from(arrived("current", "scope"))
        val row = legacy("no-minutes", "scope").copy(actualFocusMinutes = 0, distanceKm = 18.0)
        val totals = ArrivalHistoryTotals.calculate(listOf(RecordedFlight.Countdown(row)), current, wall + 100_000, ZoneId.of("UTC"))
        assertEquals(3, totals.focusMinutes)
        assertEquals(18.0 + current.distanceKm, totals.distanceKm, 1e-10)
        val engine = FreeFlightEngine()
        val start = engine.start(current.archive.originalStartRequest()!!.copy(sessionId = "cancelled"), FreeFlightClockReading(0, 0, wall, "cancel-clock"))
        val cancelled = RecordedFlight.CountUp(engine.cancel(start, FreeFlightClockReading(1_000, 1_000, wall + 1_000, "cancel-clock")))
        assertNull(cancelled.destinationCode)
        assertFalse(cancelled.completed)
        val statistics = HistoryStatistics.calculate(listOf(cancelled), emptyMap(), emptyMap())
        assertEquals(0, statistics.flights); assertTrue(statistics.destinations.isEmpty())
    }
}
