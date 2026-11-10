package com.projectfocus.service.focus.history

import androidx.room.withTransaction
import com.projectfocus.core.database.AppDatabase
import com.projectfocus.core.model.flight.PlannedScene
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.*
import com.projectfocus.core.network.record.*
import com.projectfocus.service.focus.session.SessionRepository
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class RecordSummaryActionTarget(val account: HistoryAccount, val summary: FlightRecordSummary)
enum class RecordSummaryActionResult { APPLIED, MISSING, CHANGED, PROTECTED, OBSOLETE }

interface RecordSummaryOperations {
    suspend fun saveSummaryScene(target: RecordSummaryActionTarget, scene: PlannedScene): RecordSummaryActionResult
    suspend fun deleteSummary(target: RecordSummaryActionTarget): RecordSummaryActionResult
}

/** Invoked under the shared record writer; summaries never become synthetic resumable archives. */
@Singleton
class RecordSummaryMutations @Inject constructor(
    private val db: AppDatabase,
    private val gateway: RecordMutationGateway,
    private val store: RecordSummaryStore,
    private val pending: RecordSummaryMutationJournal,
    private val bindings: RecordMutationJournal,
    private val sessions: SessionRepository,
    private val clock: Clock,
) {
    internal suspend fun execute(target: RecordSummaryActionTarget, scene: FlightRecordScenePatch?): RecordSummaryActionResult {
        val identity = TokenStore.snapshot()
        if (!target.account.isCurrent()) return RecordSummaryActionResult.OBSOLETE
        if (identity.token.isNullOrBlank()) throw RecordCloudFailure(RecordMutationOutcome.Rejected(401))
        if (!editable(target.summary, scene != null)) return RecordSummaryActionResult.PROTECTED
        var entry: PendingSummaryMutation? = null
        val prepared = guarded(identity, target) {
            val actual = store.current(target.account.scope).singleOrNull { it.mode == target.summary.mode && it.id == target.summary.id }
                ?: return@guarded if (bindings.bindingForRemote(target.account.scope, kind(target.summary.mode), target.summary.id)?.binding?.deleted == true)
                    RecordSummaryActionResult.MISSING else RecordSummaryActionResult.CHANGED
            if (actual.raw != target.summary.raw) return@guarded RecordSummaryActionResult.CHANGED
            val previous = pending.pending(target.account.scope, actual.mode, actual.id)
            if (previous != null) {
                if (previous.summary.raw != actual.raw || previous.scene != scene)
                    throw RecordCloudFailure(RecordMutationOutcome.Rejected(409))
                entry = previous
            } else {
                val bound = bindings.bindingForRemote(target.account.scope, kind(actual.mode), actual.id)
                val revision = maxOf(actual.revision ?: 0L, bound?.binding?.revision ?: 0L)
                entry = PendingSummaryMutation(target.account.scope, actual, UUID.randomUUID().toString(), revision, scene)
                    .also { pending.put(it, clock.millis()) }
            }
            RecordSummaryActionResult.APPLIED
        }
        if (prepared != RecordSummaryActionResult.APPLIED) return prepared
        val confirmed = acknowledge(identity, requireNotNull(entry)) ?: return RecordSummaryActionResult.OBSOLETE
        return apply(identity, confirmed)
    }

    internal suspend fun count(scope: String) = pending.pending(scope).size

    internal suspend fun recover(identity: TokenStore.Snapshot): RecordMutationRecovery {
        val account = HistoryAccount(TokenStore.storageScope(identity), identity.generation)
        val entries = TokenStore.withSnapshotLease(identity) { pending.pending(account.scope) }
            ?: return RecordMutationRecovery(0, 0)
        var completed = 0; var retryable = false; var authentication = false
        for (entry in entries) {
            if (!account.isCurrent()) break
            val target = RecordSummaryActionTarget(account, entry.summary)
            val safe = guarded(identity, target) {
                val actual = store.current(account.scope).singleOrNull { it.mode == entry.summary.mode && it.id == entry.summary.id }
                if (!entry.acknowledged && actual?.raw != entry.summary.raw) RecordSummaryActionResult.CHANGED
                else if (actual != null && !editable(actual, entry.scene != null)) RecordSummaryActionResult.PROTECTED
                else RecordSummaryActionResult.APPLIED
            }
            if (safe != RecordSummaryActionResult.APPLIED) continue
            val confirmed = try { acknowledge(identity, entry) ?: break }
                catch (failure: RecordCloudFailure) {
                    val reason = failure.reason
                    if (reason is RecordMutationOutcome.Rejected && reason.status == 401) { authentication = true; break }
                    if (reason == RecordMutationOutcome.NetworkFailure || reason == RecordMutationOutcome.InvalidResponse ||
                        reason is RecordMutationOutcome.Rejected && reason.retryable) retryable = true
                    continue
                }
            if (apply(identity, confirmed) == RecordSummaryActionResult.APPLIED) completed++
        }
        val remaining = TokenStore.withSnapshotLease(identity) { pending.pending(account.scope).size } ?: entries.size - completed
        return RecordMutationRecovery(completed, remaining, retryable, authentication)
    }

    private suspend fun acknowledge(identity: TokenStore.Snapshot, entry: PendingSummaryMutation): PendingSummaryMutation? {
        if (TokenStore.snapshot() != identity) return null
        if (entry.acknowledged) return entry
        val options = FlightRecordMutationOptions(entry.operationId, entry.expectedRevision)
        val reply = if (entry.scene == null) gateway.delete(identity, entry.summary.mode, entry.summary.id, options)
            else gateway.changeScene(identity, entry.summary.mode, entry.summary.id, entry.scene, options)
        val receipt = when (reply) {
            is RecordMutationOutcome.Acknowledged -> reply
            RecordMutationOutcome.Obsolete -> return null
            else -> throw RecordCloudFailure(reply)
        }
        return TokenStore.withSnapshotLease(identity) {
            db.withTransaction {
                check(pending.pending(entry.scope, entry.summary.mode, entry.summary.id)?.operationId == entry.operationId)
                entry.copy(acknowledged = true, acceptedRevision = receipt.revision).also { pending.put(it, clock.millis()) }
            }
        }
    }

    private suspend fun apply(identity: TokenStore.Snapshot, entry: PendingSummaryMutation): RecordSummaryActionResult {
        val target = RecordSummaryActionTarget(HistoryAccount(entry.scope, identity.generation), entry.summary)
        return guarded(identity, target) {
            val stored = requireNotNull(pending.pending(entry.scope, entry.summary.mode, entry.summary.id))
            check(stored.operationId == entry.operationId && stored.acknowledged)
            val actual = store.current(entry.scope).singleOrNull { it.mode == entry.summary.mode && it.id == entry.summary.id }
            if (actual != null && !editable(actual, entry.scene != null)) return@guarded RecordSummaryActionResult.PROTECTED
            val old = bindings.bindingForRemote(entry.scope, kind(entry.summary.mode), entry.summary.id)
            val key = old?.key ?: FlightRecordKey(kind(entry.summary.mode), "cloud-summary-${entry.summary.mode.path}-" +
                RecordCloudSnapshots.digest(entry.scope + "\n" + entry.summary.id).take(40))
            bindings.bind(entry.scope, key, RecordCloudBinding(entry.summary.id, entry.acceptedRevision,
                entry.scene == null, old?.binding?.archiveStored ?: false), clock.millis())
            if (entry.scene != null) pending.rememberScene(entry, clock.millis())
            pending.remove(entry)
            RecordSummaryActionResult.APPLIED
        }
    }

    private suspend fun guarded(identity: TokenStore.Snapshot, target: RecordSummaryActionTarget,
        action: suspend () -> RecordSummaryActionResult): RecordSummaryActionResult = TokenStore.withSnapshotLease(identity) {
        val kind = kind(target.summary.mode)
        val bound = bindings.bindingForRemote(target.account.scope, kind, target.summary.id)
        val key = bound?.key ?: FlightRecordKey(kind, target.summary.id)
        sessions.withHistoryDeletionLeases(listOf(key, FlightRecordKey(kind, target.summary.id)).distinct()) {
            db.withTransaction {
                val records = if (kind == FlightRecordKind.COUNTDOWN) {
                    db.flightHistoryDao().getAll().filter { it.ownerScope == target.account.scope && (it.id == key.id || it.id == target.summary.id) }
                        .map { it.status !in setOf("COMPLETED", "CANCELLED") }
                } else db.freeFlightArchiveDao().owned(target.account.scope).filter {
                    it.sessionId == key.id || it.sessionId == target.summary.id || decodeHistoryArchive(it).remoteRecordId == target.summary.id
                }.map { it.activeSlot != null || it.status !in setOf("COMPLETED", "CANCELLED") }
                when {
                    records.any { it } -> RecordSummaryActionResult.PROTECTED
                    records.isNotEmpty() -> RecordSummaryActionResult.CHANGED
                    else -> action()
                }
            }
        } ?: RecordSummaryActionResult.PROTECTED
    } ?: RecordSummaryActionResult.OBSOLETE

    private fun kind(mode: FlightRecordMode) = if (mode == FlightRecordMode.DOWN) FlightRecordKind.COUNTDOWN else FlightRecordKind.COUNT_UP
    private fun editable(summary: FlightRecordSummary, scene: Boolean) = summary.status == SummaryFlightStatus.ARRIVED ||
        !scene && summary.status == SummaryFlightStatus.CANCELLED
}
