package com.projectfocus.service.focus.history

import androidx.room.withTransaction
import com.projectfocus.core.database.AppDatabase
import com.projectfocus.core.database.entity.*
import com.projectfocus.core.model.flight.PlannedFlightMetadataCodec
import com.projectfocus.core.model.flight.PlannedScene
import com.projectfocus.core.model.flight.freeflight.FreeFlightArchiveCodec
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.*
import com.projectfocus.core.network.record.RecordSceneWireProjection
import com.projectfocus.service.focus.session.TodayStatsNotifier
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

internal enum class RecordCloudImportResult { RESTORED, REMOVED, UNCHANGED, DEFERRED, OBSOLETE }

/** Restores terminal snapshots without replacing another account's rows or a live runtime archive. */
@Singleton
class RecordCloudArchiveImport @Inject constructor(
    private val db: AppDatabase,
    private val snapshots: RecordCloudSnapshots,
    private val journal: RecordMutationJournal,
    private val inbox: RecordCloudSyncJournal,
    private val deletion: RecordDeletionStore,
    private val scenes: RecordSceneWireProjection,
    private val notifier: TodayStatsNotifier,
    private val clock: Clock,
) {
    internal suspend fun apply(identity: TokenStore.Snapshot, remote: CloudFlightRecord): RecordCloudImportResult {
        val account = HistoryAccount(TokenStore.storageScope(identity), identity.generation)
        val located = TokenStore.withSnapshotLease(identity) { db.withTransaction { locate(account, remote) } }
            ?: return RecordCloudImportResult.OBSOLETE
        val key = located.key
        if (remote.deleted) return applyDeletion(identity, account, key, remote)
        val result = TokenStore.withSnapshotLease(identity) {
            db.withTransaction {
                val currentBinding = journal.binding(account.scope, key)
                if (currentBinding != null && currentBinding.remoteId != remote.id) return@withTransaction RecordCloudImportResult.DEFERRED
                if ((currentBinding?.revision ?: -1L) >= remote.revision) {
                    inbox.remove(account.scope, remote)
                    return@withTransaction RecordCloudImportResult.UNCHANGED
                }
                if (currentBinding?.deleted == true)
                    return@withTransaction RecordCloudImportResult.DEFERRED
                val pending = journal.pending(account.scope, key)
                val raw = rowExists(key)
                val actual = snapshots.capture(account, key, remote.id)
                if (raw && actual == null) return@withTransaction RecordCloudImportResult.DEFERRED
                val archive = remote.archive
                if (archive == null && actual == null) return@withTransaction RecordCloudImportResult.DEFERRED
                if (archive != null && actual != null && align(archive, key) != align(actual.upload.archive, key))
                    return@withTransaction RecordCloudImportResult.DEFERRED
                if (pending != null && (pending.acknowledged || pending.expectedRemoteRevision == null ||
                        remote.revision <= pending.expectedRemoteRevision || pending.fingerprint != actual?.fingerprint ||
                        pending.remoteId != remote.id || archive == null)) return@withTransaction RecordCloudImportResult.DEFERRED
                val scene = if (pending?.desiredSceneJson != null && pending.scenePatch == remote.scene)
                    PlannedFlightMetadataCodec.decodeSceneOrNull(pending.desiredSceneJson) else remote.scene?.let(scenes::restore)
                if (remote.scene != null && scene == null) return@withTransaction RecordCloudImportResult.DEFERRED
                if (actual == null) insert(account, key, requireNotNull(archive))
                val start = actual?.record?.recordedAtWallMs ?: when (remote.mode) {
                    FlightRecordMode.DOWN -> requireNotNull(archive?.countdown).startTimeMillis
                    FlightRecordMode.UP -> requireNotNull(archive?.countUp).startedAtWallMs
                }
                val effective = actual ?: snapshots.capture(account, key, remote.id)
                if (scene != null && effective?.upload?.scene != remote.scene) annotate(account, key, start, scene)
                journal.bind(account.scope, key, RecordCloudBinding(remote.id, remote.revision, false,
                    archive != null || currentBinding?.archiveStored == true), clock.millis())
                if (pending != null) journal.remove(pending)
                inbox.remove(account.scope, remote)
                RecordCloudImportResult.RESTORED
            }
        } ?: RecordCloudImportResult.OBSOLETE
        if (result == RecordCloudImportResult.RESTORED) notifier.notifyChanged()
        return result
    }

    private suspend fun applyDeletion(identity: TokenStore.Snapshot, account: HistoryAccount, key: FlightRecordKey,
        remote: CloudFlightRecord): RecordCloudImportResult {
        var early: RecordCloudImportResult? = null
        var pending: PendingRecordMutation? = null
        val actual = TokenStore.withSnapshotLease(identity) {
            db.withTransaction {
                val binding = journal.binding(account.scope, key)
                pending = journal.pending(account.scope, key)
                when {
                    binding != null && binding.remoteId != remote.id -> early = RecordCloudImportResult.DEFERRED
                    (binding?.revision ?: -1L) >= remote.revision -> {
                        inbox.remove(account.scope, remote); early = RecordCloudImportResult.UNCHANGED
                    }
                    pending?.let { it.remoteId != remote.id || (it.acceptedRevision ?: -1L) > remote.revision } == true ->
                        early = RecordCloudImportResult.DEFERRED
                    else -> {
                        val capture = snapshots.capture(account, key, remote.id)
                        if (capture != null) {
                            if (pending != null && pending?.fingerprint != capture.fingerprint) early = RecordCloudImportResult.DEFERRED
                            else return@withTransaction capture
                        }
                        if (early != null) return@withTransaction null
                        if (rowExists(key)) early = RecordCloudImportResult.DEFERRED
                        else { finishDeletion(account, key, remote); early = RecordCloudImportResult.UNCHANGED }
                    }
                }
                null
            }
        }
        if (!account.isCurrent()) return RecordCloudImportResult.OBSOLETE
        early?.let { return it }
        if (actual == null) return RecordCloudImportResult.OBSOLETE
        val result = deletion.deleteAtomically(RecordDeletionTarget(account, actual.record),
            { journal.pending(account.scope, key)?.operationId == pending?.operationId &&
                snapshots.capture(account, key, remote.id)?.fingerprint == actual.fingerprint },
            { finishDeletion(account, key, remote) })
        return when (result) {
            RecordDeletionResult.DELETED -> RecordCloudImportResult.REMOVED
            RecordDeletionResult.MISSING -> RecordCloudImportResult.UNCHANGED
            RecordDeletionResult.OBSOLETE -> RecordCloudImportResult.OBSOLETE
            else -> RecordCloudImportResult.DEFERRED
        }
    }

    private suspend fun finishDeletion(account: HistoryAccount, key: FlightRecordKey, remote: CloudFlightRecord) {
        journal.bind(account.scope, key, RecordCloudBinding(remote.id, remote.revision, true), clock.millis())
        journal.pending(account.scope, key)?.let { journal.remove(it) }
        inbox.remove(account.scope, remote)
    }

    private suspend fun locate(account: HistoryAccount, remote: CloudFlightRecord): BoundCloudRecord {
        val kind = if (remote.mode == FlightRecordMode.DOWN) FlightRecordKind.COUNTDOWN else FlightRecordKind.COUNT_UP
        journal.bindingForRemote(account.scope, kind, remote.id)?.let { return it }
        val preferredId = if (kind == FlightRecordKind.COUNT_UP) remote.archive?.countUp?.sessionId ?: remote.id else remote.id
        val direct = FlightRecordKey(kind, preferredId)
        val directBinding = journal.binding(account.scope, direct)
        val directOwner = when (kind) {
            FlightRecordKind.COUNTDOWN -> db.flightHistoryDao().findById(preferredId)?.ownerScope
            FlightRecordKind.COUNT_UP -> db.freeFlightArchiveDao().getById(preferredId)?.ownerScope
        }
        if (directOwner == account.scope && (directBinding == null || directBinding.remoteId == remote.id))
            return BoundCloudRecord(direct, directBinding ?: RecordCloudBinding(remote.id, null, false, false))
        // Older bindings omitted their local key; recover them from this account's real rows.
        val candidates = if (kind == FlightRecordKind.COUNTDOWN)
            db.flightHistoryDao().getAll().filter { it.ownerScope == account.scope }.map { FlightRecordKey(kind, it.id) }
        else db.freeFlightArchiveDao().owned(account.scope).map { FlightRecordKey(kind, it.sessionId) }
        for (candidate in candidates) {
            val binding = journal.binding(account.scope, candidate)
            if (binding?.remoteId == remote.id) return BoundCloudRecord(candidate, binding)
            if (binding == null && (candidate.id == remote.id || kind == FlightRecordKind.COUNT_UP &&
                    decodeHistoryArchive(requireNotNull(db.freeFlightArchiveDao().get(candidate.id, account.scope))).remoteRecordId == remote.id))
                return BoundCloudRecord(candidate, RecordCloudBinding(remote.id, null, false, false))
        }
        val key = if (!rowExists(direct) && journal.binding(account.scope, direct)?.let { it.remoteId != remote.id } != true) direct
            else FlightRecordKey(kind, "cloud-${remote.mode.path}-" + RecordCloudSnapshots.digest(account.scope + "\n" + remote.id).take(40))
        return BoundCloudRecord(key, journal.binding(account.scope, key) ?: RecordCloudBinding(remote.id, null, false, false))
    }

    private suspend fun rowExists(key: FlightRecordKey): Boolean = when (key.kind) {
        FlightRecordKind.COUNTDOWN -> db.flightHistoryDao().findById(key.id) != null
        FlightRecordKind.COUNT_UP -> db.freeFlightArchiveDao().getById(key.id) != null
    }

    private suspend fun insert(account: HistoryAccount, key: FlightRecordKey, snapshot: FlightRecordArchiveSnapshot) {
        snapshot.validated()
        if (snapshot.mode == FlightRecordMode.DOWN) {
            val value = requireNotNull(snapshot.countdown)
            db.flightHistoryDao().insert(FlightHistoryEntity(
                id = key.id, roomId = value.roomId, focusType = value.focusType, roomParticipantCount = value.roomParticipantCount,
                departureAirportCode = value.departure.code, departureAirportName = value.departure.name,
                departureCity = value.departure.city, departureCountry = value.departure.country,
                departureLatitude = value.departure.latitude, departureLongitude = value.departure.longitude,
                destinationAirportCode = value.destination.code, destinationAirportName = value.destination.name,
                destinationCity = value.destination.city, destinationCountry = value.destination.country,
                destinationLatitude = value.destination.latitude, destinationLongitude = value.destination.longitude,
                startTimeMillis = value.startTimeMillis, endTimeMillis = value.endTimeMillis,
                durationMinutes = value.durationMinutes, actualFocusMinutes = value.actualFocusMinutes, distanceKm = value.distanceKm,
                status = value.status, categoryId = null, seat = value.seat,
                hasAnnouncedDeparture = value.hasAnnouncedDeparture, hasAnnouncedDescent = value.hasAnnouncedDescent,
                hasAnnouncedArrival = value.hasAnnouncedArrival, logJson = value.logJson,
                isSynced = true, createdAt = value.createdAt, updatedAt = value.updatedAt,
                quote = value.quote, quoteAuthor = value.quoteAuthor, syncedAt = clock.millis(),
                sceneSnapshotJson = value.sceneSnapshotJson, plannedDepartureJson = value.plannedDepartureJson, ownerScope = account.scope))
        } else {
            val value = requireNotNull(snapshot.countUp).copy(sessionId = key.id, ownerScope = account.scope,
                shieldingCategoryId = null, remoteRecordId = snapshot.id).validated()
            check(db.freeFlightArchiveDao().insertArchive(FreeFlightArchiveEntity(key.id, account.scope,
                value.revision, value.status.name, null, value.updatedAtWallMs, FreeFlightArchiveCodec.encode(value))) != -1L)
            for (sample in snapshot.positions) db.freeFlightArchiveDao().insertPosition(FreeFlightPositionEntity(key.id,
                sample.sequence, sample.activeElapsedMs, sample.point.latitude, sample.point.longitude,
                sample.bearingDegrees, sample.cumulativeDistanceKm, sample.wallMs))
        }
    }

    private suspend fun annotate(account: HistoryAccount, key: FlightRecordKey, start: Long, scene: PlannedScene) {
        val dao = db.flightRecordSceneDao(); val existing = dao.find(key.kind.name, key.id)
        val text = requireNotNull(PlannedFlightMetadataCodec.encodeScene(scene))
        if (existing != null) {
            check(existing.ownerScope == account.scope && existing.startedAtWallMs == start)
            if (existing.sceneJson != text) check(dao.update(key.kind.name, key.id, account.scope, start,
                existing.revision, Math.addExact(existing.revision, 1), text, clock.millis()) == 1)
        } else dao.insert(FlightRecordSceneEntity(key.kind.name, key.id, account.scope,
            key.id.takeIf { key.kind == FlightRecordKind.COUNTDOWN }, key.id.takeIf { key.kind == FlightRecordKind.COUNT_UP },
            start, 1, text, clock.millis()))
    }

    private fun align(snapshot: FlightRecordArchiveSnapshot, key: FlightRecordKey): FlightRecordArchiveSnapshot =
        snapshot.withoutDeviceOwnership().let { value -> value.copy(
            countdown = value.countdown?.let { down -> down.copy(
                departure = down.departure.copy(id = 0, displayName = down.departure.name, displayCity = down.departure.city),
                destination = down.destination.copy(id = 0, displayName = down.destination.name, displayCity = down.destination.city)) },
            countUp = value.countUp?.copy(sessionId = key.id, remoteRecordId = value.id),
            positions = value.positions.map { it.copy(sessionId = key.id) }) }
}
