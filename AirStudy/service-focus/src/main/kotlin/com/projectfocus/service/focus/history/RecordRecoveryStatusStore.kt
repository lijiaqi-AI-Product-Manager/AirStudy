package com.projectfocus.service.focus.history

import androidx.room.withTransaction
import com.projectfocus.core.database.AppDatabase
import com.projectfocus.core.database.entity.AppSettingEntity
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.record.RecordSceneWireProjection
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import org.json.JSONObject

data class RecordRecoveryStatus(
    val account: HistoryAccount? = null,
    val missingArchives: Int = 0,
    val unsupportedScenes: Int = 0,
    val protectedRecords: Int = 0,
    val pendingChanges: Int = 0,
    val pendingUploads: Int = 0,
    val authenticationRequired: Boolean = false,
    val unavailable: Boolean = false,
    val syncing: Boolean = false,
) {
    val pendingCount get() = missingArchives + unsupportedScenes + protectedRecords + pendingChanges + pendingUploads
    val visible get() = account?.isCurrent() == true && (pendingCount > 0 || authenticationRequired || unavailable)
}

/** A small account-bound summary keeps recovery feedback independent of large archive payloads. */
@Singleton
class RecordRecoveryStatusStore @Inject constructor(
    private val db: AppDatabase,
    private val mutations: RecordMutationJournal,
    private val inbox: RecordCloudSyncJournal,
    private val scenes: RecordSceneWireProjection,
    private val clock: Clock,
    private val summaries: RecordSummaryMutationJournal? = null,
) {
    private val running = MutableStateFlow<TokenStore.Snapshot?>(null)
    private fun key(scope: String) = "record.cloud.status.v1." + RecordCloudSnapshots.digest(scope)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<RecordRecoveryStatus> = TokenStore.identities.flatMapLatest { identity ->
        if (identity.token.isNullOrBlank()) flowOf(RecordRecoveryStatus())
        else {
            val account = HistoryAccount(TokenStore.storageScope(identity), identity.generation)
            combine(db.appSettingDao().observeValue(key(account.scope)), running) { text, active ->
                if (!account.isCurrent()) RecordRecoveryStatus()
                else {
                    val status = try { if (text == null) RecordRecoveryStatus(account) else decode(account, text) }
                    catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        RecordRecoveryStatus(account, unavailable = true)
                    }
                    status.copy(syncing = active == identity)
                }
            }.catch { failure ->
                if (failure is CancellationException) throw failure
                emit(if (account.isCurrent()) RecordRecoveryStatus(account, unavailable = true) else RecordRecoveryStatus())
            }
        }
    }.distinctUntilChanged()

    internal suspend fun update(identity: TokenStore.Snapshot, result: RecordCloudSyncResult?, syncing: Boolean,
        failed: Boolean = false) {
        if (identity.token.isNullOrBlank()) return
        if (syncing && TokenStore.snapshot() == identity) running.value = identity
        else if (!syncing) running.compareAndSet(identity, null)
        TokenStore.withSnapshotLease(identity) {
            db.withTransaction {
                val scope = TokenStore.storageScope(identity)
                val entries = inbox.entries(scope)
                val pending = mutations.pending(scope)
                val summaryPending = summaries?.pending(scope).orEmpty()
                val previous = db.appSettingDao().get(key(scope))?.let {
                    try { decode(HistoryAccount(scope, identity.generation), it) } catch (_: Exception) { null }
                }
                val represented = entries.map { it.mode to it.id }.toSet()
                var missing = 0; var unsupported = 0; var protectedCount = 0
                for (entry in entries) {
                    val scene = entry.scene
                    when {
                        entry.deleted -> protectedCount++
                        entry.archive == null -> missing++
                        scene != null && scenes.restore(scene) == null -> unsupported++
                        else -> protectedCount++
                    }
                }
                val changes = pending.count {
                    val mode = if (it.key.kind == FlightRecordKind.COUNTDOWN) com.projectfocus.core.network.dto.FlightRecordMode.DOWN
                        else com.projectfocus.core.network.dto.FlightRecordMode.UP
                    (mode to it.remoteId) !in represented
                } + summaryPending.count { (it.summary.mode to it.summary.id) !in represented }
                val uploads = if (syncing && result == null) previous?.pendingUploads ?: 0
                    else ((result?.pending ?: 0) - entries.size - pending.size - summaryPending.size).coerceAtLeast(0)
                val authenticationRequired = result?.authenticationRequired ?: (syncing && previous?.authenticationRequired == true)
                val unavailable = failed || (if (syncing && result == null) previous?.unavailable == true
                    else result?.retryable == true && entries.isEmpty() && pending.isEmpty() && summaryPending.isEmpty() && uploads == 0)
                val value = JSONObject().put("version", 1).put("scope", scope).put("missingArchives", missing)
                    .put("unsupportedScenes", unsupported).put("protectedRecords", protectedCount).put("pendingChanges", changes)
                    .put("pendingUploads", uploads).put("authenticationRequired", authenticationRequired)
                    .put("unavailable", unavailable)
                db.appSettingDao().put(AppSettingEntity(key(scope), value.toString(), clock.millis()))
            }
        }
    }

    private fun decode(account: HistoryAccount, text: String): RecordRecoveryStatus {
        val value = JSONObject(text)
        require(value.getInt("version") == 1 && value.getString("scope") == account.scope)
        fun count(name: String) = value.getInt(name).also { require(it in 0..1_000_000) }
        return RecordRecoveryStatus(account, count("missingArchives"), count("unsupportedScenes"), count("protectedRecords"),
            count("pendingChanges"), count("pendingUploads"), value.getBoolean("authenticationRequired"),
            value.getBoolean("unavailable"))
    }
}
