package com.projectfocus.service.focus.history

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.room.Room
import com.projectfocus.core.database.AppDatabase
import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.core.datastore.SettingsRepository
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.PlannedFlightMetadataCodec
import com.projectfocus.core.model.flight.PlannedScene
import com.projectfocus.core.model.flight.freeflight.*
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.service.focus.freeflight.RoomFreeFlightArchiveStore
import com.projectfocus.service.focus.session.*
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RecordDeletionStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "record-deletion-test.db"
    private val wall = Instant.parse("2026-11-03T06:00:00Z").toEpochMilli()
    private val clock = Clock.fixed(Instant.ofEpochMilli(wall + 120_000), ZoneOffset.UTC)
    private lateinit var db: AppDatabase
    private lateinit var sessions: SessionRepository
    private lateinit var store: RecordDeletionStore
    private lateinit var preferences: CoroutineScope
    private lateinit var settings: SettingsRepository
    private var mono = 0L
    private val notifier = object : TodayStatsNotifier() {
        var count = 0
        override fun notifyChanged() { ++count; super.notifyChanged() }
    }
    private val reading = PlannedScene("reading-option", "", "reading", 0xFFFDAA0F.toInt(), false, displayName = "阅读")
    private val work = reading.copy(optionId = "work-option", iconKey = "work", displayName = "工作")
    private val originalSceneJson = requireNotNull(PlannedFlightMetadataCodec.encodeScene(reading))

    @Before fun open() = runBlocking(Dispatchers.IO) {
        context.deleteDatabase(databaseName)
        preferences = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = preferences,
            produceFile = { File(temporary.newFolder(), "record-deletion.preferences_pb") }))
        TokenStore.setCancellable("history-deletion-account")
        reopen()
        Unit
    }
    private fun reopen() {
        db = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
        sessions = SessionRepository(SessionController(object : MonotonicSource {
            override fun elapsedRealtimeMs() = mono
        }, { wall + mono }, { "live-countdown" }), settings, db.flightHistoryDao(), db.airportDao(), NoOpWidgetPush, notifier)
        store = RecordDeletionStore(db, sessions, notifier)
    }
    @After fun close() = runBlocking(Dispatchers.IO) {
        TokenStore.clear()
        preferences.coroutineContext[Job]?.cancelAndJoin()
        db.close()
        context.deleteDatabase(databaseName)
        Unit
    }
    private suspend fun target(kind: FlightRecordKind, id: String = "same-id"): RecordDeletionTarget {
        val state = loaded()
        return RecordDeletionTarget(state.account, state.records.single { it.key == FlightRecordKey(kind, id) })
    }
    private fun legacy(id: String = "same-id", owner: String? = TokenStore.storageScope(), status: String = "COMPLETED") = FlightHistoryEntity(
        id, null, "SOLO", null, "PEK", "Beijing Airport", "Beijing", "CN", 40.0, 116.0,
        "TSN", "Tianjin Airport", "Tianjin", "CN", 39.0, 117.0, wall, wall + 300_000,
        10, 5, 99.25, status, null, "01A", false, false, false, null, false, wall, wall,
        sceneSnapshotJson = originalSceneJson, ownerScope = owner,
    )

    private suspend fun independent(id: String = "same-id", status: FreeFlightStatus = FreeFlightStatus.COMPLETED): FreeFlightArchive {
        val engine = FreeFlightEngine()
        val now = FreeFlightClockReading(0, 0, wall, "record-scene-clock")
        val departure = PlannedAirport(1, "PEK", "Beijing Airport", "Beijing", "CN", 40.0, 116.0)
        val started = engine.start(FreeFlightStartRequest(id, TokenStore.storageScope(), departure, 90.0, "01A",
            FreeFlightScene(3, "阅读", "reading")), now).copy(sampleSequence = 1)
        val archiveStore = RoomFreeFlightArchiveStore(db.freeFlightArchiveDao())
        fun sample(value: FreeFlightArchive) = FreeFlightPositionSample(value.sessionId, value.sampleSequence,
            value.clock.activeElapsedMs, value.position, value.bearingDegrees, value.cumulativeDistanceKm, value.updatedAtWallMs)
        assertTrue(archiveStore.commit(null, started, sample(started)))
        if (status == FreeFlightStatus.ONGOING) return started
        val later = now.copy(elapsedRealtimeMs = 90_001, uptimeMillis = 90_001, wallMs = wall + 90_001)
        val finished = if (status == FreeFlightStatus.CANCELLED) engine.cancel(started, later) else {
            val choosing = engine.prepareLanding(started, later)
            val landing = PlannedAirport(2, "TSN", "Tianjin Airport", "Tianjin", "CN", 39.0, 117.0)
            val chosen = engine.selectLanding(choosing, landing, choosing.landingSelectionGeneration)
            engine.completeLandingWithoutMap(engine.beginLandingWithoutMap(chosen, chosen.landingSelectionGeneration, later), later)
        }
        val committed = finished.copy(revision = 1, sampleSequence = 2)
        assertTrue(archiveStore.commit(0, committed, sample(committed)))
        return committed
    }

    private suspend fun loaded(reader: FlightHistoryReader = FlightHistoryReader(db.historyReadDao())) = withTimeout(10_000) {
        reader.records.first { it is HistoryReadState.Loaded } as HistoryReadState.Loaded
    }


    @Test fun identicalIdsStayIndependentAndOnlyTheirOwnChildrenAndTotalsAreRemoved() = runBlocking(Dispatchers.IO) {
        db.flightHistoryDao().insert(legacy())
        independent()
        val sceneStore = RecordSceneStore(db, clock)
        for (kind in FlightRecordKind.entries) {
            val captured = target(kind)
            assertTrue(sceneStore.save(RecordSceneEditTarget(captured.account, captured.record.key, wall, 0), work) is RecordSceneSaveResult.Saved)
        }
        val originalUp = db.freeFlightArchiveDao().get("same-id", TokenStore.storageScope())!!
        val originalPositions = db.freeFlightArchiveDao().positions("same-id", TokenStore.storageScope())
        assertEquals(2, originalPositions.size)
        val before = loaded().records.filter { it.completed }.sumOf { it.distanceKm }
        val down = target(FlightRecordKind.COUNTDOWN)
        assertEquals(RecordDeletionResult.DELETED, store.delete(down))
        assertNull(db.flightHistoryDao().findById("same-id"))
        assertNull(db.flightRecordSceneDao().find("COUNTDOWN", "same-id"))
        assertNotNull(db.flightRecordSceneDao().find("COUNT_UP", "same-id"))
        assertEquals(originalUp, db.freeFlightArchiveDao().get("same-id", TokenStore.storageScope()))
        assertEquals(originalPositions, db.freeFlightArchiveDao().positions("same-id", TokenStore.storageScope()))
        assertEquals(before - 99.25, loaded().records.filter { it.completed }.sumOf { it.distanceKm }, 0.00001)
        assertEquals(RecordDeletionResult.MISSING, store.delete(down))
        assertEquals(1, notifier.count)
        db.close(); reopen()
        val up = target(FlightRecordKind.COUNT_UP)
        assertEquals(RecordDeletionResult.DELETED, store.delete(up))
        assertNull(db.freeFlightArchiveDao().get("same-id", TokenStore.storageScope()))
        assertTrue(db.freeFlightArchiveDao().positions("same-id", TokenStore.storageScope()).isEmpty())
        assertNull(db.flightRecordSceneDao().find("COUNT_UP", "same-id"))
        assertTrue(loaded().records.isEmpty())
        assertEquals(2, notifier.count)
    }

    @Test fun changedRowsScenesAndOpaquePayloadFieldsInvalidateOldConfirmation() = runBlocking(Dispatchers.IO) {
        db.flightHistoryDao().insert(legacy())
        independent()
        val down = target(FlightRecordKind.COUNTDOWN)
        db.openHelper.writableDatabase.execSQL("UPDATE flight_history SET actualFocusMinutes = 4 WHERE id = 'same-id'")
        assertEquals(RecordDeletionResult.CHANGED, store.delete(down))
        val freshDown = target(FlightRecordKind.COUNTDOWN)
        val edit = RecordSceneEditTarget(freshDown.account, freshDown.record.key, wall, 0)
        assertTrue(RecordSceneStore(db, clock).save(edit, work) is RecordSceneSaveResult.Saved)
        assertEquals(RecordDeletionResult.CHANGED, store.delete(freshDown))
        val up = target(FlightRecordKind.COUNT_UP)
        val row = db.freeFlightArchiveDao().get("same-id", TokenStore.storageScope())!!
        val changed = row.payloadJson.dropLast(1) + ",\"futureField\":{\"retained\":true}}"
        db.openHelper.writableDatabase.execSQL("UPDATE free_flight_archive SET payloadJson = ? WHERE sessionId = ?", arrayOf(changed, "same-id"))
        assertEquals(RecordDeletionResult.CHANGED, store.delete(up))
        assertEquals(changed, db.freeFlightArchiveDao().get("same-id", TokenStore.storageScope())!!.payloadJson)
        val unsupported = up.copy(record = (up.record as RecordedFlight.CountUp).copy(storedRow = null))
        assertEquals(RecordDeletionResult.CHANGED, store.delete(unsupported))
        assertEquals(0, notifier.count)
    }

    @Test fun activeOrNotYetSettledFlightsCannotBeDeletedAndCannotBeResurrected() = runBlocking(Dispatchers.IO) {
        assertTrue(sessions.start(SessionRepository.NO_CATEGORY, 1000))
        val active = requireNotNull(store.captureCountdown("live-countdown"))
        assertEquals(RecordDeletionResult.PROTECTED, store.delete(active))
        mono = 1000
        assertTrue(sessions.tick())
        val arrived = requireNotNull(store.captureCountdown("live-countdown"))
        assertTrue(arrived.record.completed)
        assertEquals(RecordDeletionResult.PROTECTED, store.delete(arrived))
        assertNotNull(sessions.settle())
        val count = notifier.count
        assertEquals(RecordDeletionResult.DELETED, store.delete(arrived))
        assertNull(sessions.settle())
        assertNull(db.flightHistoryDao().findById("live-countdown"))
        assertEquals(count + 1, notifier.count)
    }

    @Test fun terminalIndependentArchiveIsProtectedUntilActualRuntimeReservationIsReleased() = runBlocking(Dispatchers.IO) {
        independent()
        val owner = sessions.openFreeFlightHost(TokenStore.storageScope())
        assertTrue(sessions.reserveFreeFlightRuntime("same-id", owner))
        val up = target(FlightRecordKind.COUNT_UP)
        assertEquals(RecordDeletionResult.PROTECTED, store.delete(up))
        assertNotNull(db.freeFlightArchiveDao().get("same-id", owner.ownerScope))
        sessions.releaseFreeFlightRuntime("same-id", owner)
        assertEquals(RecordDeletionResult.DELETED, store.delete(up))
    }

    @Test fun ongoingArchivesAreProtectedEvenWithoutAnInMemoryRuntime() = runBlocking(Dispatchers.IO) {
        db.flightHistoryDao().insert(legacy(status = "ONGOING"))
        independent(status = FreeFlightStatus.ONGOING)
        assertEquals(RecordDeletionResult.PROTECTED, store.delete(target(FlightRecordKind.COUNTDOWN)))
        assertEquals(RecordDeletionResult.PROTECTED, store.delete(target(FlightRecordKind.COUNT_UP)))
        assertEquals(2, loaded().records.size)
        assertEquals(0, notifier.count)
    }

    @Test fun accountChangesAndAnonymousLegacyOwnershipCannotCrossDelete() = runBlocking(Dispatchers.IO) {
        db.flightHistoryDao().insert(legacy())
        independent()
        val down = target(FlightRecordKind.COUNTDOWN)
        val up = target(FlightRecordKind.COUNT_UP)
        TokenStore.setCancellable("another-deletion-account")
        assertNull(store.captureCountdown("same-id"))
        assertEquals(RecordDeletionResult.OBSOLETE, store.delete(down))
        assertEquals(RecordDeletionResult.OBSOLETE, store.delete(up))
        db.flightHistoryDao().insert(legacy(id = "unowned", owner = null, status = "CANCELLED"))
        assertNull(store.captureCountdown("unowned"))
        TokenStore.clear()
        val local = requireNotNull(store.captureCountdown("unowned"))
        assertEquals(RecordDeletionResult.DELETED, store.delete(local))
        assertNotNull(db.flightHistoryDao().findById("same-id"))
        assertNotNull(db.freeFlightArchiveDao().get("same-id", up.account.scope))
        assertEquals(1, notifier.count)
    }

    @Test fun failedDeleteRollsBackChildrenAndKeepsTheSameConfirmationRetryable() = runBlocking(Dispatchers.IO) {
        db.flightHistoryDao().insert(legacy())
        independent()
        for (kind in FlightRecordKind.entries) {
            val value = target(kind)
            RecordSceneStore(db, clock).save(RecordSceneEditTarget(value.account, value.record.key, wall, 0), work)
            val confirmed = target(kind)
            val table = if (kind == FlightRecordKind.COUNTDOWN) "flight_history" else "free_flight_archive"
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_record_delete BEFORE DELETE ON $table BEGIN SELECT RAISE(ABORT, 'delete temporarily unavailable'); END")
            val count = notifier.count
            var failed = false
            try { store.delete(confirmed) } catch (_: Exception) { failed = true }
            assertTrue(failed)
            assertEquals(count, notifier.count)
            assertNotNull(db.flightRecordSceneDao().find(kind.name, "same-id"))
            if (kind == FlightRecordKind.COUNT_UP) assertEquals(2, db.freeFlightArchiveDao().positions("same-id", confirmed.account.scope).size)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_record_delete")
            assertEquals(RecordDeletionResult.DELETED, store.delete(confirmed))
        }
        assertEquals(2, notifier.count)
        assertTrue(loaded().records.isEmpty())
    }
}
