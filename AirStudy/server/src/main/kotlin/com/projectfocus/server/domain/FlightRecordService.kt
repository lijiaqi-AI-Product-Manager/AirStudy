package com.projectfocus.server.domain

import com.projectfocus.core.model.flight.freeflight.FreeFlightStatus
import com.projectfocus.core.network.dto.*
import com.projectfocus.server.db.CloudFlightRecords as Records
import com.projectfocus.server.db.FlightRecordOperations as Operations
import com.projectfocus.server.db.Tables.Flights
import com.projectfocus.server.db.Tables.Users
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.MessageDigest

internal val recordJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }

class FlightRecordFailure(val status: HttpStatusCode, override val message: String) : RuntimeException(message)

/** Each accepted mutation, its retry receipt and account totals commit together. */
class FlightRecordService(private val database: Database) {
    fun upload(user: String, mode: FlightRecordMode, id: String, body: FlightRecordArchiveUpload,
               options: FlightRecordMutationOptions): FlightRecordMutationReceipt {
        validateKey(id)
        val archive = body.archive.withoutDeviceOwnership()
        require(archive.mode == mode && archive.id == id)
        val payload = recordJson.encodeToString(FlightRecordArchiveSnapshot.serializer(), archive)
        require(payload.toByteArray(Charsets.UTF_8).size <= 32 * 1024 * 1024)
        val scene = body.scene?.let(::validatedScene)
        return mutate(user, mode, id, "upload", payload + "\n" + scene, options) {
            val current = find(user, mode, id)
            if (current?.get(Records.deleted) == true) fail(HttpStatusCode.Gone, "记录已删除")
            val stored = current?.get(Records.archiveJson)
            if (stored != null && stored != payload) fail(HttpStatusCode.Conflict, "记录内容已更改")
            checkRevision(current, options)
            if (stored == payload) return@mutate current!![Records.revision]
            val revision = nextRevision(user)
            write(user, mode, id, current, revision, false, payload, current?.get(Records.sceneJson) ?: scene)
            FlightRecordTotals.recompute(user)
            revision
        }
    }

    fun changeScene(user: String, mode: FlightRecordMode, id: String, patch: FlightRecordScenePatch,
                    options: FlightRecordMutationOptions): FlightRecordMutationReceipt {
        validateKey(id)
        val scene = validatedScene(patch)
        return mutate(user, mode, id, "scene", scene, options) {
            val current = find(user, mode, id)
            val legacy = if (current == null && mode == FlightRecordMode.DOWN) legacy(user, id) else null
            if (current == null && legacy == null || current?.get(Records.deleted) == true)
                fail(HttpStatusCode.NotFound, "记录不存在")
            val snapshot = current?.get(Records.archiveJson)?.let {
                recordJson.decodeFromString(FlightRecordArchiveSnapshot.serializer(), it)
            }
            val completed = when {
                snapshot?.countdown != null -> snapshot.countdown.status == "COMPLETED"
                snapshot?.countUp != null -> snapshot.countUp.status == FreeFlightStatus.COMPLETED
                else -> (legacy ?: legacy(user, id))?.get(Flights.status) == "COMPLETED"
            }
            if (!completed) fail(HttpStatusCode.Conflict, "该记录不能修改场景")
            checkRevision(current, options)
            if (current?.get(Records.sceneJson) == scene) return@mutate current[Records.revision]
            val revision = nextRevision(user)
            write(user, mode, id, current, revision, false, current?.get(Records.archiveJson), scene)
            revision
        }
    }

    fun delete(user: String, mode: FlightRecordMode, id: String,
               options: FlightRecordMutationOptions): FlightRecordMutationReceipt {
        validateKey(id)
        return mutate(user, mode, id, "delete", "", options) {
            val current = find(user, mode, id)
            if (current?.get(Records.deleted) == true) return@mutate current[Records.revision]
            val legacy = if (mode == FlightRecordMode.DOWN) legacy(user, id) else null
            if (legacy != null && legacy[Flights.status] !in setOf("COMPLETED", "CANCELLED", "ABANDONED"))
                fail(HttpStatusCode.Conflict, "进行中的飞行不能删除")
            checkRevision(current, options)
            val revision = nextRevision(user)
            write(user, mode, id, current, revision, true, null, null)
            if (mode == FlightRecordMode.DOWN)
                Flights.deleteWhere { (Flights.userId eq user) and (Flights.id eq id) }
            FlightRecordTotals.recompute(user)
            revision
        }
    }

