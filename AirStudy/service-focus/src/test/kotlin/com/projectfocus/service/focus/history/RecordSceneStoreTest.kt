package com.projectfocus.service.focus.history

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.projectfocus.core.database.AppDatabase
import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.PlannedFlightMetadataCodec
import com.projectfocus.core.model.flight.PlannedScene
import com.projectfocus.core.model.flight.freeflight.*
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.service.focus.freeflight.RoomFreeFlightArchiveStore
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RecordSceneStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val wall = Instant.parse("2026-11-03T06:00:00Z").toEpochMilli()
    private val clock = Clock.fixed(Instant.ofEpochMilli(wall + 120_000), ZoneOffset.UTC)
    private val databaseName = "record-scene-store-test.db"
    private lateinit var db: AppDatabase
    private lateinit var store: RecordSceneStore
    private val reading = PlannedScene("reading-option", "", "reading", 0xFFFDAA0F.toInt(), false, displayName = "阅读")
    private val work = PlannedScene("work-option", "", "work", 0xFF2B71C2.toInt(), false, displayName = "工作")
    private val custom = PlannedScene("custom-option", "课题研究", "study", 0xFF3CC612.toInt(), true,
        cloudId = 42, clientUuid = "scene-client-id", displayName = "课题研究")
    private val originalSceneJson get() = requireNotNull(PlannedFlightMetadataCodec.encodeScene(reading))
        .dropLast(1) + ",\"futureField\":{\"retained\":true}}"

    @Before fun open() = runBlocking(Dispatchers.IO) {
        context.deleteDatabase(databaseName)
        reopen()
        TokenStore.setCancellable("record-scene-account-one")
        Unit
    }

    private fun reopen() {
        db = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
        store = RecordSceneStore(db, clock)
    }

    @After fun close() = runBlocking(Dispatchers.IO) {
        TokenStore.clear()
        db.close()
        context.deleteDatabase(databaseName)
        Unit
    }

    private fun account() = HistoryAccount(TokenStore.storageScope(), TokenStore.snapshot().generation)
    private fun target(kind: FlightRecordKind, id: String = "same-id", revision: Long = 0) =
        RecordSceneEditTarget(account(), FlightRecordKey(kind, id), wall, revision)

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

    @Test fun equalIdentifiersKeepSeparateLabelsAfterReopenAndOriginalFlightDataStaysUntouched() = runBlocking(Dispatchers.IO) {
        val original = legacy()
        db.flightHistoryDao().insert(original)
        independent()
        val owner = TokenStore.storageScope()
        val archiveBefore = db.freeFlightArchiveDao().get("same-id", owner)
        val positionsBefore = db.freeFlightArchiveDao().positions("same-id", owner)
        val totalsBefore = loaded().let { HistoryStatistics.calculate(it.records, it.airportCounts, it.airportCodes) }
        assertEquals(RecordSceneSaveResult.Saved(1), store.save(target(FlightRecordKind.COUNTDOWN), work))
        assertEquals(RecordSceneSaveResult.Saved(1), store.save(target(FlightRecordKind.COUNT_UP), custom))
        db.close()
        reopen()
        val rows = loaded().records
        assertEquals(2, rows.size)
        assertEquals(RecordedScene(work, 1), rows.single { it.key.kind == FlightRecordKind.COUNTDOWN }.editedScene)
        assertEquals(RecordedScene(custom, 1), rows.single { it.key.kind == FlightRecordKind.COUNT_UP }.editedScene)
        assertEquals(original, db.flightHistoryDao().findById("same-id"))
        assertEquals(archiveBefore, db.freeFlightArchiveDao().get("same-id", owner))
        assertEquals(positionsBefore, db.freeFlightArchiveDao().positions("same-id", owner))
        assertEquals(2, positionsBefore.size)
        val after = loaded().let { HistoryStatistics.calculate(it.records, it.airportCounts, it.airportCodes) }
        assertEquals(totalsBefore, after)
    }

    @Test fun staleSelectionsCannotOverwriteANewerChoiceOrARecordWithADifferentStart() = runBlocking(Dispatchers.IO) {
        db.flightHistoryDao().insert(legacy())
        val old = target(FlightRecordKind.COUNTDOWN)
        assertEquals(RecordSceneSaveResult.Saved(1), store.save(old, work))
        assertEquals(RecordSceneSaveResult.Changed, store.save(old, custom))
        assertEquals(RecordSceneSaveResult.Changed, store.save(old.copy(startedAtWallMs = wall + 1, expectedRevision = 1), custom))
        assertEquals(work, loaded().records.single().editedScene?.snapshot)
        val current = old.copy(expectedRevision = 1)
        assertEquals(RecordSceneSaveResult.Saved(1), store.save(current, work))
        assertEquals(RecordSceneSaveResult.Saved(2), store.save(current, custom))
        assertEquals(RecordedScene(custom, 2), loaded().records.single().editedScene)
    }

    @Test fun activeAndCancelledFlightsDoNotAcceptSceneChanges() = runBlocking(Dispatchers.IO) {
        listOf("ONGOING", "CANCELLED").forEach { status ->
            val id = "countdown-$status"
            db.flightHistoryDao().insert(legacy(id = id, status = status))
            assertEquals(RecordSceneSaveResult.NotEditable, store.save(target(FlightRecordKind.COUNTDOWN, id), work))
        }
        listOf(FreeFlightStatus.CANCELLED, FreeFlightStatus.ONGOING).forEach { status ->
            val id = "independent-$status"
            independent(id, status)
            assertEquals(RecordSceneSaveResult.NotEditable, store.save(target(FlightRecordKind.COUNT_UP, id), work))
        }
        assertTrue(loaded().records.all { it.editedScene == null })
    }

    @Test fun accountChangesAndUnassignedLegacyRowsCannotBeReattributedByAnEdit() = runBlocking(Dispatchers.IO) {
        val owner = TokenStore.storageScope()
        db.flightHistoryDao().insert(legacy())
        db.flightHistoryDao().insert(legacy(id = "unassigned", owner = null))
        val old = target(FlightRecordKind.COUNTDOWN)
        assertEquals(RecordSceneSaveResult.NotEditable, store.save(target(FlightRecordKind.COUNTDOWN, "unassigned"), work))
        TokenStore.setCancellable("record-scene-account-two")
        assertEquals(RecordSceneSaveResult.Obsolete, store.save(old, work))
        assertEquals(RecordSceneSaveResult.NotEditable, store.save(target(FlightRecordKind.COUNTDOWN), work))
        assertTrue(loaded().records.isEmpty())
        TokenStore.clear()
        assertEquals(RecordSceneSaveResult.Saved(1), store.save(target(FlightRecordKind.COUNTDOWN, "unassigned"), custom))
        assertEquals(listOf("unassigned"), loaded().records.map { it.key.id })
        assertNull(db.flightHistoryDao().findById("unassigned")?.ownerScope)
        assertEquals(owner, db.flightHistoryDao().findById("same-id")?.ownerScope)
        TokenStore.setCancellable("record-scene-account-one")
        assertEquals(listOf("same-id"), loaded().records.map { it.key.id })
        assertNull(loaded().records.single().editedScene)
    }

    @Test fun rejectedDatabaseWriteKeepsTheOldChoiceAndCanBeRetried() = runBlocking(Dispatchers.IO) {
        db.flightHistoryDao().insert(legacy())
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_scene BEFORE INSERT ON flight_record_scene BEGIN SELECT RAISE(ABORT, 'fixture write rejected'); END")
        val selected = target(FlightRecordKind.COUNTDOWN)
        try {
            store.save(selected, work)
            fail("A rejected database write must not report success")
        } catch (error: Exception) {
            assertTrue(error.toString().contains("fixture write rejected"))
        }
        assertNull(loaded().records.single().editedScene)
        assertEquals(originalSceneJson, db.flightHistoryDao().findById("same-id")?.sceneSnapshotJson)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_scene")
        assertEquals(RecordSceneSaveResult.Saved(1), store.save(selected, work))
        assertEquals(work, loaded().records.single().editedScene?.snapshot)
    }

    @Test fun deletingOneModeCascadesOnlyItsOwnAnnotationEvenWhenIdentifiersMatch() = runBlocking(Dispatchers.IO) {
        db.flightHistoryDao().insert(legacy())
        independent()
        store.save(target(FlightRecordKind.COUNTDOWN), work)
        store.save(target(FlightRecordKind.COUNT_UP), custom)
        db.flightHistoryDao().deleteById("same-id")
        assertNull(db.flightRecordSceneDao().find(FlightRecordKind.COUNTDOWN.name, "same-id"))
        assertNotNull(db.flightRecordSceneDao().find(FlightRecordKind.COUNT_UP.name, "same-id"))
        assertEquals(RecordedScene(custom, 1), loaded().records.single().editedScene)
        db.openHelper.writableDatabase.execSQL("DELETE FROM free_flight_archive WHERE sessionId = ?", arrayOf("same-id"))
        assertNull(db.flightRecordSceneDao().find(FlightRecordKind.COUNT_UP.name, "same-id"))
        assertTrue(loaded().records.isEmpty())
        db.query("PRAGMA foreign_key_check", emptyArray()).use { assertFalse(it.moveToFirst()) }
    }

    @Test fun corruptAnnotationIsAReadFailureRatherThanAMissingOrSuccessfulScene() = runBlocking(Dispatchers.IO) {
        db.flightHistoryDao().insert(legacy())
        store.save(target(FlightRecordKind.COUNTDOWN), work)
        db.openHelper.writableDatabase.execSQL("UPDATE flight_record_scene SET sceneJson = ?", arrayOf("broken-json"))
        val reader = FlightHistoryReader(db.historyReadDao())
        val failure = withTimeout(10_000) { reader.records.first { it is HistoryReadState.Failure } }
        assertTrue(failure is HistoryReadState.Failure)
        db.openHelper.writableDatabase.execSQL("UPDATE flight_record_scene SET sceneJson = ?", arrayOf(PlannedFlightMetadataCodec.encodeScene(work)))
        reader.retry()
        assertEquals(work, loaded(reader).records.single().editedScene?.snapshot)
    }
}
