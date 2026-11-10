package com.projectfocus.service.focus.history

import androidx.room.withTransaction
import com.projectfocus.core.database.AppDatabase
import com.projectfocus.core.model.flight.PlannedFlightMetadataCodec
import com.projectfocus.core.model.flight.PlannedScene
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.*
import com.projectfocus.core.network.record.*
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RecordCloudFailure(val reason: RecordMutationOutcome) : Exception("Record change was not confirmed")

data class RecordMutationRecovery(val completed: Int, val pending: Int,
    val retryable: Boolean = false, val authenticationRequired: Boolean = false)

/** Remote acknowledgement is durable before a guarded local change; both can resume after restart. */
@Singleton
class RecordCloudMutationCoordinator @Inject constructor(
    private val db: AppDatabase,
    private val mutations: RecordMutationGateway,
    private val archives: RecordArchiveGateway,
    private val snapshots: RecordCloudSnapshots,
    private val journal: RecordMutationJournal,
    private val localScenes: RecordSceneStore,
    private val localDeletion: RecordDeletionStore,
    private val sceneProjection: RecordSceneWireProjection,
    private val clock: Clock,
    private val summaryMutations: RecordSummaryMutations? = null,
) {
    private val writer = Mutex()

    internal suspend fun <T> serialize(block: suspend () -> T): T = writer.withLock { block() }

    internal suspend fun summaryPending(scope: String): Int = summaryMutations?.count(scope) ?: 0

    suspend fun deleteSummary(target: RecordSummaryActionTarget): RecordSummaryActionResult = writer.withLock {
        requireNotNull(summaryMutations).execute(target, null)
    }

    suspend fun saveSummaryScene(target: RecordSummaryActionTarget, scene: PlannedScene): RecordSummaryActionResult = writer.withLock {
        requireNotNull(summaryMutations).execute(target, sceneProjection.project(scene).normalized())
    }

    suspend fun delete(target: RecordDeletionTarget): RecordDeletionResult = writer.withLock {
        val identity = identity(target.account) ?: return@withLock RecordDeletionResult.OBSOLETE
        if (!terminal(target.record)) return@withLock RecordDeletionResult.PROTECTED
        var alreadyRemoved = false
        val entry = TokenStore.withSnapshotLease(identity) {
            db.withTransaction {
                val binding = journal.binding(target.account.scope, target.record.key)
                val actual = snapshots.capture(target.account, target.record.key, binding?.remoteId) ?: run {
                    alreadyRemoved = binding?.deleted == true
                    return@withTransaction null
                }
                if (actual.record != target.record) return@withTransaction null
                prepare(target.account, actual, binding, null, null, null)
            }
        } ?: return@withLock when {
            !target.account.isCurrent() -> RecordDeletionResult.OBSOLETE
            alreadyRemoved -> RecordDeletionResult.MISSING
            else -> RecordDeletionResult.CHANGED
        }
        val confirmed = acknowledge(identity, entry)
            ?: return@withLock RecordDeletionResult.OBSOLETE
        applyDelete(identity, confirmed)
    }

    suspend fun saveScene(target: RecordSceneEditTarget, scene: PlannedScene): RecordSceneSaveResult = writer.withLock {
        val identity = identity(target.account) ?: return@withLock RecordSceneSaveResult.Obsolete
        val json = requireNotNull(PlannedFlightMetadataCodec.encodeScene(scene))
        val patch = sceneProjection.project(scene).normalized()
        val entry = TokenStore.withSnapshotLease(identity) {
            db.withTransaction {
                val binding = journal.binding(target.account.scope, target.key)
                val actual = snapshots.capture(target.account, target.key, binding?.remoteId) ?: return@withTransaction null
                if (!actual.record.completed || actual.record.recordedAtWallMs != target.startedAtWallMs ||
                    (actual.record.editedScene?.revision ?: 0L) != target.expectedRevision) return@withTransaction null
                prepare(target.account, actual, binding, json, patch, target.expectedRevision)
            }
        } ?: return@withLock if (target.account.isCurrent()) RecordSceneSaveResult.Changed else RecordSceneSaveResult.Obsolete
        val confirmed = acknowledge(identity, entry) ?: return@withLock RecordSceneSaveResult.Obsolete
        applyScene(identity, confirmed)
    }

    suspend fun recoverPending(expectedIdentity: TokenStore.Snapshot? = null): RecordMutationRecovery = writer.withLock {
        val identity = TokenStore.snapshot()
        if (expectedIdentity != null && identity != expectedIdentity) return@withLock RecordMutationRecovery(0, 0)
        if (identity.token.isNullOrBlank()) return@withLock RecordMutationRecovery(0, 0)
        val account = HistoryAccount(TokenStore.storageScope(identity), identity.generation)
        val entries = TokenStore.withSnapshotLease(identity) { journal.pending(account.scope) }
            ?: return@withLock RecordMutationRecovery(0, 0)
        var completed = 0
        var retryable = false
        var authenticationRequired = false
        for (entry in entries) {
            if (!account.isCurrent()) break
            val current = TokenStore.withSnapshotLease(identity) {
                db.withTransaction { snapshots.capture(account, entry.key, entry.remoteId) }
            }
            if (current == null && !entry.acknowledged || current != null && current.fingerprint != entry.fingerprint) continue
            val confirmed = try { acknowledge(identity, entry) ?: break }
                catch (failure: RecordCloudFailure) {
                    val reason = failure.reason
                    if (reason is RecordMutationOutcome.Rejected && reason.status == 401) {
                        authenticationRequired = true
                        break
                    }
                    if (reason == RecordMutationOutcome.NetworkFailure || reason == RecordMutationOutcome.InvalidResponse ||
                        reason is RecordMutationOutcome.Rejected && reason.retryable) retryable = true
                    continue
                }
            val applied = if (confirmed.desiredSceneJson == null)
                applyDelete(identity, confirmed) in setOf(RecordDeletionResult.DELETED, RecordDeletionResult.MISSING)
            else applyScene(identity, confirmed) is RecordSceneSaveResult.Saved
            if (applied) completed++
        }
        val pending = TokenStore.withSnapshotLease(identity) { journal.pending(account.scope).size } ?: entries.size - completed
        val partial = if (account.isCurrent() && !authenticationRequired) summaryMutations?.recover(identity) else null
        RecordMutationRecovery(completed + (partial?.completed ?: 0), pending + (partial?.pending ?: summaryPending(account.scope)),
            retryable || partial?.retryable == true, authenticationRequired || partial?.authenticationRequired == true)
    }

    private fun identity(account: HistoryAccount): TokenStore.Snapshot? {
        val identity = TokenStore.snapshot()
        if (!account.isCurrent()) return null
        if (identity.token.isNullOrBlank()) throw RecordCloudFailure(RecordMutationOutcome.Rejected(401))
        return identity
    }

    private suspend fun prepare(account: HistoryAccount, actual: CapturedCloudRecord, binding: RecordCloudBinding?,
        sceneJson: String?, patch: FlightRecordScenePatch?, sceneRevision: Long?): PendingRecordMutation {
        val key = actual.record.key
        val previous = journal.pending(account.scope, key)
        var expectedRemoteRevision = binding?.revision
        if (previous != null) {
            if (previous.fingerprint != actual.fingerprint)
                throw RecordCloudFailure(RecordMutationOutcome.Rejected(409))
            if (previous.desiredSceneJson == sceneJson && previous.scenePatch == patch) return previous
            if (!previous.acknowledged || previous.scenePatch == null)
                throw RecordCloudFailure(RecordMutationOutcome.Rejected(409))
            // A newly confirmed choice can supersede an accepted scene whose local write failed.
            // Preserve the accepted remote revision, and replace both intent and binding atomically.
            expectedRemoteRevision = previous.acceptedRevision
            journal.bind(account.scope, key, RecordCloudBinding(previous.remoteId, expectedRemoteRevision, false,
                binding?.archiveStored ?: true), clock.millis())
        }
        return PendingRecordMutation(account.scope, key, actual.upload.archive.id, actual.fingerprint,
            UUID.randomUUID().toString(), expectedRemoteRevision, sceneJson, patch, sceneRevision, actual.record.recordedAtWallMs)
            .also { journal.put(it, clock.millis()) }
    }

    private suspend fun acknowledge(identity: TokenStore.Snapshot, original: PendingRecordMutation): PendingRecordMutation? {
        if (TokenStore.snapshot() != identity) return null
        if (original.acknowledged) return original
        var entry = original
        if (entry.desiredSceneJson != null && entry.expectedRemoteRevision == null) {
            val account = HistoryAccount(entry.scope, identity.generation)
            val capture = TokenStore.withSnapshotLease(identity) {
                db.withTransaction { snapshots.capture(account, entry.key, entry.remoteId) }
            } ?: return null
            if (capture.fingerprint != entry.fingerprint) throw RecordCloudFailure(RecordMutationOutcome.Rejected(409))
            val uploaded = accepted(archives.upload(identity, capture.upload, FlightRecordMutationOptions(entry.operationId + ":archive")))
                ?: return null
            val revision = uploaded.revision ?: throw RecordCloudFailure(RecordMutationOutcome.InvalidResponse)
            entry = entry.copy(expectedRemoteRevision = revision)
            if (TokenStore.withSnapshotLease(identity) {
                db.withTransaction {
                    check(journal.pending(entry.scope, entry.key)?.operationId == entry.operationId)
                    journal.bind(entry.scope, entry.key, RecordCloudBinding(entry.remoteId, revision, false), clock.millis())
                    journal.put(entry, clock.millis())
                }
                true
            } != true) return null
        }
        val options = FlightRecordMutationOptions(entry.operationId, entry.expectedRemoteRevision)
        val result = if (entry.scenePatch == null) mutations.delete(identity, mode(entry.key), entry.remoteId, options)
            else mutations.changeScene(identity, mode(entry.key), entry.remoteId, entry.scenePatch, options)
        val receipt = accepted(result) ?: return null
        val confirmed = entry.copy(acknowledged = true, acceptedRevision = receipt.revision)
        return TokenStore.withSnapshotLease(identity) {
            db.withTransaction {
                check(journal.pending(entry.scope, entry.key)?.operationId == entry.operationId)
                journal.put(confirmed, clock.millis())
            }
            confirmed
        }
    }

    private fun accepted(outcome: RecordMutationOutcome): RecordMutationOutcome.Acknowledged? = when (outcome) {
        is RecordMutationOutcome.Acknowledged -> outcome
        RecordMutationOutcome.Obsolete -> null
        else -> throw RecordCloudFailure(outcome)
    }

    private suspend fun applyDelete(identity: TokenStore.Snapshot, entry: PendingRecordMutation): RecordDeletionResult {
        val account = HistoryAccount(entry.scope, identity.generation)
        val actual = TokenStore.withSnapshotLease(identity) {
            db.withTransaction { snapshots.capture(account, entry.key, entry.remoteId) }
        }
        if (!account.isCurrent()) return RecordDeletionResult.OBSOLETE
        if (actual == null) {
            // A local row can disappear after remote confirmation; finish only this account's receipt.
            return TokenStore.withSnapshotLease(identity) {
                db.withTransaction {
                    val rawExists = when (entry.key.kind) {
                        FlightRecordKind.COUNTDOWN -> db.flightHistoryDao().findById(entry.key.id) != null
                        FlightRecordKind.COUNT_UP -> db.freeFlightArchiveDao().get(entry.key.id, entry.scope) != null
                    }
                    if (rawExists) RecordDeletionResult.CHANGED else { finish(entry, true); RecordDeletionResult.MISSING }
                }
            } ?: RecordDeletionResult.OBSOLETE
        }
        if (actual.fingerprint != entry.fingerprint) return RecordDeletionResult.CHANGED
        return localDeletion.deleteAtomically(RecordDeletionTarget(account, actual.record),
            { snapshots.capture(account, entry.key, entry.remoteId)?.fingerprint == entry.fingerprint },
            { finish(entry, true) })
    }

    private suspend fun applyScene(identity: TokenStore.Snapshot, entry: PendingRecordMutation): RecordSceneSaveResult {
        val account = HistoryAccount(entry.scope, identity.generation)
        if (!account.isCurrent()) return RecordSceneSaveResult.Obsolete
        val scene = requireNotNull(PlannedFlightMetadataCodec.decodeSceneOrNull(entry.desiredSceneJson))
        return localScenes.saveAtomically(RecordSceneEditTarget(account, entry.key, entry.startedAt, requireNotNull(entry.sceneRevision)), scene,
            { snapshots.capture(account, entry.key, entry.remoteId)?.fingerprint == entry.fingerprint },
            { finish(entry, false) })
    }

    private suspend fun finish(entry: PendingRecordMutation, deleted: Boolean) {
        val stored = requireNotNull(journal.pending(entry.scope, entry.key))
        check(stored.operationId == entry.operationId && stored.acknowledged)
        val previous = journal.binding(entry.scope, entry.key)
        journal.bind(entry.scope, entry.key, RecordCloudBinding(entry.remoteId, stored.acceptedRevision, deleted,
            previous?.archiveStored ?: true), clock.millis())
        journal.remove(entry)
    }

    private fun terminal(record: RecordedFlight): Boolean = when (record) {
        is RecordedFlight.Countdown -> record.record.status in setOf("COMPLETED", "CANCELLED")
        is RecordedFlight.CountUp -> record.archive.status != com.projectfocus.core.model.flight.freeflight.FreeFlightStatus.ONGOING
    }
    private fun mode(key: FlightRecordKey) = if (key.kind == FlightRecordKind.COUNTDOWN) FlightRecordMode.DOWN else FlightRecordMode.UP
}
