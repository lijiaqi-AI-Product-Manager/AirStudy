package com.projectfocus.core.network.record

import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.FlightRecordMode
import com.projectfocus.core.network.dto.FlightRecordMutationOptions
import com.projectfocus.core.network.dto.FlightRecordScenePatch
import java.net.URLEncoder
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

/** Captured credentials travel with one record operation; later account changes cancel it. */
class HttpRecordMutationGateway(client: OkHttpClient, baseUrl: String) : RecordMutationGateway {
    private val origin = baseUrl.toHttpUrl().also {
        require(it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null)
    }
    private val transport = CapturedRecordHttpTransport(client)

    override suspend fun delete(identity: TokenStore.Snapshot, mode: FlightRecordMode, recordId: String,
        options: FlightRecordMutationOptions): RecordMutationOutcome =
        perform(identity, mode, recordId, null, options)

    override suspend fun changeScene(identity: TokenStore.Snapshot, mode: FlightRecordMode, recordId: String,
        scene: FlightRecordScenePatch, options: FlightRecordMutationOptions): RecordMutationOutcome =
        perform(identity, mode, recordId, scene.normalized(), options)

    private suspend fun perform(identity: TokenStore.Snapshot, mode: FlightRecordMode, recordId: String,
        scene: FlightRecordScenePatch?, options: FlightRecordMutationOptions): RecordMutationOutcome {
        if (TokenStore.snapshot() != identity) return RecordMutationOutcome.Obsolete
        if (identity.token.isNullOrBlank()) return RecordMutationOutcome.Rejected(401)
        if (recordId.isBlank()) return RecordMutationOutcome.Rejected(400)
        val request = try { request(identity, mode, recordId, scene, options) }
            catch (_: IllegalArgumentException) { return RecordMutationOutcome.Rejected(-1) }
        return execute(identity, request, deletion = scene == null)
    }

    private fun request(identity: TokenStore.Snapshot, mode: FlightRecordMode, recordId: String,
        scene: FlightRecordScenePatch?, options: FlightRecordMutationOptions): Request {
        require(recordId.isNotBlank())
        val url = origin.newBuilder().encodedPath("/v2/flight-records/")
            .addPathSegment(mode.path)
            .addEncodedPathSegment(URLEncoder.encode(recordId.trim(), "UTF-8"))
            .apply { if (scene != null) addPathSegment("focus-scene") }.build()
        return Request.Builder().url(url).header("Authorization", "Bearer " + identity.token.orEmpty())
            .header("accept", "application/json").header("User-Agent", "AirStudy/1.0")
            .apply {
                options.operationId?.let { header("Idempotency-Key", it) }
                options.expectedRevision?.let { header("If-Match", "\"$it\"") }
                if (scene == null) delete() else {
                    val json = JSONObject().put("focusIconType", scene.focusIconType).put("focusScenario", scene.focusScenario)
                    patch(json.toString().toRequestBody("application/json".toMediaType()))
                }
            }.build()
    }

    private suspend fun execute(identity: TokenStore.Snapshot, request: Request, deletion: Boolean): RecordMutationOutcome =
        transport.execute(identity, request, RecordMutationOutcome.Obsolete, RecordMutationOutcome.NetworkFailure) { response ->
            parse(response, deletion)
        }

    private fun parse(response: Response, deletion: Boolean): RecordMutationOutcome {
        if (deletion && response.code == 404) return RecordMutationOutcome.Acknowledged(alreadyMissing = true)
        val body = response.body
        val source = body?.source()
        source?.request(MAX_RESPONSE_BYTES + 1)
        if (source != null && source.buffer.size > MAX_RESPONSE_BYTES) return RecordMutationOutcome.InvalidResponse
        val root = try { JSONObject(source?.readUtf8().orEmpty()) } catch (_: Exception) { null }
        if (!response.isSuccessful) return RecordMutationOutcome.Rejected(response.code,
            root?.optString("message", "").orEmpty(), response.code == 408 || response.code == 429 || response.code >= 500)
        if (root == null) return RecordMutationOutcome.InvalidResponse
        if (!root.optBoolean("success", false)) return RecordMutationOutcome.Rejected(response.code, root.optString("message", ""))
        val revision = root.optJSONObject("data")?.optLong("revision", -1)?.takeIf { it >= 0 }
        return RecordMutationOutcome.Acknowledged(revision = revision)
    }

    private companion object { const val MAX_RESPONSE_BYTES = 65_536L }
}
