package com.projectfocus.core.network.record

import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.*
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class HttpRecordArchiveGateway(client: OkHttpClient, baseUrl: String) : RecordArchiveGateway {
    private val origin = baseUrl.toHttpUrl().also {
        require(it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null)
    }
    private val transport = CapturedRecordHttpTransport(client)
    private val codec = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override suspend fun upload(identity: TokenStore.Snapshot, record: FlightRecordArchiveUpload,
                                options: FlightRecordMutationOptions): RecordMutationOutcome {
        invalid(identity)?.let { return it }
        val request = try {
            val normalized = record.copy(archive = record.archive.withoutDeviceOwnership())
            val url = origin.newBuilder().encodedPath("/v2/flight-records/")
                .addPathSegment(normalized.archive.mode.path)
                .addEncodedPathSegment(URLEncoder.encode(normalized.archive.id, "UTF-8")).build()
            builder(identity, url).apply {
                options.operationId?.let { header("Idempotency-Key", it) }
                options.expectedRevision?.let { header("If-Match", "\"$it\"") }
            }.put(codec.encodeToString(FlightRecordArchiveUpload.serializer(), normalized)
                .toRequestBody("application/json".toMediaType())).build()
        } catch (_: IllegalArgumentException) { return RecordMutationOutcome.Rejected(400) }
        return transport.execute(identity, request, RecordMutationOutcome.Obsolete, RecordMutationOutcome.NetworkFailure) { response ->
            val (root, failure) = envelope(response)
            failure ?: root?.optJSONObject("data")?.optLong("revision", -1)?.takeIf { it > 0L }
                ?.let { RecordMutationOutcome.Acknowledged(revision = it) } ?: RecordMutationOutcome.InvalidResponse
        }
    }

    override suspend fun changes(identity: TokenStore.Snapshot, after: Long, limit: Int): RecordArchiveReadResult {
        invalid(identity)?.let { return RecordArchiveReadResult.Failure(it) }
        if (after < 0L || limit !in 1..100) return RecordArchiveReadResult.Failure(RecordMutationOutcome.Rejected(400))
        val request = try {
            builder(identity, origin.newBuilder().encodedPath("/v2/flight-records")
                .addQueryParameter("after", after.toString()).addQueryParameter("limit", limit.toString()).build()).get().build()
        } catch (_: IllegalArgumentException) { return RecordArchiveReadResult.Failure(RecordMutationOutcome.Rejected(400)) }
        return transport.execute(identity, request, RecordArchiveReadResult.Failure(RecordMutationOutcome.Obsolete),
            RecordArchiveReadResult.Failure(RecordMutationOutcome.NetworkFailure)) { response ->
            val (root, failure) = envelope(response)
            if (failure != null) return@execute RecordArchiveReadResult.Failure(failure)
            val page = try {
                val page = codec.decodeFromString(CloudFlightRecordPage.serializer(), requireNotNull(root?.optJSONObject("data")).toString())
                require(page.items.size <= limit && (!page.hasMore || page.items.isNotEmpty()))
                var previous = after
                val keys = HashSet<Pair<FlightRecordMode, String>>()
                for (record in page.items) {
                    require(record.id.isNotBlank() && record.id == record.id.trim() && record.id.length <= 128)
                    require(record.revision > previous && keys.add(record.mode to record.id))
                    previous = record.revision
                    if (record.deleted) require(record.archive == null && record.scene == null)
                    record.archive?.let { require(it.validated().mode == record.mode && it.id == record.id) }
                }
                require(page.nextCursor == previous)
                page
            } catch (_: Exception) { return@execute RecordArchiveReadResult.Failure(RecordMutationOutcome.InvalidResponse) }
            RecordArchiveReadResult.Loaded(page)
        }
    }

    private fun invalid(identity: TokenStore.Snapshot): RecordMutationOutcome? = when {
        TokenStore.snapshot() != identity -> RecordMutationOutcome.Obsolete
        identity.token.isNullOrBlank() -> RecordMutationOutcome.Rejected(401)
        else -> null
    }

    private fun builder(identity: TokenStore.Snapshot, url: HttpUrl) = Request.Builder().url(url)
        .header("Authorization", "Bearer " + identity.token.orEmpty()).header("accept", "application/json")
        .header("User-Agent", "AirStudy/1.0")

    private fun envelope(response: Response): Pair<JSONObject?, RecordMutationOutcome?> {
        val source = response.body?.source()
        source?.request(MAX_RESPONSE_BYTES + 1L)
        if (source != null && source.buffer.size > MAX_RESPONSE_BYTES) return null to RecordMutationOutcome.InvalidResponse
        val root = try { JSONObject(source?.readUtf8().orEmpty()) } catch (_: Exception) { null }
        if (!response.isSuccessful) return root to RecordMutationOutcome.Rejected(response.code,
            root?.optString("message", "").orEmpty(), response.code == 408 || response.code == 429 || response.code >= 500)
        if (root == null) return null to RecordMutationOutcome.InvalidResponse
        if (!root.optBoolean("success", false)) return root to RecordMutationOutcome.Rejected(response.code, root.optString("message", ""))
        return root to null
    }

    private companion object { const val MAX_RESPONSE_BYTES = 40L * 1024L * 1024L }
}
