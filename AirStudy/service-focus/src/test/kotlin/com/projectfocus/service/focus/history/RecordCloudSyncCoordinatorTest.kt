package com.projectfocus.service.focus.history

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.projectfocus.core.database.AppDatabase
import com.projectfocus.core.database.entity.*
import com.projectfocus.core.datastore.SettingsRepository
import com.projectfocus.core.model.flight.*
import com.projectfocus.core.model.flight.freeflight.*
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.*
import com.projectfocus.core.network.record.*
import com.projectfocus.service.focus.session.*
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.*
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
class RecordCloudSyncCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = Clock.fixed(Instant.ofEpochMilli(4_000_000), ZoneOffset.UTC)
    private val wall = 1_000_000L
    private lateinit var db: AppDatabase
    private lateinit var preferences: CoroutineScope
    private lateinit var settings: SettingsRepository
    private lateinit var sessions: SessionRepository
    private lateinit var journal: RecordMutationJournal
    private lateinit var inbox: RecordCloudSyncJournal
    private lateinit var snapshots: RecordCloudSnapshots
    private lateinit var mutations: RecordCloudMutationCoordinator
    private lateinit var sync: RecordCloudSyncCoordinator
    private val remote = Remote()
    private val work = PlannedScene("work-option", "工作", "work", 0, true, displayName = "工作")
    private val projection = object : RecordSceneWireProjection {
        override fun project(scene: PlannedScene) = FlightRecordScenePatch(if (scene.iconKey == "work") 2 else 3,
            scene.displayName?.takeIf(String::isNotBlank) ?: scene.name)
        override fun restore(patch: FlightRecordScenePatch): PlannedScene? = when (patch.focusIconType) {
            2 -> work.copy(name = patch.focusScenario, displayName = patch.focusScenario)
            3 -> PlannedScene("read-option", patch.focusScenario, "reading", 0, true, displayName = patch.focusScenario)
            else -> null
        }
    }
    private val notifier = object : TodayStatsNotifier() {
        var notifications = 0
        override fun notifyChanged() { notifications++; super.notifyChanged() }
    }
    @Before fun open() = runBlocking(Dispatchers.IO) {
        TokenStore.setCancellable("initial")
        login("a", "token-a")
        preferences = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = preferences,
            produceFile = { File(temporary.root, "settings.preferences_pb") }))
        reopen("first.db")
    }
    @After fun close() = runBlocking(Dispatchers.IO) {
        TokenStore.clear(); preferences.coroutineContext[Job]?.cancelAndJoin(); db.close()
    }
    private suspend fun login(owner: String, token: String = "token-$owner") {
        assertNotNull(TokenStore.publishAccountIfCurrentCancellable(TokenStore.snapshot(), token, owner.repeat(64)))
    }
    private fun account() = HistoryAccount(TokenStore.storageScope(), TokenStore.snapshot().generation)
    private fun reopen(name: String) {
        if (::db.isInitialized) db.close()
        db = Room.databaseBuilder(context, AppDatabase::class.java, File(temporary.root, name).absolutePath).build()
        sessions = SessionRepository(SessionController(object : MonotonicSource { override fun elapsedRealtimeMs() = 0L },
            { wall }, { "live-countdown" }), settings, db.flightHistoryDao(), db.airportDao(), NoOpWidgetPush, notifier)
        val deletion = RecordDeletionStore(db, sessions, notifier)
        journal = RecordMutationJournal(db); inbox = RecordCloudSyncJournal(db); snapshots = RecordCloudSnapshots(db, projection)
        mutations = RecordCloudMutationCoordinator(db, remote, remote, snapshots, journal, RecordSceneStore(db, clock), deletion, projection, clock)
        val importer = RecordCloudArchiveImport(db, snapshots, journal, inbox, deletion, projection, notifier, clock)
        sync = RecordCloudSyncCoordinator(db, remote, mutations, snapshots, journal, inbox, importer, clock)
    }
    private fun down(id: String = "shared-id", status: String = "COMPLETED", owner: String = account().scope) = FlightHistoryEntity(
        id, null, "SOLO", null, "PEK", "Airport", "Beijing", "CN", 40.0, 116.0,
        "LHR", "Airport", "London", "GB", 51.4, -0.4, wall, wall + 300_000, 10, 5, 99.25, status,
        null, "01A", false, false, false, null, false, wall, wall, ownerScope = owner)
    private suspend fun savedDown(id: String = "shared-id", status: String = "COMPLETED"):
        FlightRecordArchiveSnapshot {
        db.flightHistoryDao().insert(down(id, status))
        return requireNotNull(snapshots.capture(account(), FlightRecordKey(FlightRecordKind.COUNTDOWN, id))).upload.archive
    }
    private suspend fun savedUp(id: String = "shared-id", ongoing: Boolean = false, owner: String = account().scope): FreeFlightArchive {
        val engine = FreeFlightEngine(); val now = FreeFlightClockReading(0, 0, wall, "clock")
        val started = engine.start(FreeFlightStartRequest(id, owner,
            PlannedAirport(1, "PEK", "Airport", "Beijing", "CN", 40.0, 116.0), 90.0, "01A", FreeFlightScene(3, "阅读", "reading")), now)
        val at = now.copy(elapsedRealtimeMs = 90_001, uptimeMillis = 90_001, wallMs = wall + 90_001)
        val sampled = engine.sample(started, at).copy(sampleSequence = 1)
        val value = if (ongoing) sampled else {
            val selection = engine.prepareLanding(sampled, at)
            val chosen = engine.selectLanding(selection, PlannedAirport(2, "LHR", "Airport", "London", "GB", 51.4, -0.4), selection.landingSelectionGeneration)
            engine.completeLandingWithoutMap(engine.beginLandingWithoutMap(chosen, chosen.landingSelectionGeneration, at), at)
        }.copy(revision = 1, remoteRecordId = "remote-$id")
        check(db.freeFlightArchiveDao().insertArchive(FreeFlightArchiveEntity(id, owner, value.revision, value.status.name,
            if (ongoing) 1 else null, value.updatedAtWallMs, FreeFlightArchiveCodec.encode(value))) != -1L)
        if (sampled.sampleSequence > 0) db.freeFlightArchiveDao().insertPosition(FreeFlightPositionEntity(id, sampled.sampleSequence,
            sampled.clock.activeElapsedMs, sampled.position.latitude, sampled.position.longitude, sampled.bearingDegrees,
            sampled.cumulativeDistanceKm, at.wallMs))
        return value
    }
    private suspend fun wireUp(id: String = "shared-id") = requireNotNull(snapshots.capture(account(),
        FlightRecordKey(FlightRecordKind.COUNT_UP, id))).upload.archive
    private fun stageRecord(archive: FlightRecordArchiveSnapshot, scene: FlightRecordScenePatch? = null) =
        remote.seed(account().scope, archive.mode, archive.id, archive = archive, scene = scene)
    private fun sql(text: String) = db.openHelper.writableDatabase.execSQL(text)
    private suspend fun pendingChange(scene: PlannedScene?): CapturedCloudRecord {
        val key = FlightRecordKey(FlightRecordKind.COUNTDOWN, "shared-id")
        val current = requireNotNull(snapshots.capture(account(), key))
        journal.put(PendingRecordMutation(account().scope, key, key.id, current.fingerprint, "unconfirmed-change",
            requireNotNull(journal.binding(account().scope, key)).revision, PlannedFlightMetadataCodec.encodeScene(scene),
            scene?.let(projection::project), scene?.let { current.record.editedScene?.revision ?: 0L }, wall), clock.millis())
        return current
    }
    private suspend fun expectWriteFailure() {
        try { sync.synchronize() } catch (_: android.database.sqlite.SQLiteException) { return }
        throw AssertionError("Failed database transaction was reported as complete")
    }

    @Test fun bothModesRestoreTheirActualDataAndSamplesAfterReinstall() = runBlocking(Dispatchers.IO) {
        val expectedDown = savedDown(); val expectedUp = savedUp(); val expectedSamples = db.freeFlightArchiveDao().positions("shared-id", account().scope)
        assertTrue(expectedSamples.isNotEmpty())
        assertEquals(2, sync.synchronize().uploaded)
        assertEquals(setOf(FlightRecordMode.DOWN, FlightRecordMode.UP), remote.uploads.map { it.second.archive.mode }.toSet())
        reopen("second.db")
        val result = sync.synchronize()
        assertEquals(2, result.restored); assertEquals(0, result.pending)
        val restoredDown = requireNotNull(db.flightHistoryDao().findById("shared-id"))
        assertEquals(expectedDown.countdown!!.actualFocusMinutes, restoredDown.actualFocusMinutes)
        assertEquals(expectedDown.countdown!!.departure.latitude, restoredDown.departureLatitude, 0.0)
        val restored = decodeHistoryArchive(requireNotNull(db.freeFlightArchiveDao().get("shared-id", account().scope)))
        assertEquals(expectedUp, restored); assertEquals(expectedSamples, db.freeFlightArchiveDao().positions("shared-id", account().scope))
        assertEquals(0, sync.synchronize().restored); assertEquals(2, remote.uploads.size)
    }

    @Test fun pagedRestoreDoesNotSkipOrRepeatRecords() = runBlocking(Dispatchers.IO) {
        val snapshot = savedDown()
        for (index in 1..5) stageRecord(snapshot.copy(id = "remote-$index", countdown = snapshot.countdown!!.copy(id = "remote-$index")))
        db.flightHistoryDao().deleteById("shared-id"); remote.pageSize = 2
        val result = sync.synchronize()
        assertEquals(5, result.restored); assertEquals(listOf(0L, 2L, 4L), remote.reads.map { it.second })
        assertEquals(5, db.flightHistoryDao().getAll().size); assertEquals(5L, inbox.cursor(account().scope))
        assertTrue(remote.uploads.isEmpty()); assertEquals(0, sync.synchronize().restored)
    }

    @Test fun restorationKeepsTheOriginalCustomSceneSnapshotWhenItsCloudSceneIsUnchanged() = runBlocking(Dispatchers.IO) {
        val scene = PlannedScene("custom-work-identity", "专案", "work", 0xFFBB5500.toInt(), true,
            cloudId = 42, clientUuid = "scene-client-uuid", displayName = "专案")
        val original = down().copy(sceneSnapshotJson = PlannedFlightMetadataCodec.encodeScene(scene))
        db.flightHistoryDao().insert(original)
        assertEquals(1, sync.synchronize().uploaded)
        reopen("second.db"); assertEquals(1, sync.synchronize().restored)
        val restored = requireNotNull(db.flightHistoryDao().findById(original.id))
        assertEquals(original.sceneSnapshotJson, restored.sceneSnapshotJson)
        assertNull(db.flightRecordSceneDao().find("COUNTDOWN", original.id))
        val record = requireNotNull(snapshots.capture(account(), FlightRecordKey(FlightRecordKind.COUNTDOWN, original.id)))
        assertNull(record.record.editedScene)
        assertEquals(scene, PlannedFlightMetadataCodec.decodeSceneOrNull(restored.sceneSnapshotJson))
    }

    @Test fun cursorAndIncomingRecordsRollbackTogether() = runBlocking(Dispatchers.IO) {
        val snapshot = savedDown(); stageRecord(snapshot); db.flightHistoryDao().deleteById("shared-id")
        sql("CREATE TRIGGER reject_cursor BEFORE INSERT ON app_setting WHEN instr(NEW.key, 'record.cloud.cursor.v1.') = 1 BEGIN SELECT RAISE(ABORT, 'cursor write failed'); END")
        expectWriteFailure(); assertEquals(0L, inbox.cursor(account().scope)); assertTrue(inbox.entries(account().scope).isEmpty())
        sql("DROP TRIGGER reject_cursor"); reopen("first.db")
        assertEquals(1, sync.synchronize().restored); assertEquals(1L, inbox.cursor(account().scope))
    }

    @Test fun failedImportKeepsTheDownloadedSnapshotForRestart() = runBlocking(Dispatchers.IO) {
        val snapshot = savedDown(); stageRecord(snapshot, FlightRecordScenePatch(2, "工作")); db.flightHistoryDao().deleteById("shared-id")
        sql("CREATE TRIGGER reject_annotation BEFORE INSERT ON flight_record_scene BEGIN SELECT RAISE(ABORT, 'scene write failed'); END")
        expectWriteFailure(); assertNull(db.flightHistoryDao().findById("shared-id"))
        assertEquals(1L, inbox.cursor(account().scope)); assertEquals(1, inbox.entries(account().scope).size)
        sql("DROP TRIGGER reject_annotation"); reopen("first.db")
        assertEquals(1, sync.synchronize().restored); assertTrue(inbox.entries(account().scope).isEmpty())
        assertEquals("工作", PlannedFlightMetadataCodec.decodeSceneOrNull(db.flightRecordSceneDao().find("COUNTDOWN", "shared-id")!!.sceneJson)!!.name)
    }

    @Test fun unknownSceneIsRetainedWithoutInventingItsAppearance() = runBlocking(Dispatchers.IO) {
        val snapshot = savedDown(); stageRecord(snapshot, FlightRecordScenePatch(999, "未知场景")); db.flightHistoryDao().deleteById("shared-id")
        val result = sync.synchronize()
        assertTrue(result.retryable); assertEquals(1, result.pending); assertNull(db.flightHistoryDao().findById("shared-id"))
        assertEquals(999, inbox.entries(account().scope).single().scene!!.focusIconType); assertTrue(remote.uploads.isEmpty())
    }

    @Test fun oldCloudEntryWithoutAnArchiveDoesNotInventAirportsOrTrack() = runBlocking(Dispatchers.IO) {
        remote.seed(account().scope, FlightRecordMode.DOWN, "legacy", scene = FlightRecordScenePatch(2, "工作"))
        val result = sync.synchronize()
        assertEquals(1, result.pending); assertTrue(db.flightHistoryDao().getAll().isEmpty())
        assertTrue(remote.uploads.isEmpty()); assertNull(inbox.entries(account().scope).single().archive)
    }

    @Test fun accountChangeDuringDownloadDoesNotPublishTheOldResponse() = runBlocking(Dispatchers.IO) {
        val snapshot = savedDown(); stageRecord(snapshot); db.flightHistoryDao().deleteById("shared-id")
        val oldScope = account().scope
        remote.beforeRead = { login("b") }
        assertTrue(sync.synchronize().obsolete)
        assertTrue(db.flightHistoryDao().getAll().isEmpty()); assertEquals(0L, inbox.cursor(oldScope))
        assertEquals(0L, inbox.cursor(account().scope)); assertTrue(inbox.entries(oldScope).isEmpty())
    }

    @Test fun accountChangeDuringUploadCannotBindTheNewAccount() = runBlocking(Dispatchers.IO) {
        savedDown(); val old = account(); remote.beforeUpload = { login("b") }
        assertTrue(withTimeout(5000) { sync.synchronize() }.obsolete)
        val key = FlightRecordKey(FlightRecordKind.COUNTDOWN, "shared-id")
        assertNull(journal.binding(account().scope, key)); assertNull(journal.binding(old.scope, key))
        assertEquals(old.scope, db.flightHistoryDao().findById(key.id)!!.ownerScope)
        login("a", "new-token-a"); assertEquals(1, sync.synchronize().restored); assertEquals(1, remote.uploads.size)
    }

    @Test fun collidingIdentifiersUseAccountAliasesAndKeepRemoteDeletionKeys() = runBlocking(Dispatchers.IO) {
        val ownDown = savedDown(); savedUp(); val ownUp = wireUp()
        stageRecord(ownDown); stageRecord(ownUp)
        val ownScope = account().scope; login("b"); val foreign = account().scope
        db.flightHistoryDao().update(down(owner = foreign))
        val priorUp = requireNotNull(db.freeFlightArchiveDao().getById("shared-id"))
        val priorPositions = db.freeFlightArchiveDao().positions("shared-id", ownScope)
        val otherUp = decodeHistoryArchive(priorUp).copy(ownerScope = foreign)
        sql("DELETE FROM free_flight_archive WHERE sessionId = 'shared-id'")
        check(db.freeFlightArchiveDao().insertArchive(priorUp.copy(ownerScope = foreign, payloadJson = FreeFlightArchiveCodec.encode(otherUp))) != -1L)
        for (position in priorPositions) db.freeFlightArchiveDao().insertPosition(position)
        login("a", "renewed-a"); assertEquals(2, sync.synchronize().restored)
        assertEquals(foreign, db.flightHistoryDao().findById("shared-id")!!.ownerScope)
        assertEquals(foreign, db.freeFlightArchiveDao().getById("shared-id")!!.ownerScope)
        val aliases = journal.bindings(ownScope); assertEquals(2, aliases.size)
        val upKey = aliases.single { it.key.kind == FlightRecordKind.COUNT_UP }.key
        assertNotEquals("shared-id", upKey.id)
        val restored = requireNotNull(snapshots.capture(account(), upKey, "remote-shared-id"))
        assertEquals(RecordDeletionResult.DELETED, mutations.delete(RecordDeletionTarget(account(), restored.record)))
        assertEquals("remote-shared-id", remote.deleted.single().second)
        assertNotNull(db.freeFlightArchiveDao().getById("shared-id"))
        assertEquals(priorPositions, db.freeFlightArchiveDao().positions("shared-id", foreign))
        assertEquals(down(owner = foreign), db.flightHistoryDao().findById("shared-id"))
    }

    @Test fun remoteDeletionIsAppliedBeforeAnUnboundLocalRecordCanUpload() = runBlocking(Dispatchers.IO) {
        savedDown(); remote.seed(account().scope, FlightRecordMode.DOWN, "shared-id", deleted = true)
        assertEquals(1, sync.synchronize().removed); assertNull(db.flightHistoryDao().findById("shared-id"))
        assertTrue(remote.uploads.isEmpty()); reopen("second.db")
        assertEquals(0, sync.synchronize().restored); assertTrue(db.flightHistoryDao().getAll().isEmpty())
    }

    @Test fun downloadingADeletionCannotReplaceAnOngoingCountdown() = runBlocking(Dispatchers.IO) {
        db.flightHistoryDao().insert(down(status = "ONGOING")); remote.seed(account().scope, FlightRecordMode.DOWN, "shared-id", deleted = true)
        val result = sync.synchronize()
        assertEquals(1, result.pending); assertTrue(result.retryable); assertEquals("ONGOING", db.flightHistoryDao().findById("shared-id")!!.status)
        assertTrue(remote.uploads.isEmpty())
    }

    @Test fun downloadingADeletionCannotRemoveAnActiveCountUp() = runBlocking(Dispatchers.IO) {
        val active = savedUp(ongoing = true); remote.seed(account().scope, FlightRecordMode.UP, "remote-shared-id", deleted = true)
        val result = sync.synchronize()
        assertEquals(1, result.pending); assertEquals(active, decodeHistoryArchive(db.freeFlightArchiveDao().getActive()!!))
        assertTrue(remote.uploads.isEmpty())
    }

    @Test fun aTerminalRuntimeReservationKeepsItsDeletionPendingUntilReleased() = runBlocking(Dispatchers.IO) {
        val value = savedUp(); val owner = account().scope
        val lease = sessions.openFreeFlightHost(owner)
        assertTrue(sessions.reserveFreeFlightRuntime(value.sessionId, lease))
        remote.seed(owner, FlightRecordMode.UP, "remote-shared-id", deleted = true)
        val protected = sync.synchronize()
        assertEquals(1, protected.pending); assertTrue(protected.retryable)
        assertNotNull(db.freeFlightArchiveDao().get(value.sessionId, owner))
        sessions.releaseFreeFlightRuntime(value.sessionId, lease)
        assertEquals(1, sync.synchronize().removed)
        assertNull(db.freeFlightArchiveDao().get(value.sessionId, owner)); assertTrue(inbox.entries(owner).isEmpty())
        assertTrue(remote.uploads.isEmpty())
    }

    @Test fun differentLocalContentsStayProtectedAndDoNotOverwriteTheCloud() = runBlocking(Dispatchers.IO) {
        val snapshot = savedDown(); stageRecord(snapshot.copy(countdown = snapshot.countdown!!.copy(actualFocusMinutes = 7)))
        assertEquals(1, sync.synchronize().pending); assertEquals(5, db.flightHistoryDao().findById("shared-id")!!.actualFocusMinutes)
        assertTrue(remote.uploads.isEmpty()); reopen("first.db"); assertEquals(1, sync.synchronize().pending)
    }

    @Test fun lostUploadAcknowledgementIsRecoveredFromTheChangeFeed() = runBlocking(Dispatchers.IO) {
        savedDown(); remote.loseNextUploadAcknowledgement = true
        val first = sync.synchronize(); assertTrue(first.retryable); assertEquals(1, first.pending)
        reopen("first.db"); assertEquals(1, sync.synchronize().restored)
        assertEquals(1, remote.uploads.size); assertEquals(1, remote.records[account().scope]!!.size)
        assertTrue(journal.binding(account().scope, FlightRecordKey(FlightRecordKind.COUNTDOWN, "shared-id"))!!.archiveStored)
    }

    @Test fun localBindingFailureDoesNotRepeatTheRemoteArchive() = runBlocking(Dispatchers.IO) {
        savedDown()
        sql("CREATE TRIGGER reject_binding BEFORE INSERT ON app_setting WHEN instr(NEW.key, 'record.cloud.binding.v1.') = 1 BEGIN SELECT RAISE(ABORT, 'binding write failed'); END")
        expectWriteFailure(); assertEquals(1, remote.uploads.size)
        sql("DROP TRIGGER reject_binding"); reopen("first.db")
        assertEquals(1, sync.synchronize().restored); assertEquals(1, remote.uploads.size)
    }

    @Test fun acceptedSceneRecoveryFinishesBeforeItsIncomingCloudVersion() = runBlocking(Dispatchers.IO) {
        savedDown(); assertEquals(1, sync.synchronize().uploaded)
        val key = FlightRecordKey(FlightRecordKind.COUNTDOWN, "shared-id")
        val original = requireNotNull(snapshots.capture(account(), key))
        val version = stageRecord(original.upload.archive, FlightRecordScenePatch(2, "工作")).revision
        journal.put(PendingRecordMutation(account().scope, key, key.id, original.fingerprint, "accepted-scene", 1,
            PlannedFlightMetadataCodec.encodeScene(work), FlightRecordScenePatch(2, "工作"), 0, wall,
            acknowledged = true, acceptedRevision = version), clock.millis())
        val result = sync.synchronize()
        assertEquals(0, result.pending); assertTrue(journal.pending(account().scope).isEmpty())
        val annotation = db.flightRecordSceneDao().find("COUNTDOWN", key.id)!!
        assertEquals(1L, annotation.revision); assertEquals(work, PlannedFlightMetadataCodec.decodeSceneOrNull(annotation.sceneJson))
        assertEquals(version, journal.binding(account().scope, key)!!.revision)
    }

    private suspend fun finishPendingWithCloudDeletion(scene: PlannedScene?) {
        savedDown(); assertEquals(1, sync.synchronize().uploaded); pendingChange(scene)
        remote.seed(account().scope, FlightRecordMode.DOWN, "shared-id", deleted = true)
        remote.mutationFailure = RecordMutationOutcome.NetworkFailure
        val result = sync.synchronize()
        assertEquals(1, result.removed); assertEquals(0, result.pending); assertFalse(result.retryable)
        assertNull(db.flightHistoryDao().findById("shared-id")); assertTrue(journal.pending(account().scope).isEmpty())
        assertTrue(journal.binding(account().scope, FlightRecordKey(FlightRecordKind.COUNTDOWN, "shared-id"))!!.deleted)
        assertEquals(1, remote.uploads.size)
    }
    @Test fun aDownloadedDeletionCompletesAnUnconfirmedDeleteWithoutAnotherWrite() = runBlocking(Dispatchers.IO) {
        finishPendingWithCloudDeletion(null)
    }
    @Test fun aDownloadedDeletionEndsAnUnconfirmedSceneChange() = runBlocking(Dispatchers.IO) {
        finishPendingWithCloudDeletion(work)
    }
    @Test fun aNewerCloudSceneReleasesARejectedIntentAndAllowsANewConfirmedChoice() = runBlocking(Dispatchers.IO) {
        savedDown(); assertEquals(1, sync.synchronize().uploaded); val original = pendingChange(work)
        stageRecord(original.upload.archive, FlightRecordScenePatch(3, "云端阅读"))
        assertEquals(0, sync.synchronize().pending); assertTrue(journal.pending(account().scope).isEmpty())
        assertEquals("云端阅读", PlannedFlightMetadataCodec.decodeSceneOrNull(db.flightRecordSceneDao().find("COUNTDOWN", "shared-id")!!.sceneJson)!!.name)
        val key = FlightRecordKey(FlightRecordKind.COUNTDOWN, "shared-id")
        assertTrue(mutations.saveScene(RecordSceneEditTarget(account(), key, wall, 1), work) is RecordSceneSaveResult.Saved)
        assertEquals("工作", remote.records[account().scope]!![FlightRecordMode.DOWN to key.id]!!.scene!!.focusScenario)
        assertEquals(5, db.flightHistoryDao().findById(key.id)!!.actualFocusMinutes)
    }
    @Test fun aChangedCloudSceneRequiresAFreshDeletionConfirmation() = runBlocking(Dispatchers.IO) {
        savedDown(); assertEquals(1, sync.synchronize().uploaded); val original = pendingChange(null)
        stageRecord(original.upload.archive, FlightRecordScenePatch(2, "工作"))
        assertEquals(0, sync.synchronize().pending); assertNotNull(db.flightHistoryDao().findById("shared-id"))
        assertTrue(journal.pending(account().scope).isEmpty())
        val actual = requireNotNull(snapshots.capture(account(), FlightRecordKey(FlightRecordKind.COUNTDOWN, "shared-id")))
        assertEquals("工作", actual.record.editedScene!!.snapshot.name)
        assertEquals(RecordDeletionResult.DELETED, mutations.delete(RecordDeletionTarget(account(), actual.record)))
        assertNull(db.flightHistoryDao().findById("shared-id"))
    }

    @Test fun renewedCredentialsKeepOneHistoryAndDoNotResubmitOldRecords() = runBlocking(Dispatchers.IO) {
        savedDown(); assertEquals(1, sync.synchronize().uploaded); val owner = account().scope
        TokenStore.clear(); login("a", "new-token"); assertEquals(owner, account().scope)
        assertEquals(0, sync.synchronize().uploaded); assertEquals(1, remote.uploads.size)
        login("b"); assertEquals(0, sync.synchronize().restored); assertEquals(0, sync.synchronize().uploaded)
        assertEquals(owner, db.flightHistoryDao().findById("shared-id")!!.ownerScope)
    }

    private class Remote : RecordArchiveGateway, RecordMutationGateway {
        val records = mutableMapOf<String, MutableMap<Pair<FlightRecordMode, String>, CloudFlightRecord>>()
        val uploads = mutableListOf<Pair<TokenStore.Snapshot, FlightRecordArchiveUpload>>()
        val reads = mutableListOf<Pair<TokenStore.Snapshot, Long>>()
        val deleted = mutableListOf<Pair<FlightRecordMode, String>>()
        private val receipts = mutableMapOf<Pair<String, String>, Long>()
        private val revisions = mutableMapOf<String, Long>()
        var pageSize = 50
        var beforeRead: (suspend () -> Unit)? = null
        var beforeUpload: (suspend () -> Unit)? = null
        var loseNextUploadAcknowledgement = false
        var mutationFailure: RecordMutationOutcome? = null
        fun seed(owner: String, mode: FlightRecordMode, id: String, deleted: Boolean = false,
                 archive: FlightRecordArchiveSnapshot? = null, scene: FlightRecordScenePatch? = null): CloudFlightRecord {
            val version = (revisions[owner] ?: 0) + 1; revisions[owner] = version
            return CloudFlightRecord(mode, id, version, deleted, archive, scene).also {
                records.getOrPut(owner) { mutableMapOf() }[mode to id] = it
            }
        }
        override suspend fun changes(identity: TokenStore.Snapshot, after: Long, limit: Int): RecordArchiveReadResult {
            reads += identity to after
            val all = records[TokenStore.storageScope(identity)].orEmpty().values.filter { it.revision > after }.sortedBy { it.revision }
            val page = all.take(minOf(limit, pageSize)); beforeRead?.also { beforeRead = null }?.invoke()
            return RecordArchiveReadResult.Loaded(CloudFlightRecordPage(page, hasMore = all.size > page.size, nextCursor = page.lastOrNull()?.revision ?: after))
        }
        override suspend fun upload(identity: TokenStore.Snapshot, record: FlightRecordArchiveUpload, options: FlightRecordMutationOptions): RecordMutationOutcome {
            uploads += identity to record
            val owner = TokenStore.storageScope(identity); val operation = owner to requireNotNull(options.operationId)
            receipts[operation]?.let { return RecordMutationOutcome.Acknowledged(revision = it) }
            val current = records[owner]?.get(record.archive.mode to record.archive.id)
            if (current?.deleted == true) return RecordMutationOutcome.Rejected(410)
            if ((current?.revision ?: 0L) != options.expectedRevision) return RecordMutationOutcome.Rejected(409)
            val saved = seed(owner, record.archive.mode, record.archive.id, archive = record.archive, scene = current?.scene ?: record.scene)
            receipts[operation] = saved.revision
            beforeUpload?.also { beforeUpload = null }?.invoke()
            return if (loseNextUploadAcknowledgement) { loseNextUploadAcknowledgement = false; RecordMutationOutcome.NetworkFailure }
                else RecordMutationOutcome.Acknowledged(revision = saved.revision)
        }
        override suspend fun delete(identity: TokenStore.Snapshot, mode: FlightRecordMode, recordId: String, options: FlightRecordMutationOptions): RecordMutationOutcome {
            deleted += mode to recordId
            mutationFailure?.let { return it }
            val owner = TokenStore.storageScope(identity); val current = records[owner]?.get(mode to recordId)
            if (current?.deleted == true) return RecordMutationOutcome.Acknowledged(revision = current.revision)
            if (options.expectedRevision != null && options.expectedRevision != current?.revision) return RecordMutationOutcome.Rejected(409)
            return RecordMutationOutcome.Acknowledged(revision = seed(owner, mode, recordId, deleted = true).revision)
        }
        override suspend fun changeScene(identity: TokenStore.Snapshot, mode: FlightRecordMode, recordId: String,
            scene: FlightRecordScenePatch, options: FlightRecordMutationOptions): RecordMutationOutcome {
            mutationFailure?.let { return it }
            val owner = TokenStore.storageScope(identity); val current = records[owner]?.get(mode to recordId) ?: return RecordMutationOutcome.Rejected(404)
            if (current.deleted) return RecordMutationOutcome.Rejected(404)
            if (options.expectedRevision != current.revision) return RecordMutationOutcome.Rejected(409)
            return RecordMutationOutcome.Acknowledged(revision = seed(owner, mode, recordId, archive = current.archive, scene = scene).revision)
        }
    }
}
