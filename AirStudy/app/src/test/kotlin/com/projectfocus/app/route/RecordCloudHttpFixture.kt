package com.projectfocus.app.route

import com.projectfocus.core.network.dto.*
import com.projectfocus.core.network.record.*
import com.projectfocus.service.focus.history.RecordCloudMutationCoordinator
import com.projectfocus.service.focus.history.RecordCloudSyncCoordinator
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import mockwebserver3.*
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.robolectric.util.ReflectionHelpers

/** Real loopback HTTP for the application's normal injected record operations. */
internal class RecordCloudHttpFixture(private val coordinator: RecordCloudMutationCoordinator,
    private val sync: RecordCloudSyncCoordinator? = null) : AutoCloseable {
    private val previousMutations = ReflectionHelpers.getField<RecordMutationGateway>(coordinator, "mutations")
    private val previousArchives = ReflectionHelpers.getField<RecordArchiveGateway>(coordinator, "archives")
    private val previousSync = sync?.let { ReflectionHelpers.getField<RecordArchiveGateway>(it, "gateway") }
    private val http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
    private val server = MockWebServer()
    private val records = mutableMapOf<String, CloudFlightRecord>()
    private val counters = mutableMapOf<String, Long>()
    private val receipts = mutableMapOf<String, Pair<String, Long>>()
    val mutationGateway: RecordMutationGateway
    @Volatile var rejectionStatus: Int? = null
    val requestCount: Int get() = server.requestCount

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(records) { reply(request) }
        }
        server.start()
        mutationGateway = HttpRecordMutationGateway(http, server.url("/").toString())
        val archives = HttpRecordArchiveGateway(http, server.url("/").toString())
        ReflectionHelpers.setField(coordinator, "mutations", mutationGateway)
        ReflectionHelpers.setField(coordinator, "archives", archives)
        sync?.let { ReflectionHelpers.setField(it, "gateway", archives) }
    }

    private fun reply(request: RecordedRequest): MockResponse {
        rejectionStatus?.let { return failure(it) }
        val authorization = request.headers["Authorization"] ?: return failure(401)
        val path = request.url.encodedPath
        if (path == "/v2/flight-records" && request.method == "GET") {
            val after = request.url.queryParameter("after")?.toLongOrNull() ?: return failure(400)
            val limit = request.url.queryParameter("limit")?.toIntOrNull() ?: return failure(400)
            if (after < 0 || limit !in 1..100) return failure(400)
            val all = records.filterKeys { it.startsWith(authorization + "\n") }.values.filter { it.revision > after }.sortedBy { it.revision }
            val selected = all.take(limit)
            val page = CloudFlightRecordPage(selected, hasMore = all.size > selected.size, nextCursor = selected.lastOrNull()?.revision ?: after)
            return MockResponse.Builder().code(200).body(Json.encodeToString(
                FlightRecordResponse.serializer(CloudFlightRecordPage.serializer()), FlightRecordResponse(success = true, data = page))).build()
        }
        if (!path.startsWith("/v2/flight-records/")) return failure(404)
        val segments = path.removePrefix("/v2/flight-records/").split('/')
        if (segments.size !in 2..3) return failure(404)
        val mode = FlightRecordMode.entries.firstOrNull { it.path == segments[0] } ?: return failure(400)
        val id = URLDecoder.decode(segments[1], "UTF-8")
        val key = authorization + "\n" + path.removeSuffix("/focus-scene")
        val body = request.body?.utf8().orEmpty()
        val fingerprint = listOf(request.method, path, request.headers["If-Match"], body).joinToString("\n")
        val operation = request.headers["Idempotency-Key"]?.let { authorization + "\n" + it }
        operation?.let { receipts[it] }?.let { receipt ->
            return if (receipt.first == fingerprint) accepted(receipt.second) else failure(409)
        }
        val current = records[key]
        if (request.method == "DELETE" && current?.deleted == true) {
            operation?.let { receipts[it] = fingerprint to current.revision }
            return accepted(current.revision)
        }
        if (request.method == "PUT" && current?.deleted == true) return failure(410)
        val expected = request.headers["If-Match"]?.removeSurrounding("\"")?.toLongOrNull()
        if (expected != null && expected != (current?.revision ?: 0L)) return failure(409)
        fun next(): Long = ((counters[authorization] ?: 0L) + 1).also { counters[authorization] = it }
        val changed = when (request.method) {
            "PUT" -> {
                val upload = try { Json.decodeFromString(FlightRecordArchiveUpload.serializer(), body).also {
                    require(it.archive.validated().mode == mode && it.archive.id == id) } }
                catch (_: Exception) { return failure(400) }
                if (current?.archive != null && current.archive != upload.archive) return failure(409)
                if (current?.archive == upload.archive) requireNotNull(current)
                else CloudFlightRecord(mode, id, next(), false, upload.archive, current?.scene ?: upload.scene)
            }
            "PATCH" -> {
                if (current == null || current.deleted) return failure(404)
                val value = try { JSONObject(body) } catch (_: Exception) { return failure(400) }
                if (value.keys().asSequence().toSet() != setOf("focusIconType", "focusScenario")) return failure(400)
                val patch = try { Json.decodeFromString(FlightRecordScenePatch.serializer(), body) } catch (_: Exception) { return failure(400) }
                if (patch.focusIconType <= 0 || patch.focusScenario.isBlank()) return failure(400)
                current.copy(revision = next(), scene = patch)
            }
            "DELETE" -> CloudFlightRecord(mode, id, next(), true)
            else -> return failure(405)
        }
        records[key] = changed
        operation?.let { receipts[it] = fingerprint to changed.revision }
        return accepted(changed.revision)
    }

    private fun accepted(revision: Long) = MockResponse.Builder().code(200)
        .body("""{"success":true,"data":{"revision":$revision}}""").build()
    private fun failure(status: Int) = MockResponse.Builder().code(status).body("""{"success":false}""").build()

    override fun close() {
        ReflectionHelpers.setField(coordinator, "mutations", previousMutations)
        ReflectionHelpers.setField(coordinator, "archives", previousArchives)
        if (sync != null && previousSync != null) ReflectionHelpers.setField(sync, "gateway", previousSync)
        server.close(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
    }
}
