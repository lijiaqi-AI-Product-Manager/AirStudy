package com.projectfocus.service.focus.history

import com.projectfocus.core.database.AppDatabase
import com.projectfocus.core.database.entity.AppSettingEntity
import com.projectfocus.core.network.dto.FlightRecordScenePatch
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

internal data class PendingRecordMutation(
    val scope: String, val key: FlightRecordKey, val remoteId: String, val fingerprint: String,
    val operationId: String, val expectedRemoteRevision: Long?, val desiredSceneJson: String?,
    val scenePatch: FlightRecordScenePatch?, val sceneRevision: Long?, val startedAt: Long,
    val acknowledged: Boolean = false, val acceptedRevision: Long? = null,
)

internal data class RecordCloudBinding(val remoteId: String, val revision: Long?, val deleted: Boolean)

/** Uses the existing transactional key-value table; credentials are never persisted in operations. */
@Singleton
class RecordMutationJournal @Inject constructor(private val db: AppDatabase) {
    private fun prefix(scope: String) = "record.cloud.pending.v1." + RecordCloudSnapshots.digest(scope) + "."
    private fun suffix(key: FlightRecordKey) = key.kind.name + "." + RecordCloudSnapshots.digest(key.id)
    private fun operationKey(scope: String, key: FlightRecordKey) = prefix(scope) + suffix(key)
    private fun bindingKey(scope: String, key: FlightRecordKey) = "record.cloud.binding.v1." + RecordCloudSnapshots.digest(scope) + "." + suffix(key)

    internal suspend fun pending(scope: String, key: FlightRecordKey): PendingRecordMutation? =
        db.appSettingDao().get(operationKey(scope, key))?.let(::decode)?.also { require(it.scope == scope && it.key == key) }
    internal suspend fun pending(scope: String): List<PendingRecordMutation> =
        db.appSettingDao().withPrefix(prefix(scope)).map { decode(requireNotNull(it.value)) }.also { entries ->
            require(entries.all { it.scope == scope })
        }
    internal suspend fun put(entry: PendingRecordMutation, wall: Long) {
        val value = JSONObject().put("version", 1).put("scope", entry.scope).put("kind", entry.key.kind.name)
            .put("localId", entry.key.id).put("remoteId", entry.remoteId).put("fingerprint", entry.fingerprint)
            .put("operationId", entry.operationId).put("expectedRemoteRevision", entry.expectedRemoteRevision ?: JSONObject.NULL)
            .put("desiredSceneJson", entry.desiredSceneJson ?: JSONObject.NULL).put("sceneRevision", entry.sceneRevision ?: JSONObject.NULL)
            .put("startedAt", entry.startedAt).put("acknowledged", entry.acknowledged).put("acceptedRevision", entry.acceptedRevision ?: JSONObject.NULL)
        entry.scenePatch?.let { value.put("focusIconType", it.focusIconType).put("focusScenario", it.focusScenario) }
        db.appSettingDao().put(AppSettingEntity(operationKey(entry.scope, entry.key), value.toString(), wall))
    }
    internal suspend fun remove(entry: PendingRecordMutation) = db.appSettingDao().delete(operationKey(entry.scope, entry.key))
    internal suspend fun binding(scope: String, key: FlightRecordKey): RecordCloudBinding? =
        db.appSettingDao().get(bindingKey(scope, key))?.let { text ->
            val value = JSONObject(text)
            RecordCloudBinding(value.getString("remoteId"), value.optionalLong("revision"), value.getBoolean("deleted"))
        }
    internal suspend fun bind(scope: String, key: FlightRecordKey, binding: RecordCloudBinding, wall: Long) {
        val value = JSONObject().put("remoteId", binding.remoteId).put("revision", binding.revision ?: JSONObject.NULL).put("deleted", binding.deleted)
        db.appSettingDao().put(AppSettingEntity(bindingKey(scope, key), value.toString(), wall))
    }
    private fun decode(text: String): PendingRecordMutation {
        val value = JSONObject(text); require(value.getInt("version") == 1)
        return PendingRecordMutation(value.getString("scope"), FlightRecordKey(FlightRecordKind.valueOf(value.getString("kind")), value.getString("localId")),
            value.getString("remoteId"), value.getString("fingerprint"), value.getString("operationId"), value.optionalLong("expectedRemoteRevision"),
            value.optString("desiredSceneJson").takeIf { !value.isNull("desiredSceneJson") },
            if (value.has("focusIconType")) FlightRecordScenePatch(value.getInt("focusIconType"), value.getString("focusScenario")) else null,
            value.optionalLong("sceneRevision"), value.getLong("startedAt"), value.getBoolean("acknowledged"), value.optionalLong("acceptedRevision"))
    }
    private fun JSONObject.optionalLong(key: String): Long? = if (isNull(key)) null else getLong(key)
}
