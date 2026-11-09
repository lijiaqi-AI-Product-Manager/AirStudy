package com.projectfocus.app.route

import com.projectfocus.core.network.dto.FlightRecordArchiveUpload
import com.projectfocus.core.network.record.*
import com.projectfocus.service.focus.history.RecordCloudMutationCoordinator
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import mockwebserver3.*
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.robolectric.util.ReflectionHelpers

/** Real loopback HTTP for the application's normal injected record operations. */
internal class RecordCloudHttpFixture(private val coordinator: RecordCloudMutationCoordinator) : AutoCloseable {
    private val previousMutations = ReflectionHelpers.getField<RecordMutationGateway>(coordinator, "mutations")
    private val previousArchives = ReflectionHelpers.getField<RecordArchiveGateway>(coordinator, "archives")
    private val http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
    private val server = MockWebServer()
    private val revisions = mutableMapOf<String, Long>()
    private val receipts = mutableMapOf<String, Pair<String, Long>>()
    @Volatile var rejectionStatus: Int? = null
    val requestCount: Int get() = server.requestCount

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(revisions) { reply(request) }
        }
        server.start()
        ReflectionHelpers.setField(coordinator, "mutations", HttpRecordMutationGateway(http, server.url("/").toString()))
        ReflectionHelpers.setField(coordinator, "archives", HttpRecordArchiveGateway(http, server.url("/").toString()))
    }

    private fun reply(request: RecordedRequest): MockResponse {
        rejectionStatus?.let { return failure(it) }
        val authorization = request.headers["Authorization"] ?: return failure(401)
        val path = request.url.encodedPath
        if (!path.startsWith("/v2/flight-records/")) return failure(404)
        val key = authorization + "\n" + path.removeSuffix("/focus-scene")
        val body = request.body?.utf8().orEmpty()
        val fingerprint = listOf(request.method, path, request.headers["If-Match"], body).joinToString("\n")
        val operation = request.headers["Idempotency-Key"]?.let { authorization + "\n" + it }
        operation?.let { receipts[it] }?.let { receipt ->
            return if (receipt.first == fingerprint) accepted(receipt.second) else failure(409)
        }
        val current = revisions[key] ?: 0L
        val expected = request.headers["If-Match"]?.removeSurrounding("\"")?.toLongOrNull()
        if (expected != null && expected != current) return failure(409)
        val next = when (request.method) {
            "PUT" -> {
                try { Json.decodeFromString(FlightRecordArchiveUpload.serializer(), body).archive.validated() }
                catch (_: Exception) { return failure(400) }
                current.takeIf { it > 0 } ?: 1L
            }
            "PATCH" -> {
                if (current == 0L) return failure(404)
                val patch = try { JSONObject(body) } catch (_: Exception) { return failure(400) }
                if (patch.keys().asSequence().toSet() != setOf("focusIconType", "focusScenario") ||
                    patch.optInt("focusIconType") <= 0 || patch.optString("focusScenario").isBlank()) return failure(400)
                current + 1L
            }
            "DELETE" -> current + 1L
            else -> return failure(405)
        }
        revisions[key] = next
        operation?.let { receipts[it] = fingerprint to next }
        return accepted(next)
    }

    private fun accepted(revision: Long) = MockResponse.Builder().code(200)
        .body("""{"success":true,"data":{"revision":$revision}}""").build()
    private fun failure(status: Int) = MockResponse.Builder().code(status).body("""{"success":false}""").build()

    override fun close() {
        ReflectionHelpers.setField(coordinator, "mutations", previousMutations)
        ReflectionHelpers.setField(coordinator, "archives", previousArchives)
        server.close(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
    }
}
