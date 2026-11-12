package com.projectfocus.core.network.plan

import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.DailyFlightPlanSnapshot
import com.projectfocus.core.network.dto.CancelBatchDeleteFlightPlanRequest
import com.projectfocus.core.network.dto.FlightPlanHistoryRequest
import com.projectfocus.core.network.dto.GetFlightPlanRequest
import com.projectfocus.core.network.dto.UpsertFlightPlanRequest
import com.projectfocus.core.network.dto.UpdateFlightPlanLegStatusRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** data.code is a source String branch, independent of the project's numeric error_code. */
class FlightPlanRejectedException(message: String, val code: String = "") : IOException(message) {
    val isTerminalConflict: Boolean get() = code == "LEG_TERMINAL_CONFLICT"
}
class FlightPlanAuthenticationException : IOException("Flight plan authentication is required")
class FlightPlanIdentityChangedException : IOException("Flight plan account changed during the request")

/**
 * The observed actions/JSON use real WS frames. URL, JWT and per-request socket lifetime
 * are this project's adapters; the original server/handshake were not reproduced.
 * Mutations are never automatically retried after a lost acknowledgement.
 */
class SocketFlightPlanGateway(
    private val client: OkHttpClient,
    baseUrl: String,
    private val identity: () -> TokenStore.Snapshot = TokenStore::snapshot,
    private val timeoutMillis: Long = 12_000,
) : FlightPlanGateway {
    private val endpoint = requireNotNull(baseUrl.toHttpUrl().resolve("flight-plan"))

    override suspend fun get(request: GetFlightPlanRequest): DailyFlightPlanSnapshot? =
        FlightPlanResponseParser.daily(exchange(request.requestId, request.action, FlightPlanRequestCodec.encode(request)))

    override suspend fun upsert(request: UpsertFlightPlanRequest): DailyFlightPlanSnapshot? =
        FlightPlanResponseParser.daily(exchange(request.requestId, request.action, FlightPlanRequestCodec.encode(request)))

    override suspend fun history(request: FlightPlanHistoryRequest): List<DailyFlightPlanSnapshot> =
        FlightPlanResponseParser.history(exchange(request.requestId, request.action, FlightPlanRequestCodec.encode(request)))

    override suspend fun deleteCanceled(request: CancelBatchDeleteFlightPlanRequest): DailyFlightPlanSnapshot? =
        FlightPlanResponseParser.daily(exchange(request.requestId, request.action, FlightPlanRequestCodec.encode(request)))

    override suspend fun updateLegStatus(request: UpdateFlightPlanLegStatusRequest): DailyFlightPlanSnapshot? =
        FlightPlanResponseParser.status(exchange(request.requestId, request.action, FlightPlanRequestCodec.encode(request)))

    private suspend fun exchange(requestId: String, action: String, payload: String): org.json.JSONObject? {
        require(requestId.isNotBlank()) { "Missing flight plan request ID" }
        val account = identity()
        val token = account.token?.takeIf { it.isNotBlank() } ?: throw FlightPlanAuthenticationException()
        val request = Request.Builder().url(endpoint).header("Authorization", "Bearer $token").build()
        return withTimeout(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val done = AtomicBoolean(false)
                val socket = AtomicReference<WebSocket?>()
                fun fail(error: Throwable) {
                    if (done.compareAndSet(false, true)) {
                        socket.get()?.cancel()
                        continuation.resumeWithException(error)
                    }
                }
                continuation.invokeOnCancellation {
                    done.set(true)
                    socket.get()?.cancel()
                }
                val listener = object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        socket.set(webSocket)
                        if (done.get()) { webSocket.cancel(); return }
                        if (identity() != account) { fail(FlightPlanIdentityChangedException()); return }
                        if (!webSocket.send(payload)) fail(IOException("Flight plan socket refused the request"))
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (done.get()) return
                        if (identity() != account) { fail(FlightPlanIdentityChangedException()); return }
                        try {
                            val response = FlightPlanResponseParser.envelope(text)
                            if (response.requestId != requestId) return
                            if (response.action.isNotEmpty() && response.action != action) return
                            if (!response.success) {
                                fail(FlightPlanRejectedException(response.message.takeIf { it.isNotBlank() }
                                    ?: "Flight plan request failed", response.code))
                            } else if (identity() != account) {
                                fail(FlightPlanIdentityChangedException())
                            } else if (done.compareAndSet(false, true)) {
                                continuation.resume(response.data)
                                webSocket.close(1000, null)
                            }
                        } catch (error: Exception) {
                            fail(IOException("Could not parse flight plan reply", error))
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, null)
                        fail(IOException("Flight plan socket closed before acknowledgement"))
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        fail(IOException("Flight plan socket closed before acknowledgement"))
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        fail(IOException("Flight plan connection failed", t))
                    }
                }
                try {
                    val webSocket = client.newWebSocket(request, listener)
                    // onOpen may already have run on the network thread.
                    socket.compareAndSet(null, webSocket)
                    if (done.get()) webSocket.cancel()
                } catch (error: Exception) { fail(error) }
            }
        }
    }
}
