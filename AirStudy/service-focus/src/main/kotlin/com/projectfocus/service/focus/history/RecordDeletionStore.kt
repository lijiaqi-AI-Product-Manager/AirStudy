package com.projectfocus.service.focus.history

import androidx.room.withTransaction
import com.projectfocus.core.database.AppDatabase
import com.projectfocus.core.model.flight.PlannedFlightMetadataCodec
import com.projectfocus.core.model.flight.freeflight.FreeFlightStatus
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.service.focus.session.SessionRepository
import com.projectfocus.service.focus.session.TodayStatsNotifier
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RecordDeletionTarget(val account: HistoryAccount, val record: RecordedFlight)

enum class RecordDeletionResult { DELETED, MISSING, CHANGED, PROTECTED, OBSOLETE }

interface RecordDeletionOperations {
    suspend fun captureCountdown(id: String): RecordDeletionTarget?
    suspend fun delete(target: RecordDeletionTarget): RecordDeletionResult
}

/** Removes only the confirmed account, mode and immutable record snapshot. */
@Singleton
class RecordDeletionStore @Inject constructor(
    private val db: AppDatabase,
    private val sessions: SessionRepository,
    private val notifier: TodayStatsNotifier,
) : RecordDeletionOperations {
    override suspend fun captureCountdown(id: String): RecordDeletionTarget? = withContext(Dispatchers.IO) {
        val identity = TokenStore.snapshot()
        val account = HistoryAccount(TokenStore.storageScope(identity), identity.generation)
        TokenStore.withSnapshotLease(identity) {
            db.withTransaction {
                val row = db.flightHistoryDao().findById(id) ?: return@withTransaction null
                if (row.ownerScope != account.scope && !(account.scope == "local" && row.ownerScope == null))
                    return@withTransaction null
                val key = FlightRecordKey(FlightRecordKind.COUNTDOWN, id)
                RecordDeletionTarget(account, RecordedFlight.Countdown(row, sceneFor(key, account.scope, row.startTimeMillis)))
            }
        }
    }

    override suspend fun delete(target: RecordDeletionTarget): RecordDeletionResult = withContext(Dispatchers.IO) {
        require(target.record.key.id.isNotBlank())
        val identity = TokenStore.snapshot()
        if (!target.account.isCurrent()) return@withContext RecordDeletionResult.OBSOLETE
        val result = TokenStore.withSnapshotLease(identity) {
            if (!target.account.isCurrent()) return@withSnapshotLease RecordDeletionResult.OBSOLETE
            sessions.withHistoryDeletionLease(target.record.key) {
                db.withTransaction { removeConfirmed(target) }
            } ?: RecordDeletionResult.PROTECTED
        } ?: RecordDeletionResult.OBSOLETE
        if (result == RecordDeletionResult.DELETED) notifier.notifyChanged()
        result
    }

    private suspend fun removeConfirmed(target: RecordDeletionTarget): RecordDeletionResult {
        val expected = target.record
        val owner = target.account.scope
        val actual: RecordedFlight = when (expected) {
            is RecordedFlight.Countdown -> {
                val row = db.flightHistoryDao().findById(expected.key.id) ?: return RecordDeletionResult.MISSING
                if (row.ownerScope != owner && !(owner == "local" && row.ownerScope == null))
                    return RecordDeletionResult.OBSOLETE
                if (row.status !in setOf("COMPLETED", "CANCELLED")) return RecordDeletionResult.PROTECTED
                RecordedFlight.Countdown(row, sceneFor(expected.key, owner, row.startTimeMillis))
            }
            is RecordedFlight.CountUp -> {
                val row = db.freeFlightArchiveDao().get(expected.key.id, owner) ?: return RecordDeletionResult.MISSING
                if (row.status !in setOf("COMPLETED", "CANCELLED") || row.activeSlot != null) return RecordDeletionResult.PROTECTED
                if (expected.storedRow == null || row != expected.storedRow) return RecordDeletionResult.CHANGED
                val archive = decodeHistoryArchive(row)
                if (archive.status == FreeFlightStatus.ONGOING) return RecordDeletionResult.PROTECTED
                RecordedFlight.CountUp(archive, sceneFor(expected.key, owner, archive.startedAtWallMs), row)
            }
        }
        if (actual != expected) return RecordDeletionResult.CHANGED
        val removed = when (actual) {
            is RecordedFlight.Countdown -> db.historyDeletionDao().countdown(actual.key.id, owner)
            is RecordedFlight.CountUp -> db.historyDeletionDao().countUp(actual.key.id, owner, actual.archive.revision)
        }
        check(removed == 1) { "The confirmed record was not removed" }
        return RecordDeletionResult.DELETED
    }

    private suspend fun sceneFor(key: FlightRecordKey, owner: String, startedAt: Long): RecordedScene? {
        val row = db.flightRecordSceneDao().find(key.kind.name, key.id) ?: return null
        check(row.ownerScope == owner && row.startedAtWallMs == startedAt) { "The record annotation changed ownership" }
        return RecordedScene(requireNotNull(PlannedFlightMetadataCodec.decodeSceneOrNull(row.sceneJson)), row.revision)
    }
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
abstract class RecordDeletionModule {
    @dagger.Binds abstract fun operations(store: RecordDeletionStore): RecordDeletionOperations
}