    fun changes(user: String, after: Long, limit: Int): CloudFlightRecordPage {
        require(after >= 0L && limit in 1..100)
        return transaction(database) {
            val rows = Records.selectAll().where { (Records.userId eq user) and (Records.revision greater after) }
                .orderBy(Records.revision to SortOrder.ASC).limit(limit)
            val items = mutableListOf<CloudFlightRecord>()
            var bytes = 0L
            for (row in rows) {
                val size = row[Records.archiveJson]?.toByteArray(Charsets.UTF_8)?.size ?: 0
                if (items.isNotEmpty() && bytes + size > 4L * 1024L * 1024L) break
                bytes += size
                items += CloudFlightRecord(FlightRecordMode.entries.single { it.path == row[Records.mode] }, row[Records.recordId],
                    row[Records.revision], row[Records.deleted], row[Records.archiveJson]?.let {
                        recordJson.decodeFromString(FlightRecordArchiveSnapshot.serializer(), it)
                    }, row[Records.sceneJson]?.let { recordJson.decodeFromString(FlightRecordScenePatch.serializer(), it) })
            }
            val cursor = items.lastOrNull()?.revision ?: after
            val more = Records.selectAll().where { (Records.userId eq user) and (Records.revision greater cursor) }.limit(1).any()
            CloudFlightRecordPage(items = items, hasMore = more, nextCursor = cursor)
        }
    }

    private fun mutate(user: String, mode: FlightRecordMode, id: String, action: String, payload: String,
                       options: FlightRecordMutationOptions, block: () -> Long): FlightRecordMutationReceipt = transaction(database) {
        Users.selectAll().where { Users.id eq user }.forUpdate().singleOrNull()
            ?: fail(HttpStatusCode.Unauthorized, "请重新登录")
        val hash = MessageDigest.getInstance("SHA-256").digest(
            recordJson.encodeToString(kotlinx.serialization.serializer<List<String>>(),
                listOf(action, mode.path, id, payload, options.expectedRevision?.toString() ?: "")).toByteArray(Charsets.UTF_8)
        ).joinToString("") { "%02x".format(it) }
        if (options.operationId != null) {
            val receipt = Operations.selectAll().where { (Operations.userId eq user) and (Operations.operationId eq options.operationId) }.singleOrNull()
            if (receipt != null) {
                if (receipt[Operations.requestHash] != hash) fail(HttpStatusCode.Conflict, "操作编号已用于其他修改")
                return@transaction FlightRecordMutationReceipt(receipt[Operations.revision])
            }
        }
        val revision = block()
        options.operationId?.let { key ->
            Operations.insert { it[userId] = user; it[operationId] = key; it[requestHash] = hash; it[Operations.revision] = revision }
        }
        FlightRecordMutationReceipt(revision)
    }

    private fun validatedScene(patch: FlightRecordScenePatch): String {
        val normalized = patch.normalized()
        require(normalized.focusScenario.isNotBlank() && normalized.focusScenario.length <= 512)
        return recordJson.encodeToString(FlightRecordScenePatch.serializer(), normalized)
    }

    private fun checkRevision(row: ResultRow?, options: FlightRecordMutationOptions) {
        if (options.expectedRevision != null && options.expectedRevision != (row?.get(Records.revision) ?: 0L))
            fail(HttpStatusCode.Conflict, "记录已在其他设备更改，请刷新后重试")
    }

    private fun find(user: String, mode: FlightRecordMode, id: String) = Records.selectAll().where {
        (Records.userId eq user) and (Records.mode eq mode.path) and (Records.recordId eq id)
    }.singleOrNull()

    private fun legacy(user: String, id: String) = Flights.selectAll().where {
        (Flights.userId eq user) and (Flights.id eq id)
    }.singleOrNull()

    private fun nextRevision(user: String): Long = Math.addExact(Records.selectAll().where { Records.userId eq user }
        .orderBy(Records.revision to SortOrder.DESC).limit(1).singleOrNull()?.get(Records.revision) ?: 0L, 1L)

    private fun write(user: String, mode: FlightRecordMode, id: String, previous: ResultRow?, revision: Long,
                      deleted: Boolean, archive: String?, scene: String?) {
        if (previous == null) Records.insert {
            it[userId] = user; it[Records.mode] = mode.path; it[recordId] = id; it[Records.revision] = revision
            it[Records.deleted] = deleted; it[archiveJson] = archive; it[sceneJson] = scene
        } else Records.update({ (Records.userId eq user) and (Records.mode eq mode.path) and (Records.recordId eq id) }) {
            it[Records.revision] = revision; it[Records.deleted] = deleted; it[archiveJson] = archive; it[sceneJson] = scene
        }
    }

    private fun validateKey(id: String) { require(id.isNotBlank() && id == id.trim() && id.length <= 128 && id.none(Char::isISOControl)) }
    private fun fail(status: HttpStatusCode, message: String): Nothing = throw FlightRecordFailure(status, message)
}
