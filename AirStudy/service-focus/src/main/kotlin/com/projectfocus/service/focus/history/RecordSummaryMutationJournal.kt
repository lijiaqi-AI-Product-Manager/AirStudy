package com.projectfocus.service.focus.history

import com.projectfocus.core.database.AppDatabase
import com.projectfocus.core.database.entity.AppSettingEntity
import com.projectfocus.core.network.dto.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject

internal data class PendingSummaryMutation(val scope: String, val summary: FlightRecordSummary,
    val operationId: String, val expectedRevision: Long, val scene: FlightRecordScenePatch?,
    val acknowledged: Boolean = false, val acceptedRevision: Long? = null)

internal data class SummarySceneReceipt(val mode: FlightRecordMode, val id: String,
    val scene: FlightRecordScenePatch, val revision: Long?, val operationId: String)

/** Keeps an account-bound choice and its acknowledgement without storing credentials or flight checkpoints. */
@Singleton
class RecordSummaryMutationJournal @Inject constructor(private val db: AppDatabase) {
    private fun prefix(scope: String) = "record.summary.pending.v1." + RecordCloudSnapshots.digest(scope) + "."
    private fun suffix(mode: FlightRecordMode, id: String) = mode.path + "." + RecordCloudSnapshots.digest(id)
    private fun key(scope: String, mode: FlightRecordMode, id: String) = prefix(scope) + suffix(mode, id)
    private fun scenePrefix(scope: String) = "record.summary.scene.v1." + RecordCloudSnapshots.digest(scope) + "."

    internal suspend fun pending(scope: String): List<PendingSummaryMutation> =
        db.appSettingDao().withPrefix(prefix(scope)).map { row -> decode(requireNotNull(row.value)).also {
            check(it.scope == scope && row.key == key(scope, it.summary.mode, it.summary.id))
        } }

    internal suspend fun pending(scope: String, mode: FlightRecordMode, id: String): PendingSummaryMutation? =
        db.appSettingDao().get(key(scope, mode, id))?.let(::decode)?.also {
            check(it.scope == scope && it.summary.mode == mode && it.summary.id == id)
        }

    internal suspend fun put(entry: PendingSummaryMutation, wall: Long) {
        val body = JSONObject().put("version", 1).put("scope", entry.scope).put("summary", entry.summary.raw.toString())
            .put("operationId", entry.operationId).put("expectedRevision", entry.expectedRevision)
            .put("acknowledged", entry.acknowledged).put("acceptedRevision", entry.acceptedRevision ?: JSONObject.NULL)
        entry.scene?.let { body.put("scene", JSONObject().put("focusIconType", it.focusIconType).put("focusScenario", it.focusScenario)) }
        db.appSettingDao().put(AppSettingEntity(key(entry.scope, entry.summary.mode, entry.summary.id), body.toString(), wall))
    }

    internal suspend fun remove(entry: PendingSummaryMutation) =
        db.appSettingDao().delete(key(entry.scope, entry.summary.mode, entry.summary.id))

    internal suspend fun rememberScene(entry: PendingSummaryMutation, wall: Long) {
        val scene = requireNotNull(entry.scene)
        val body = JSONObject().put("mode", entry.summary.mode.path).put("id", entry.summary.id)
            .put("focusIconType", scene.focusIconType).put("focusScenario", scene.focusScenario)
            .put("operationId", entry.operationId)
            .put("revision", entry.acceptedRevision ?: JSONObject.NULL)
        db.appSettingDao().put(AppSettingEntity(scenePrefix(entry.scope) + suffix(entry.summary.mode, entry.summary.id), body.toString(), wall))
    }

    internal suspend fun scenes(scope: String): List<SummarySceneReceipt> =
        db.appSettingDao().withPrefix(scenePrefix(scope)).map { row ->
            val body = JSONObject(requireNotNull(row.value))
            val mode = FlightRecordMode.entries.single { it.path == body.getString("mode") }
            val id = body.getString("id")
            check(row.key == scenePrefix(scope) + suffix(mode, id))
            SummarySceneReceipt(mode, id, FlightRecordScenePatch(body.getInt("focusIconType"), body.getString("focusScenario")),
                if (body.isNull("revision")) null else body.getLong("revision"), body.getString("operationId"))
        }

    internal suspend fun clearUnversionedScenes(scope: String, baseline: List<SummarySceneReceipt>,
        fetched: Set<Pair<FlightRecordMode, String>>) {
        if (baseline.none { it.revision == null }) return
        val current = scenes(scope).associateBy { it.mode to it.id }
        for (receipt in baseline) {
            val key = receipt.mode to receipt.id
            if (receipt.revision == null && key in fetched && current[key] == receipt)
                db.appSettingDao().delete(scenePrefix(scope) + suffix(receipt.mode, receipt.id))
        }
    }

    private fun decode(text: String): PendingSummaryMutation {
        val body = JSONObject(text); require(body.getInt("version") == 1)
        val scene = body.optJSONObject("scene")?.let { FlightRecordScenePatch(it.getInt("focusIconType"), it.getString("focusScenario")) }
        return PendingSummaryMutation(body.getString("scope"), FlightRecordSummary.parse(Json.parseToJsonElement(body.getString("summary")).jsonObject),
            body.getString("operationId"), body.getLong("expectedRevision"), scene, body.getBoolean("acknowledged"),
            if (body.isNull("acceptedRevision")) null else body.getLong("acceptedRevision"))
    }
}
