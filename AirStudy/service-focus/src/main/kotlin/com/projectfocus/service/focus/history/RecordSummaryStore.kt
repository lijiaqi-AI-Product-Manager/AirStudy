package com.projectfocus.service.focus.history

import androidx.room.withTransaction
import com.projectfocus.core.database.AppDatabase
import com.projectfocus.core.database.entity.AppSettingEntity
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

data class RecordSummaryState(val account: HistoryAccount? = null,
    val records: List<FlightRecordSummary> = emptyList(),
    val aliases: Map<FlightRecordKey, Pair<FlightRecordMode, String>> = emptyMap(),
    val complete: Boolean = true, val totalCount: Int? = null, val failed: Boolean = false)

@Singleton
class RecordSummaryStore @Inject constructor(private val db: AppDatabase, private val journal: RecordMutationJournal) {
    private fun key(scope: String) = "record.cloud.summaries.v1." + RecordCloudSnapshots.digest(scope)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<RecordSummaryState> = TokenStore.identities.flatMapLatest { identity ->
        if (identity.token.isNullOrBlank()) flowOf(RecordSummaryState())
        else {
            val account = HistoryAccount(TokenStore.storageScope(identity), identity.generation)
            db.appSettingDao().observeValue(key(account.scope)).map { text ->
                if (!account.isCurrent()) RecordSummaryState()
                else try { decode(account, text) }
                catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    RecordSummaryState(account, failed = true)
                }
            }.catch { failure ->
                if (failure is CancellationException) throw failure
                emit(if (account.isCurrent()) RecordSummaryState(account, failed = true) else RecordSummaryState())
            }
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    internal suspend fun replace(identity: TokenStore.Snapshot, records: List<FlightRecordSummary>, complete: Boolean,
        totalCount: Int?, wall: Long): Boolean = TokenStore.withSnapshotLease(identity) {
        db.withTransaction {
            require(records.size <= 2_000 && records.map { it.mode to it.id }.toSet().size == records.size)
            require(totalCount == null || totalCount >= records.size)
            val scope = TokenStore.storageScope(identity)
            val previous = try { current(scope).associateBy { it.mode to it.id } }
                catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    emptyMap()
                }
            val kept = records.map { record ->
                val old = previous[record.mode to record.id]
                FlightRecordSummary.parse(buildJsonObject {
                    record.raw.forEach { (name, value) -> put(name, value) }
                    if (record.flightNumber == null) old?.flightNumber?.let { put("flightNumber", it) }
                    if (record.seat == null) old?.seat?.let { put("seatNumber", it) }
                })
            }
            val body = buildJsonObject {
                put("version", 1); put("scope", scope); put("complete", complete)
                totalCount?.let { put("totalCount", it) }
                put("records", JsonArray(kept.map { it.raw }))
            }.toString()
            require(body.toByteArray(Charsets.UTF_8).size <= 8 * 1024 * 1024)
            db.appSettingDao().put(AppSettingEntity(key(scope), body, wall))
            true
        }
    } == true

    internal suspend fun current(scope: String): List<FlightRecordSummary> =
        decode(HistoryAccount(scope, TokenStore.snapshot().generation), db.appSettingDao().get(key(scope))).records

    private suspend fun decode(account: HistoryAccount, text: String?): RecordSummaryState {
        val bindings = journal.bindings(account.scope)
        val removed = bindings.filter { it.binding.deleted }.map { mode(it.key.kind) to it.binding.remoteId }.toSet()
        val aliases = bindings.associate { it.key to (mode(it.key.kind) to it.binding.remoteId) }
        if (text == null) return RecordSummaryState(account, aliases = aliases, complete = false)
        val body = Json.parseToJsonElement(text).jsonObject
        require(body.getValue("version").jsonPrimitive.int == 1 && body.getValue("scope").jsonPrimitive.content == account.scope)
        val array = body.getValue("records").jsonArray; require(array.size <= 2_000)
        val values = array.map { FlightRecordSummary.parse(it.jsonObject) }
        require(values.map { it.mode to it.id }.toSet().size == values.size)
        val total = (body["totalCount"] as? JsonPrimitive)?.intOrNull
        require(total == null || total >= values.size)
        return RecordSummaryState(account, values.filter { (it.mode to it.id) !in removed }, aliases,
            body.getValue("complete").jsonPrimitive.boolean, total)
    }

    private fun mode(kind: FlightRecordKind) = if (kind == FlightRecordKind.COUNTDOWN) FlightRecordMode.DOWN else FlightRecordMode.UP
}
