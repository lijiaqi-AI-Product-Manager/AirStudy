package com.projectfocus.service.focus.history

import com.projectfocus.core.database.AppDatabase
import com.projectfocus.core.database.entity.AppSettingEntity
import com.projectfocus.core.network.dto.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import org.json.JSONObject

/** Incoming records and their download cursor are committed in the same database transaction. */
@Singleton
class RecordCloudSyncJournal @Inject constructor(private val db: AppDatabase) {
    private fun prefix(scope: String) = "record.cloud.inbox.v1." + RecordCloudSnapshots.digest(scope) + "."
    private fun cursorKey(scope: String) = "record.cloud.cursor.v1." + RecordCloudSnapshots.digest(scope)
    private fun key(scope: String, mode: FlightRecordMode, id: String) = prefix(scope) + mode.path + "." + RecordCloudSnapshots.digest(id)

    internal suspend fun cursor(scope: String): Long = db.appSettingDao().get(cursorKey(scope))?.toLong()?.also { require(it >= 0) } ?: 0

    internal suspend fun stage(scope: String, after: Long, page: CloudFlightRecordPage, wall: Long) {
        check(cursor(scope) == after)
        require(page.items.size <= 100 && page.nextCursor >= after)
        var revision = after
        for (record in page.items) {
            require(record.id.isNotBlank() && record.id == record.id.trim() && record.id.length <= 128)
            require(record.revision > revision && record.revision <= page.nextCursor)
            require(!record.deleted || record.archive == null && record.scene == null)
            record.archive?.let { require(it.validated().mode == record.mode && it.id == record.id) }
            revision = record.revision
            val address = key(scope, record.mode, record.id)
            val existing = db.appSettingDao().get(address)?.let { decode(scope, address, it) }
            if (existing == null || existing.revision < record.revision) {
                val value = JSONObject().put("scope", scope).put("record", Json.encodeToString(CloudFlightRecord.serializer(), record))
                db.appSettingDao().put(AppSettingEntity(address, value.toString(), wall))
            }
        }
        require(page.nextCursor == revision)
        require(!page.hasMore || page.items.isNotEmpty() && page.nextCursor > after)
        db.appSettingDao().put(AppSettingEntity(cursorKey(scope), page.nextCursor.toString(), wall))
    }

    internal suspend fun entries(scope: String): List<CloudFlightRecord> = db.appSettingDao().withPrefix(prefix(scope))
        .map { decode(scope, it.key, requireNotNull(it.value)) }.sortedBy { it.revision }

    internal suspend fun remove(scope: String, record: CloudFlightRecord) {
        val address = key(scope, record.mode, record.id)
        val existing = db.appSettingDao().get(address)?.let { decode(scope, address, it) }
        if (existing?.revision == record.revision) db.appSettingDao().delete(address)
    }

    private fun decode(scope: String, address: String, text: String): CloudFlightRecord {
        val value = JSONObject(text); check(value.getString("scope") == scope)
        return Json.decodeFromString(CloudFlightRecord.serializer(), value.getString("record")).also {
            check(address == key(scope, it.mode, it.id))
        }
    }
}
