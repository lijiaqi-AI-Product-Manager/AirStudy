package com.projectfocus.core.network.record

import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.FlightRecordSummary
import com.projectfocus.core.network.dto.FlightRecordSummaryQuery
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl

class HttpRecordSummaryGateway(client: OkHttpClient, baseUrl: String) : RecordSummaryGateway {
    private val origin = baseUrl.toHttpUrl().also {
        require(it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null)
    }
    private val transport = CapturedRecordHttpTransport(client)

    override suspend fun page(identity: TokenStore.Snapshot, query: FlightRecordSummaryQuery): RecordSummaryReadResult {
        fun failure(reason: RecordMutationOutcome) = RecordSummaryReadResult.Failure(reason)
        if (identity != TokenStore.snapshot()) return failure(RecordMutationOutcome.Obsolete)
        if (identity.token.isNullOrBlank()) return failure(RecordMutationOutcome.Rejected(401))
        val request = try {
            query.validated()
            Request.Builder().url(origin.newBuilder().encodedPath("/v2/flight-records")
                .addQueryParameter("page", query.page.toString()).addQueryParameter("limit", query.limit.toString())
                .addQueryParameter("timerType", query.timerType).addQueryParameter("status", query.status).build())
                .header("Authorization", "Bearer " + identity.token).header("Content-Type", "application/json")
                .header("User-Agent", "AirStudy/1.0").get().build()
        } catch (_: IllegalArgumentException) { return failure(RecordMutationOutcome.Rejected(400)) }
        return transport.execute(identity, request, failure(RecordMutationOutcome.Obsolete),
            failure(RecordMutationOutcome.NetworkFailure)) { response ->
            val source = response.body?.source()
            source?.request(MAX_BYTES + 1L)
            if (source != null && source.buffer.size > MAX_BYTES) return@execute failure(RecordMutationOutcome.InvalidResponse)
            val root = try { Json.parseToJsonElement(source?.readUtf8().orEmpty()) as? JsonObject }
                catch (_: Exception) { null }
            if (!response.isSuccessful) return@execute failure(RecordMutationOutcome.Rejected(response.code,
                (root?.get("message") as? JsonPrimitive)?.contentOrNull.orEmpty(),
                response.code == 408 || response.code == 429 || response.code >= 500))
            if (root == null) return@execute failure(RecordMutationOutcome.InvalidResponse)
            val accepted = (root["success"] as? JsonPrimitive)?.booleanOrNull
                ?: return@execute failure(RecordMutationOutcome.InvalidResponse)
            if (!accepted) return@execute failure(
                RecordMutationOutcome.Rejected(response.code, (root["message"] as? JsonPrimitive)?.contentOrNull.orEmpty()))
            try {
                val data = requireNotNull(root["data"] as? JsonObject)
                val raw = requireNotNull(data["records"] as? JsonArray)
                require(raw.size <= query.limit)
                val records = raw.map { FlightRecordSummary.parse(requireNotNull(it as? JsonObject)) }
                require(records.map { it.mode to it.id }.toSet().size == records.size)
                val pagination = data["pagination"] as? JsonObject
                val pages = (pagination?.get("totalPages") as? JsonPrimitive)?.intOrNull ?: query.page
                val total = (pagination?.get("totalCount") as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
                require(pages in 0..1_000_000 && (pages <= query.page || records.isNotEmpty()))
                require(pages != 0 || records.isEmpty())
                require(total == null || total >= records.size)
                RecordSummaryReadResult.Loaded(RecordSummaryPage(records, query.page, pages, total))
            } catch (_: Exception) { failure(RecordMutationOutcome.InvalidResponse) }
        }
    }

    private companion object { const val MAX_BYTES = 8L * 1024L * 1024L }
}
