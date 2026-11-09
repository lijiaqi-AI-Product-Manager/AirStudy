package com.projectfocus.service.focus.history

import androidx.room.withTransaction
import com.projectfocus.core.database.AppDatabase
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.*
import com.projectfocus.core.network.record.*
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

data class RecordCloudSyncResult(
    val uploaded: Int = 0,
    val restored: Int = 0,
    val removed: Int = 0,
    val pending: Int = 0,
    val retryable: Boolean = false,
    val authenticationRequired: Boolean = false,
    val obsolete: Boolean = false,
)

/** Download deletions before publishing local records; retain protected changes in a durable inbox. */
@Singleton
class RecordCloudSyncCoordinator @Inject constructor(
    private val db: AppDatabase,
    private val gateway: RecordArchiveGateway,
    private val mutations: RecordCloudMutationCoordinator,
    private val snapshots: RecordCloudSnapshots,
    private val journal: RecordMutationJournal,
    private val inbox: RecordCloudSyncJournal,
    private val importer: RecordCloudArchiveImport,
    private val clock: Clock,
) {
    private val runner = Mutex()

    suspend fun synchronize(): RecordCloudSyncResult = runner.withLock {
        val identity = TokenStore.snapshot()
        if (identity.token.isNullOrBlank()) return@withLock RecordCloudSyncResult()
        val account = HistoryAccount(TokenStore.storageScope(identity), identity.generation)
        var result = RecordCloudSyncResult()
        val failedUploads = HashSet<FlightRecordKey>()
        fun current() = TokenStore.snapshot() == identity
        fun failure(reason: RecordMutationOutcome, conflictCanRefresh: Boolean = false) {
            result = result.copy(
                retryable = result.retryable || reason == RecordMutationOutcome.NetworkFailure || reason == RecordMutationOutcome.InvalidResponse ||
                    reason is RecordMutationOutcome.Rejected && (reason.retryable || conflictCanRefresh && reason.status in setOf(409, 410)),
                authenticationRequired = result.authenticationRequired || reason is RecordMutationOutcome.Rejected && reason.status == 401,
                obsolete = result.obsolete || reason == RecordMutationOutcome.Obsolete)
        }
        suspend fun applyInbox() {
            val entries = TokenStore.withSnapshotLease(identity) { inbox.entries(account.scope) } ?: return
            for (entry in entries) {
                if (!current()) return
                val applied = mutations.serialize { importer.apply(identity, entry) }
                result = when (applied) {
                    RecordCloudImportResult.RESTORED -> result.copy(restored = result.restored + 1)
                    RecordCloudImportResult.REMOVED -> result.copy(removed = result.removed + 1)
                    RecordCloudImportResult.DEFERRED -> result.copy(retryable = true)
                    RecordCloudImportResult.OBSOLETE -> result.copy(obsolete = true)
                    RecordCloudImportResult.UNCHANGED -> result
                }
            }
        }
        val recovered = mutations.recoverPending()
        if (!current()) return@withLock result.copy(obsolete = true)
        result = result.copy(authenticationRequired = recovered.authenticationRequired)
        applyInbox()
        var caughtUp = false
        if (!result.authenticationRequired) {
            for (pageIndex in 0 until 20) {
                if (!current()) break
                val after = TokenStore.withSnapshotLease(identity) { inbox.cursor(account.scope) } ?: break
                when (val reply = gateway.changes(identity, after, 100)) {
                    is RecordArchiveReadResult.Failure -> { failure(reply.reason); break }
                    is RecordArchiveReadResult.Loaded -> {
                        if (TokenStore.withSnapshotLease(identity) {
                            db.withTransaction { inbox.stage(account.scope, after, reply.page, clock.millis()) }; true
                        } != true) break
                        applyInbox()
                        if (!reply.page.hasMore) { caughtUp = true; break }
                        if (pageIndex == 19) result = result.copy(retryable = true)
                    }
                }
            }
        }
        if (caughtUp && current() && !result.authenticationRequired) {
            val keys = TokenStore.withSnapshotLease(identity) { terminalKeys(account) }.orEmpty()
            val incoming = TokenStore.withSnapshotLease(identity) {
                inbox.entries(account.scope).map { it.mode to it.id }.toSet()
            }.orEmpty()
            for (key in keys) {
                if (!current()) break
                mutations.serialize {
                    val capture = TokenStore.withSnapshotLease(identity) {
                        db.withTransaction {
                            val binding = journal.binding(account.scope, key)
                            if (binding?.deleted == true || binding?.archiveStored == true || journal.pending(account.scope, key) != null ||
                                (mode(key) to (binding?.remoteId ?: remoteId(account, key))) in incoming) return@withTransaction null
                            snapshots.capture(account, key, binding?.remoteId)
                        }
                    } ?: return@serialize
                    val binding = TokenStore.withSnapshotLease(identity) { journal.binding(account.scope, key) } ?: run {
                        if (!current()) return@serialize
                        null
                    }
                    val payload = Json.encodeToString(FlightRecordArchiveUpload.serializer(), capture.upload)
                    val operationId = "archive-" + RecordCloudSnapshots.digest(account.scope + "\n" + payload)
                    val outcome = gateway.upload(identity, capture.upload, FlightRecordMutationOptions(operationId, binding?.revision ?: 0L))
                    val acceptedRevision = (outcome as? RecordMutationOutcome.Acknowledged)?.revision
                    if (acceptedRevision != null && acceptedRevision > 0) {
                        val saved = TokenStore.withSnapshotLease(identity) {
                            db.withTransaction {
                                val now = snapshots.capture(account, key, binding?.remoteId)
                                if (now?.fingerprint != capture.fingerprint || journal.pending(account.scope, key) != null ||
                                    journal.binding(account.scope, key) != binding) return@withTransaction false
                                journal.bind(account.scope, key, RecordCloudBinding(capture.upload.archive.id, acceptedRevision, false), clock.millis())
                                true
                            }
                        } == true
                        if (saved) result = result.copy(uploaded = result.uploaded + 1)
                        else { failedUploads.add(key); result = result.copy(retryable = true) }
                    } else {
                        failedUploads.add(key)
                        failure(if (outcome is RecordMutationOutcome.Acknowledged) RecordMutationOutcome.InvalidResponse else outcome, true)
                    }
                }
                if (result.authenticationRequired || result.obsolete) break
            }
        }
        val pending = TokenStore.withSnapshotLease(identity) {
            journal.pending(account.scope).size + inbox.entries(account.scope).size + failedUploads.size
        } ?: result.pending
        result.copy(pending = pending, retryable = result.retryable || recovered.retryable && pending > 0,
            obsolete = result.obsolete || !current())
    }

    private suspend fun terminalKeys(account: HistoryAccount): List<FlightRecordKey> =
        db.flightHistoryDao().getAll().filter { it.ownerScope == account.scope && it.status in setOf("COMPLETED", "CANCELLED") }
            .map { FlightRecordKey(FlightRecordKind.COUNTDOWN, it.id) } +
            db.freeFlightArchiveDao().owned(account.scope).filter { it.activeSlot == null && it.status in setOf("COMPLETED", "CANCELLED") }
                .map { FlightRecordKey(FlightRecordKind.COUNT_UP, it.sessionId) }

    private suspend fun remoteId(account: HistoryAccount, key: FlightRecordKey): String = if (key.kind == FlightRecordKind.COUNTDOWN) key.id
        else db.freeFlightArchiveDao().get(key.id, account.scope)?.let(::decodeHistoryArchive)?.remoteRecordId?.trim()?.takeIf(String::isNotBlank) ?: key.id

    private fun mode(key: FlightRecordKey) = if (key.kind == FlightRecordKind.COUNTDOWN) FlightRecordMode.DOWN else FlightRecordMode.UP
}
