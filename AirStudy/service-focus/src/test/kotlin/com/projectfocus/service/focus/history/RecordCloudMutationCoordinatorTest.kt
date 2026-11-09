package com.projectfocus.service.focus.history

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.projectfocus.core.database.AppDatabase
import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.core.datastore.SettingsRepository
import com.projectfocus.core.model.flight.*
import com.projectfocus.core.model.flight.freeflight.*
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.FlightRecordScenePatch
import com.projectfocus.core.network.record.*
import com.projectfocus.service.focus.freeflight.RoomFreeFlightArchiveStore
import com.projectfocus.service.focus.session.*
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import mockwebserver3.*
import okhttp3.OkHttpClient
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RecordCloudMutationCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName: String get() = File(temporary.root, "history.db").absolutePath
    private val wall = 1_000_000L
    private val clock = Clock.fixed(Instant.ofEpochMilli(4_000_000), ZoneOffset.UTC)
    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var preferences: CoroutineScope
    private lateinit var settings: SettingsRepository
    private lateinit var coordinator: RecordCloudMutationCoordinator
    private lateinit var localDeletion: RecordDeletionStore
    private lateinit var journal: RecordMutationJournal
    private val reading = PlannedScene("reading-option", "", "reading", 0, false, displayName = "阅读")
    private val work = PlannedScene("work-option", "", "work", 0, false, displayName = "工作")
    private var wireLabelSuffix = ""
    private val projection = RecordSceneWireProjection { scene -> FlightRecordScenePatch(if (scene.iconKey == "reading") 3 else 2,
        (scene.displayName?.takeIf(String::isNotBlank) ?: scene.name) + wireLabelSuffix) }
    private val notifier = object : TodayStatsNotifier() {
        var notifications = 0
        override fun notifyChanged() { notifications++; super.notifyChanged() }
    }
    @Before fun open() = runBlocking(Dispatchers.IO) {
        context.deleteDatabase(databaseName); TokenStore.setCancellable("cloud-record-owner-a")
        preferences = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = preferences,
            produceFile = { File(temporary.newFolder(), "cloud-record.preferences_pb") }))
        server = MockWebServer().also { it.start() }
        http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
        reopen()
    }
    private fun reopen() {
        db = Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()
        val sessions = SessionRepository(SessionController(object : MonotonicSource {
            override fun elapsedRealtimeMs() = 0L
        }, { wall }, { "live-countdown" }), settings, db.flightHistoryDao(), db.airportDao(), NoOpWidgetPush, notifier)
        localDeletion = RecordDeletionStore(db, sessions, notifier)
        journal = RecordMutationJournal(db)
        coordinator = RecordCloudMutationCoordinator(db, HttpRecordMutationGateway(http, server.url("/").toString()),
            HttpRecordArchiveGateway(http, server.url("/").toString()), RecordCloudSnapshots(db, projection), journal,
            RecordSceneStore(db, clock), localDeletion, projection, clock)
    }
    @After fun close() = runBlocking(Dispatchers.IO) {
        TokenStore.clear(); preferences.coroutineContext[Job]?.cancelAndJoin(); db.close(); context.deleteDatabase(databaseName)
        server.close(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
    }
    private fun account() = HistoryAccount(TokenStore.storageScope(), TokenStore.snapshot().generation)
    private fun down(id: String = "shared-id", status: String = "COMPLETED") = FlightHistoryEntity(
        id, null, "SOLO", null, "PEK", "Airport", "Beijing", "CN", 40.0, 116.0,
        "LHR", "Airport", "London", "GB", 51.4, -0.4, wall, wall + 300_000, 10, 5, 99.25, status,
        null, "01A", false, false, false, null, false, wall, wall,
        ownerScope = TokenStore.storageScope(), sceneSnapshotJson = PlannedFlightMetadataCodec.encodeScene(reading))
    private suspend fun up(): FreeFlightArchive {
        val engine = FreeFlightEngine(); val now = FreeFlightClockReading(0, 0, wall, "local-clock")
        val started = engine.start(FreeFlightStartRequest("shared-id", TokenStore.storageScope(),
            PlannedAirport(1, "PEK", "Airport", "Beijing", "CN", 40.0, 116.0), 90.0, "01A", FreeFlightScene(3, "阅读", "reading")), now)
        val at = now.copy(elapsedRealtimeMs = 90_001, uptimeMillis = 90_001, wallMs = wall + 90_001)
        val selection = engine.prepareLanding(started, at)
        val chosen = engine.selectLanding(selection, PlannedAirport(2, "LHR", "Airport", "London", "GB", 51.4, -0.4), selection.landingSelectionGeneration)
        val finished = engine.completeLandingWithoutMap(engine.beginLandingWithoutMap(chosen, chosen.landingSelectionGeneration, at), at)
            .copy(revision = 1, remoteRecordId = "remote-count-up")
        val store = RoomFreeFlightArchiveStore(db.freeFlightArchiveDao())
        assertTrue(store.commit(null, started, null)); assertTrue(store.commit(0, finished, null))
        return finished
    }
    private suspend fun deletion(kind: FlightRecordKind = FlightRecordKind.COUNTDOWN): RecordDeletionTarget {
        val state = withTimeout(5000) { FlightHistoryReader(db.historyReadDao()).records.first { it is HistoryReadState.Loaded } } as HistoryReadState.Loaded
        return RecordDeletionTarget(state.account, state.records.single { it.key == FlightRecordKey(kind, "shared-id") })
    }
    private fun sceneTarget(kind: FlightRecordKind = FlightRecordKind.COUNTDOWN) = RecordSceneEditTarget(account(), FlightRecordKey(kind, "shared-id"), wall, 0)
    private fun ack(revision: Int = 1) = MockResponse.Builder().code(200).body("""{"success":true,"data":{"revision":$revision}}""").build()
    private suspend fun expectFailure(block: suspend () -> Unit): RecordCloudFailure {
        try { block() } catch (failure: RecordCloudFailure) { return failure }
        throw AssertionError("Unconfirmed remote change was reported as saved")
    }

    @Test fun localDeletionWaitsForRemoteConfirmationAndKeepsTheOtherMode() = runBlocking(Dispatchers.IO) {
        db.flightHistoryDao().insert(down()); val independent = up(); val target = deletion()
        val received = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { received.complete(Unit); release.await(5, TimeUnit.SECONDS); return ack(2) }
        }
        val pending = async { coordinator.delete(target) }
        try {
            withTimeout(5000) { received.await() }
            assertNotNull(db.flightHistoryDao().findById("shared-id")); assertFalse(journal.pending(account().scope).single().acknowledged)
            release.countDown()
            assertEquals(RecordDeletionResult.DELETED, pending.await()); assertNull(db.flightHistoryDao().findById("shared-id"))
            assertEquals(independent.sessionId, db.freeFlightArchiveDao().get("shared-id", account().scope)!!.sessionId)
            assertTrue(journal.pending(account().scope).isEmpty()); assertEquals(1, notifier.notifications)
            assertEquals(RecordDeletionResult.MISSING, coordinator.delete(target)); assertEquals(1, server.requestCount)
        } finally { release.countDown(); pending.cancelAndJoin() }
    }

    @Test fun failedRemoteDeletionRetainsItsConfirmationForRetryAfterDatabaseReopen() = runBlocking(Dispatchers.IO) {
        db.flightHistoryDao().insert(down()); val target = deletion()
        server.enqueue(MockResponse.Builder().code(503).body("""{"success":false}""").build())
        assertTrue(expectFailure { coordinator.delete(target) }.reason is RecordMutationOutcome.Rejected)
        val operation = journal.pending(account().scope).single().operationId
        assertNotNull(db.flightHistoryDao().findById("shared-id")); assertEquals(0, notifier.notifications)
        val first = server.takeRequest(5, TimeUnit.SECONDS)!!
        db.close(); reopen(); server.enqueue(ack(2))
        assertEquals(RecordMutationRecovery(1, 0), coordinator.recoverPending())
        val second = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals(operation, first.headers["Idempotency-Key"]); assertEquals(operation, second.headers["Idempotency-Key"])
        assertEquals(first.url.encodedPath, second.url.encodedPath); assertNull(db.flightHistoryDao().findById("shared-id"))
    }

    @Test fun sceneUploadAndPatchUseRemoteCountUpIdentityWithoutRewritingItsArchive() = runBlocking(Dispatchers.IO) {
        val archive = up(); val original = db.freeFlightArchiveDao().get("shared-id", account().scope)!!
        server.enqueue(ack(7)); server.enqueue(ack(8))
        assertTrue(coordinator.saveScene(sceneTarget(FlightRecordKind.COUNT_UP), work) is RecordSceneSaveResult.Saved)
        val upload = server.takeRequest(5, TimeUnit.SECONDS)!!; val patch = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("PUT", upload.method); assertEquals("/v2/flight-records/up/remote-count-up", upload.url.encodedPath)
        assertEquals("PATCH", patch.method); assertEquals("/v2/flight-records/up/remote-count-up/focus-scene", patch.url.encodedPath)
        assertEquals("\"7\"", patch.headers["If-Match"])
        assertEquals(original, db.freeFlightArchiveDao().get(archive.sessionId, account().scope))
        assertEquals(work, PlannedFlightMetadataCodec.decodeSceneOrNull(db.flightRecordSceneDao().find("COUNT_UP", "shared-id")!!.sceneJson))
    }

    @Test fun aFailedScenePatchRetriesTheSameOperationWithoutUploadingTheArchiveAgain() = runBlocking(Dispatchers.IO) {
        db.flightHistoryDao().insert(down())
        server.enqueue(ack(1)); server.enqueue(MockResponse.Builder().code(503).body("""{"success":false}""").build())
        expectFailure { coordinator.saveScene(sceneTarget(), work) }
        assertNull(db.flightRecordSceneDao().find("COUNTDOWN", "shared-id"))
        val upload = server.takeRequest(5, TimeUnit.SECONDS)!!; val first = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("PUT", upload.method)
        db.close(); reopen(); server.enqueue(ack(2))
        assertEquals(RecordMutationRecovery(1, 0), coordinator.recoverPending())
        val retry = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("PATCH", retry.method); assertEquals(first.headers["Idempotency-Key"], retry.headers["Idempotency-Key"])
        assertEquals("\"1\"", retry.headers["If-Match"]); assertEquals(3, server.requestCount)
    }

    @Test fun acknowledgedDeletionAndJournalRemovalCommitTogetherAndRecoverWithoutAnotherRequest() = runBlocking(Dispatchers.IO) {
        db.flightHistoryDao().insert(down()); val target = deletion(); server.enqueue(ack(2))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER block_cloud_receipt BEFORE DELETE ON app_setting WHEN OLD.key LIKE 'record.cloud.pending.%' AND instr(OLD.value, '\"acknowledged\":true') > 0 BEGIN SELECT RAISE(ABORT, 'receipt unavailable'); END")
        try { coordinator.delete(target); fail("The failed local receipt write must propagate") } catch (_: android.database.sqlite.SQLiteException) { }
        assertNotNull(db.flightHistoryDao().findById("shared-id")); assertTrue(journal.pending(account().scope).single().acknowledged)
        assertEquals(0, notifier.notifications)
        db.close(); reopen(); db.openHelper.writableDatabase.execSQL("DROP TRIGGER block_cloud_receipt")
        assertEquals(RecordMutationRecovery(1, 0), coordinator.recoverPending())
        assertEquals(1, server.requestCount); assertEquals(1, notifier.notifications); assertNull(db.flightHistoryDao().findById("shared-id"))
    }

    @Test fun acknowledgedSceneAndReceiptCommitTogetherAndRecoverWithoutAnotherRequest() = runBlocking(Dispatchers.IO) {
        db.flightHistoryDao().insert(down()); server.enqueue(ack(1)); server.enqueue(ack(2))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER block_cloud_receipt BEFORE DELETE ON app_setting WHEN OLD.key LIKE 'record.cloud.pending.%' AND instr(OLD.value, '\"acknowledged\":true') > 0 BEGIN SELECT RAISE(ABORT, 'receipt unavailable'); END")
        try { coordinator.saveScene(sceneTarget(), work); fail("The failed receipt write must propagate") } catch (_: android.database.sqlite.SQLiteException) { }
        assertNull(db.flightRecordSceneDao().find("COUNTDOWN", "shared-id")); assertTrue(journal.pending(account().scope).single().acknowledged)
        db.close(); reopen(); db.openHelper.writableDatabase.execSQL("DROP TRIGGER block_cloud_receipt")
        assertEquals(RecordMutationRecovery(1, 0), coordinator.recoverPending()); assertEquals(2, server.requestCount)
        assertEquals(work, PlannedFlightMetadataCodec.decodeSceneOrNull(db.flightRecordSceneDao().find("COUNTDOWN", "shared-id")!!.sceneJson))
    }

    @Test fun changingAccountCancelsTheRequestAndDoesNotRecoverAnotherOwnersJournal() = runBlocking(Dispatchers.IO) {
        db.flightHistoryDao().insert(down()); val target = deletion(); val owner = target.account.scope
        val received = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { received.complete(Unit); release.await(5, TimeUnit.SECONDS); return ack(2) }
        }
        val pending = async { coordinator.delete(target) }
        try {
            withTimeout(5000) { received.await() }; withTimeout(1000) { TokenStore.setCancellable("cloud-record-owner-b") }
            assertEquals(RecordDeletionResult.OBSOLETE, withTimeout(2000) { pending.await() })
            assertEquals(RecordMutationRecovery(0, 0), coordinator.recoverPending())
            assertNotNull(db.flightHistoryDao().findById("shared-id")); assertEquals(1, journal.pending(owner).size)
            release.countDown(); TokenStore.setCancellable("cloud-record-owner-a")
            assertEquals(RecordMutationRecovery(1, 0), coordinator.recoverPending()); assertEquals(2, server.requestCount)
        } finally { release.countDown(); pending.cancelAndJoin() }
    }

    @Test fun aChangedRecordIsKeptWhenRemoteDeletionFinishes() = runBlocking(Dispatchers.IO) {
        val original = down(); db.flightHistoryDao().insert(original); val target = deletion()
        val received = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { received.complete(Unit); release.await(5, TimeUnit.SECONDS); return ack(2) }
        }
        val pending = async { coordinator.delete(target) }
        try {
            withTimeout(5000) { received.await() }
            db.openHelper.writableDatabase.execSQL("UPDATE flight_history SET actualFocusMinutes = 4 WHERE id = 'shared-id'")
            release.countDown(); assertEquals(RecordDeletionResult.CHANGED, pending.await())
            assertEquals(4, db.flightHistoryDao().findById("shared-id")!!.actualFocusMinutes)
            assertTrue(journal.pending(account().scope).single().acknowledged)
            assertEquals(RecordMutationRecovery(0, 1), coordinator.recoverPending()); assertEquals(1, server.requestCount)
        } finally { release.countDown(); pending.cancelAndJoin() }
    }

    @Test fun aLostLocalAcknowledgementRetriesTheOriginalRemoteOperationInsteadOfCreatingAnotherOne() = runBlocking(Dispatchers.IO) {
        db.flightHistoryDao().insert(down()); val target = deletion(); server.enqueue(ack(2))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER block_ack_checkpoint BEFORE DELETE ON app_setting WHEN OLD.key LIKE 'record.cloud.pending.%' BEGIN SELECT RAISE(ABORT, 'checkpoint unavailable'); END")
        try { coordinator.delete(target); fail("The failed acknowledgement write must propagate") } catch (_: android.database.sqlite.SQLiteException) { }
        assertFalse(journal.pending(account().scope).single().acknowledged)
        assertNotNull(db.flightHistoryDao().findById("shared-id")); assertEquals(0, notifier.notifications)
        val original = server.takeRequest(5, TimeUnit.SECONDS)!!
        db.close(); reopen(); db.openHelper.writableDatabase.execSQL("DROP TRIGGER block_ack_checkpoint"); server.enqueue(ack(2))
        assertEquals(RecordMutationRecovery(1, 0), coordinator.recoverPending())
        val retry = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals(original.headers["Idempotency-Key"], retry.headers["Idempotency-Key"])
        assertEquals(2, server.requestCount); assertNull(db.flightHistoryDao().findById("shared-id"))
    }

    @Test fun aNewExplicitSceneChoiceCanReplaceAnAcknowledgedSceneAfterALocalWriteFailure() = runBlocking(Dispatchers.IO) {
        db.flightHistoryDao().insert(down()); server.enqueue(ack(1)); server.enqueue(ack(2))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER block_scene BEFORE INSERT ON flight_record_scene BEGIN SELECT RAISE(ABORT, 'scene unavailable'); END")
        try { coordinator.saveScene(sceneTarget(), work); fail("The local write must fail") } catch (_: android.database.sqlite.SQLiteException) { }
        val previous = journal.pending(account().scope).single()
        assertTrue(previous.acknowledged); assertEquals(2L, previous.acceptedRevision)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER block_scene"); server.enqueue(ack(3))
        assertTrue(coordinator.saveScene(sceneTarget(), reading) is RecordSceneSaveResult.Saved)
        val upload = server.takeRequest(5, TimeUnit.SECONDS)!!
        val first = server.takeRequest(5, TimeUnit.SECONDS)!!
        val replacement = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("PUT", upload.method); assertEquals("PATCH", replacement.method)
        assertNotEquals(first.headers["Idempotency-Key"], replacement.headers["Idempotency-Key"])
        assertEquals("\"2\"", replacement.headers["If-Match"])
        assertEquals(reading, PlannedFlightMetadataCodec.decodeSceneOrNull(db.flightRecordSceneDao().find("COUNTDOWN", "shared-id")!!.sceneJson))
        assertTrue(journal.pending(account().scope).isEmpty())
    }

    @Test fun changingLocalizedWireLabelsDoesNotStrandAStoredSceneConfirmation() = runBlocking(Dispatchers.IO) {
        db.flightHistoryDao().insert(down()); server.enqueue(ack(1))
        server.enqueue(MockResponse.Builder().code(503).body("""{"success":false}""").build())
        expectFailure { coordinator.saveScene(sceneTarget(), work) }
        wireLabelSuffix = " translated"
        db.close(); reopen(); server.enqueue(ack(2))
        assertEquals(RecordMutationRecovery(1, 0), coordinator.recoverPending())
        server.takeRequest(5, TimeUnit.SECONDS)
        val first = server.takeRequest(5, TimeUnit.SECONDS)!!
        val retry = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals(first.body!!.utf8(), retry.body!!.utf8())
        assertEquals(first.headers["Idempotency-Key"], retry.headers["Idempotency-Key"])
    }
}
