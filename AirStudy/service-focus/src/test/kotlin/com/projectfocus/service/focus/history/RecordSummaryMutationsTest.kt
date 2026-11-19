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
import com.projectfocus.core.network.dto.*
import com.projectfocus.core.network.record.*
import com.projectfocus.service.focus.session.*
import com.projectfocus.service.focus.freeflight.RoomFreeFlightArchiveStore
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
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
class RecordSummaryMutationsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = Clock.fixed(Instant.ofEpochMilli(4_000_000), ZoneOffset.UTC)
    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var preferences: CoroutineScope
    private lateinit var settings: SettingsRepository
    private lateinit var journal: RecordSummaryMutationJournal
    private lateinit var bindings: RecordMutationJournal
    private lateinit var summaries: RecordSummaryStore
    private lateinit var coordinator: RecordCloudMutationCoordinator
    private lateinit var sessions: SessionRepository
    private lateinit var controller: SessionController
    private val work = PlannedScene("work", "工作", "work", 0, true)
    private val projection = RecordSceneWireProjection { FlightRecordScenePatch(2, "工作") }

    @Before fun open() = runBlocking(Dispatchers.IO) {
        TokenStore.setCancellable("summary-owner-a")
        preferences = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = preferences,
            produceFile = { File(temporary.root, "summary.preferences_pb") }))
        http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
        server = MockWebServer().also { it.start() }
        reopen()
    }

    private fun reopen() {
        if (::db.isInitialized) db.close()
        db = Room.databaseBuilder(context, AppDatabase::class.java, File(temporary.root, "summaries.db").absolutePath).build()
        journal = RecordSummaryMutationJournal(db); bindings = RecordMutationJournal(db)
        summaries = RecordSummaryStore(db, bindings, journal)
        controller = SessionController(object : MonotonicSource { override fun elapsedRealtimeMs() = 0L }, { 1_000_000L }, { "live-record" })
        sessions = SessionRepository(controller, settings, db.flightHistoryDao(), db.airportDao(), NoOpWidgetPush, TodayStatsNotifier())
        val gateway = HttpRecordMutationGateway(http, server.url("/").toString())
        val partial = RecordSummaryMutations(db, gateway, summaries, journal, bindings, sessions, clock)
        coordinator = RecordCloudMutationCoordinator(db, gateway, HttpRecordArchiveGateway(http, server.url("/").toString()),
            RecordCloudSnapshots(db, projection), bindings, RecordSceneStore(db, clock), RecordDeletionStore(db, sessions, TodayStatsNotifier()),
            projection, clock, partial)
    }

    @After fun close() = runBlocking(Dispatchers.IO) {
        TokenStore.clear(); preferences.coroutineContext[Job]?.cancelAndJoin(); db.close()
        server.close(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
    }

    private fun account() = HistoryAccount(TokenStore.storageScope(), TokenStore.snapshot().generation)
    private fun summary(id: String = "old-record", mode: String = "down", status: String = "arrived", revision: Long? = null) =
        FlightRecordSummary.parse(buildJsonObject {
            put("flightRecordId", id); put("recordType", mode); put("flightStatus", status)
            put("flightNumber", "AS321"); put("departureAirportCode", "PEK")
            put("focusIconType", 3); put("focusScenario", "阅读"); put("opaque", "retained")
            revision?.let { put("revision", it) }
        })
    private suspend fun seed(vararg values: FlightRecordSummary) {
        assertTrue(summaries.replace(TokenStore.snapshot(), values.toList(), true, values.size, clock.millis()))
    }
    private fun target(value: FlightRecordSummary) = RecordSummaryActionTarget(account(), value)
    private fun ack(revision: Int = 1) = MockResponse.Builder().code(200).body("""{"success":true,"data":{"revision":$revision}}""").build()
    private fun rejected() = MockResponse.Builder().code(503).body("""{"success":false}""").build()
    private suspend fun failure(block: suspend () -> Unit) {
        try { block(); fail("An unconfirmed operation must remain pending") } catch (_: RecordCloudFailure) { }
    }
    private fun full(status: String = "ONGOING") = FlightHistoryEntity("old-record", null, "SOLO", null,
        "PEK", "Airport", "Beijing", "CN", 40.0, 116.0, "LHR", "Airport", "London", "GB", 51.4, -0.4,
        1_000_000, 1_300_000, 10, 5, 99.25, status, null, "01A", false, false, false, null, false, 1_000_000, 1_300_000,
        ownerScope = account().scope)

    @Test fun sceneChangesUseTheSummaryIdentityAndKeepUnknownFactsWithoutUploadingAnArchive() = runBlocking(Dispatchers.IO) {
        val down = summary(); val up = summary(mode = "up"); seed(down, up); server.enqueue(ack(7))
        assertEquals(RecordSummaryActionResult.APPLIED, coordinator.saveSummaryScene(target(down), work))
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("PATCH", request.method); assertEquals("/v2/flight-records/down/old-record/focus-scene", request.url.encodedPath)
        assertEquals("\"0\"", request.headers["If-Match"])
        assertEquals(setOf("focusIconType", "focusScenario"), Json.parseToJsonElement(request.body!!.utf8()).jsonObject.keys)
        reopen()
        val records = summaries.current(account().scope)
        val saved = records.single { it.mode == FlightRecordMode.DOWN }
        assertEquals("工作", saved.sceneLabel); assertEquals(7L, saved.revision); assertNull(saved.startedAtWallMs)
        assertNull(saved.departure?.latitude); assertEquals(JsonPrimitive("retained"), saved.raw["opaque"])
        assertEquals(up, records.single { it.mode == FlightRecordMode.UP })
        assertTrue(db.flightHistoryDao().getAll().isEmpty()); assertTrue(db.freeFlightArchiveDao().owned(account().scope).isEmpty())
        assertTrue(journal.pending(account().scope).isEmpty()); assertEquals(1, server.requestCount)
        seed(down, up)
        assertEquals("工作", summaries.current(account().scope).single { it.mode == FlightRecordMode.DOWN }.sceneLabel)
        seed(summary(revision = 8), up)
        assertEquals("阅读", summaries.current(account().scope).single { it.mode == FlightRecordMode.DOWN }.sceneLabel)
    }

    @Test fun aFailedDeletionRetriesTheSameOperationAfterRestartAndStaleListsCannotRestoreIt() = runBlocking(Dispatchers.IO) {
        val down = summary(); val up = summary(mode = "up"); seed(down, up); server.enqueue(rejected())
        failure { coordinator.deleteSummary(target(down)) }
        val first = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals(2, summaries.current(account().scope).size); assertFalse(journal.pending(account().scope).single().acknowledged)
        val status = RecordRecoveryStatusStore(db, bindings, RecordCloudSyncJournal(db), projection, clock, journal)
        status.update(TokenStore.snapshot(), RecordCloudSyncResult(pending = 1, retryable = true), syncing = false)
        assertEquals(1, status.observe().first().pendingChanges); assertEquals(0, status.observe().first().pendingUploads)
        reopen(); server.enqueue(ack(2))
        assertEquals(RecordMutationRecovery(1, 0), coordinator.recoverPending())
        val retry = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("DELETE", retry.method); assertEquals(first.headers["Idempotency-Key"], retry.headers["Idempotency-Key"])
        assertEquals(listOf(up), summaries.current(account().scope)); seed(down, up)
        assertEquals(listOf(up), summaries.current(account().scope)); assertEquals(2, server.requestCount)
        assertTrue(db.flightHistoryDao().getAll().isEmpty())
    }

    @Test fun acknowledgementAndVisibleSceneCommitTogetherAndRecoverWithoutAnotherRequest() = runBlocking(Dispatchers.IO) {
        val record = summary(); seed(record); server.enqueue(ack(4))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER block_summary_receipt BEFORE DELETE ON app_setting WHEN OLD.key LIKE 'record.summary.pending.%' AND instr(OLD.value, '\"acknowledged\":true') > 0 BEGIN SELECT RAISE(ABORT, 'receipt unavailable'); END")
        try { coordinator.saveSummaryScene(target(record), work); fail("The local transaction must fail") }
        catch (_: android.database.sqlite.SQLiteException) { }
        assertTrue(journal.pending(account().scope).single().acknowledged)
        assertEquals("阅读", summaries.current(account().scope).single().sceneLabel)
        reopen(); db.openHelper.writableDatabase.execSQL("DROP TRIGGER block_summary_receipt")
        assertEquals(RecordMutationRecovery(1, 0), coordinator.recoverPending())
        assertEquals("工作", summaries.current(account().scope).single().sceneLabel); assertEquals(1, server.requestCount)
    }

    @Test fun countUpSummaryChangesAndDeletionUseTheCountUpEndpointAndAcceptedVersion() = runBlocking(Dispatchers.IO) {
        val record = summary(mode = "up"); seed(record); server.enqueue(ack(7))
        assertEquals(RecordSummaryActionResult.APPLIED, coordinator.saveSummaryScene(target(record), work))
        val patch = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/v2/flight-records/up/old-record/focus-scene", patch.url.encodedPath)
        val updated = summaries.current(account().scope).single(); server.enqueue(ack(8))
        assertEquals(RecordSummaryActionResult.APPLIED, coordinator.deleteSummary(target(updated)))
        val deleted = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("DELETE", deleted.method); assertEquals("/v2/flight-records/up/old-record", deleted.url.encodedPath)
        assertEquals("\"7\"", deleted.headers["If-Match"]); assertTrue(summaries.current(account().scope).isEmpty())
        assertTrue(db.freeFlightArchiveDao().owned(account().scope).isEmpty())
    }

    @Test fun anUnversionedSceneReceiptSurvivesAnOlderReadButAllowsASubsequentFreshRead() = runBlocking(Dispatchers.IO) {
        val record = summary(); seed(record)
        val olderRead = summaries.sceneBaseline(TokenStore.snapshot())!!
        server.enqueue(MockResponse.Builder().code(200).body("""{"success":true}""").build())
        assertEquals(RecordSummaryActionResult.APPLIED, coordinator.saveSummaryScene(target(record), work))
        assertTrue(summaries.replace(TokenStore.snapshot(), listOf(record), true, 1, clock.millis(), olderRead))
        assertEquals("工作", summaries.current(account().scope).single().sceneLabel)
        val freshRead = summaries.sceneBaseline(TokenStore.snapshot())!!
        assertEquals(1, freshRead.size)
        assertTrue(summaries.replace(TokenStore.snapshot(), listOf(record), true, 1, clock.millis(), freshRead))
        assertEquals("阅读", summaries.current(account().scope).single().sceneLabel)
        assertTrue(journal.scenes(account().scope).isEmpty())
    }

    @Test fun aColdOngoingCountUpArchiveWithADifferentLocalIdentifierIsProtected() = runBlocking(Dispatchers.IO) {
        val record = summary(mode = "up"); seed(record)
        val archive = FreeFlightEngine().start(FreeFlightStartRequest("local-up", account().scope,
            PlannedAirport(1, "PEK", "Airport", "Beijing", "CN", 40.0, 116.0), 90.0, "01A", FreeFlightScene(3, "阅读", "reading")),
            FreeFlightClockReading(0, 0, 1_000_000, "clock")).copy(remoteRecordId = record.id)
        assertTrue(RoomFreeFlightArchiveStore(db.freeFlightArchiveDao()).commit(null, archive, null))
        assertEquals(RecordSummaryActionResult.PROTECTED, coordinator.deleteSummary(target(record)))
        assertEquals(RecordSummaryActionResult.PROTECTED, coordinator.saveSummaryScene(target(record), work))
        assertEquals(0, server.requestCount)
        assertEquals(FreeFlightStatus.ONGOING, decodeHistoryArchive(db.freeFlightArchiveDao().get("local-up", account().scope)!!).status)
    }

    @Test fun changedAndUnknownSummariesNeverSubmitAnOldConfirmation() = runBlocking(Dispatchers.IO) {
        val old = summary(); seed(summary(revision = 2))
        assertEquals(RecordSummaryActionResult.CHANGED, coordinator.deleteSummary(target(old)))
        for (status in listOf("unknown", "pending", "in_progress", "landing")) {
            val record = summary(status = status); seed(record)
            assertEquals(RecordSummaryActionResult.PROTECTED, coordinator.deleteSummary(target(record)))
            assertEquals(RecordSummaryActionResult.PROTECTED, coordinator.saveSummaryScene(target(record), work))
        }
        val cancelled = summary(status = "cancelled"); seed(cancelled)
        assertEquals(RecordSummaryActionResult.PROTECTED, coordinator.saveSummaryScene(target(cancelled), work))
        assertEquals(0, server.requestCount); assertTrue(journal.pending(account().scope).isEmpty())
    }

    @Test fun aMissingCachedSummaryIsNotReportedAsDeletedWithoutAConfirmedDeletionMarker() = runBlocking(Dispatchers.IO) {
        val record = summary(); seed(record); val confirmation = target(record); seed()
        assertEquals(RecordSummaryActionResult.CHANGED, coordinator.deleteSummary(confirmation))
        assertEquals(0, server.requestCount); assertTrue(journal.pending(account().scope).isEmpty())
        bindings.bind(account().scope, FlightRecordKey(FlightRecordKind.COUNTDOWN, "confirmed-deletion"),
            RecordCloudBinding(record.id, 1, true, false), clock.millis())
        assertEquals(RecordSummaryActionResult.MISSING, coordinator.deleteSummary(confirmation))
        assertEquals(0, server.requestCount)
    }

    @Test fun coldOngoingArchivesAndLiveAliasesKeepTheirDeletionProtection() = runBlocking(Dispatchers.IO) {
        val record = summary(); seed(record); db.flightHistoryDao().insert(full())
        assertEquals(RecordSummaryActionResult.PROTECTED, coordinator.deleteSummary(target(record)))
        db.openHelper.writableDatabase.execSQL("DELETE FROM flight_history WHERE id = 'old-record'")
        assertNull(db.flightHistoryDao().findById("old-record"))
        bindings.bind(account().scope, FlightRecordKey(FlightRecordKind.COUNTDOWN, "aliased-record"),
            RecordCloudBinding(record.id, 1, false, false), clock.millis())
        assertTrue(controller.start(1, 60_000, "old-record"))
        assertEquals(RecordSummaryActionResult.PROTECTED, coordinator.deleteSummary(target(record)))
        assertEquals(0, server.requestCount)
    }

    @Test fun anArrivingFullArchiveAfterRemoteAcknowledgementIsKeptForExplicitReconciliation() = runBlocking(Dispatchers.IO) {
        val record = summary(); seed(record)
        val received = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { received.complete(Unit); release.await(5, TimeUnit.SECONDS); return ack(2) }
        }
        val operation = async { coordinator.deleteSummary(target(record)) }
        try {
            withTimeout(5000) { received.await() }; db.flightHistoryDao().insert(full("COMPLETED")); release.countDown()
            assertEquals(RecordSummaryActionResult.CHANGED, operation.await()); assertNotNull(db.flightHistoryDao().findById(record.id))
            assertTrue(journal.pending(account().scope).single().acknowledged)
            assertEquals(RecordMutationRecovery(0, 1), coordinator.recoverPending()); assertEquals(1, server.requestCount)
        } finally { release.countDown(); operation.cancelAndJoin() }
    }

    @Test fun changingAccountsCancelsTheRequestAndLeavesOnlyTheOriginalOwnersDurableIntent() = runBlocking(Dispatchers.IO) {
        val record = summary(); seed(record); val original = target(record)
        val received = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { received.complete(Unit); release.await(5, TimeUnit.SECONDS); return ack(2) }
        }
        val operation = async { coordinator.deleteSummary(original) }
        try {
            withTimeout(5000) { received.await() }; withTimeout(1000) { TokenStore.setCancellable("summary-owner-b") }
            assertEquals(RecordSummaryActionResult.OBSOLETE, withTimeout(2000) { operation.await() })
            seed(record); assertEquals(RecordMutationRecovery(0, 0), coordinator.recoverPending())
            assertEquals(1, journal.pending(original.account.scope).size); assertEquals(listOf(record), summaries.current(account().scope))
            release.countDown(); TokenStore.setCancellable("summary-owner-a")
            assertEquals(RecordMutationRecovery(1, 0), coordinator.recoverPending()); assertTrue(summaries.current(account().scope).isEmpty())
        } finally { release.countDown(); operation.cancelAndJoin() }
    }
}
