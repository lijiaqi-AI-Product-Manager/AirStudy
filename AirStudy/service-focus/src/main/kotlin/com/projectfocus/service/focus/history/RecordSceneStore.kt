package com.projectfocus.service.focus.history

import androidx.room.withTransaction
import com.projectfocus.core.database.AppDatabase
import com.projectfocus.core.database.entity.FlightRecordSceneEntity
import com.projectfocus.core.model.flight.PlannedFlightMetadataCodec
import com.projectfocus.core.model.flight.PlannedScene
import com.projectfocus.core.model.flight.freeflight.FreeFlightStatus
import com.projectfocus.core.network.di.TokenStore
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

data class RecordSceneEditTarget(
    val account: HistoryAccount,
    val key: FlightRecordKey,
    val startedAtWallMs: Long,
    val expectedRevision: Long,
)

sealed interface RecordSceneSaveResult {
    data class Saved(val revision: Long) : RecordSceneSaveResult
    data object Obsolete : RecordSceneSaveResult
    data object Changed : RecordSceneSaveResult
    data object NotEditable : RecordSceneSaveResult
}

/** Writes a history annotation without rewriting flight checkpoints, receipts or position samples. */
@Singleton
class RecordSceneStore @Inject constructor(private val db: AppDatabase, private val clock: Clock) {
    suspend fun save(target: RecordSceneEditTarget, scene: PlannedScene): RecordSceneSaveResult {
        require(target.expectedRevision >= 0L && target.key.id.isNotBlank())
        require(scene.iconKey.isNotBlank() && (scene.displayName?.isNotBlank() == true || scene.name.isNotBlank()))
        val identity = TokenStore.snapshot()
        if (!target.account.isCurrent()) return RecordSceneSaveResult.Obsolete
        val json = requireNotNull(PlannedFlightMetadataCodec.encodeScene(scene))
        return TokenStore.withSnapshotLease(identity) {
            if (!target.account.isCurrent()) return@withSnapshotLease RecordSceneSaveResult.Obsolete
            db.withTransaction {
                val start = when (target.key.kind) {
                    FlightRecordKind.COUNTDOWN -> {
                        val record = db.flightHistoryDao().findById(target.key.id)
                            ?: return@withTransaction RecordSceneSaveResult.NotEditable
                        val owned = record.ownerScope == target.account.scope ||
                            (target.account.scope == "local" && record.ownerScope == null)
                        if (!owned || record.status != "COMPLETED") return@withTransaction RecordSceneSaveResult.NotEditable
                        record.startTimeMillis
                    }
                    FlightRecordKind.COUNT_UP -> {
                        val row = db.freeFlightArchiveDao().get(target.key.id, target.account.scope)
                            ?: return@withTransaction RecordSceneSaveResult.NotEditable
                        val archive = decodeHistoryArchive(row)
                        if (archive.status != FreeFlightStatus.COMPLETED) return@withTransaction RecordSceneSaveResult.NotEditable
                        archive.startedAtWallMs
                    }
                }
                if (start != target.startedAtWallMs) return@withTransaction RecordSceneSaveResult.Changed
                val dao = db.flightRecordSceneDao()
                val current = dao.find(target.key.kind.name, target.key.id)
                if (current != null && (current.ownerScope != target.account.scope || current.startedAtWallMs != start))
                    return@withTransaction RecordSceneSaveResult.Changed
                if ((current?.revision ?: 0L) != target.expectedRevision) return@withTransaction RecordSceneSaveResult.Changed
                if (current?.sceneJson == json) return@withTransaction RecordSceneSaveResult.Saved(current.revision)
                val revision = Math.addExact(target.expectedRevision, 1L)
                val wall = maxOf(clock.millis(), current?.updatedAtWallMs ?: Long.MIN_VALUE)
                if (current == null) {
                    dao.insert(FlightRecordSceneEntity(target.key.kind.name, target.key.id, target.account.scope,
                        target.key.id.takeIf { target.key.kind == FlightRecordKind.COUNTDOWN },
                        target.key.id.takeIf { target.key.kind == FlightRecordKind.COUNT_UP },
                        start, revision, json, wall))
                } else {
                    check(dao.update(target.key.kind.name, target.key.id, target.account.scope, start,
                        target.expectedRevision, revision, json, wall) == 1)
                }
                RecordSceneSaveResult.Saved(revision)
            }
        } ?: RecordSceneSaveResult.Obsolete
    }
}
