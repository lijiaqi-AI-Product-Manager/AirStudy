package com.projectfocus.app.auth

import com.projectfocus.core.network.di.NetworkCore
import com.projectfocus.core.network.di.NetworkModule.FlightPlanOkHttpClient
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface PasswordResetGateway {
    suspend fun sendCode(email: String): Int
    suspend fun reset(email: String, code: String, newPassword: String): Int
}

class PasswordResetTransportException(cause: IOException) : IOException("Password reset network failure", cause)
class PasswordResetTimeoutException : IOException("Password reset request timed out")
class PasswordResetRequestBuildException(cause: Exception) : IOException("Could not build password reset request", cause)

/**
 * Source k3 dispatches HTTP status without reading the body. These anonymous
 * endpoints, timeout/cancellation and response cleanup are explicit Own adapters.
 */
@Singleton
class OwnPasswordResetGateway internal constructor(
    client: OkHttpClient,
    private val baseUrl: String,
    private val timeoutMillis: Long = 12_000L,
) : PasswordResetGateway {
    @Inject constructor(core: NetworkCore, @FlightPlanOkHttpClient client: OkHttpClient) : this(client, core.baseUrl)

    init { require(timeoutMillis > 0L) }

    private val http = client.newBuilder().apply {
        interceptors().clear()
        networkInterceptors().clear()
    }.authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .cookieJar(CookieJar.NO_COOKIES).cache(null)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS).readTimeout(timeoutMillis, TimeUnit.MILLISECONDS).build()

    override suspend fun sendCode(email: String): Int = request("/auth/password-reset/code", JSONObject().put("email", email))

    override suspend fun reset(email: String, code: String, newPassword: String): Int = request("/auth/password-reset",
        JSONObject().put("email", email).put("code", code).put("new_password", newPassword))

    private suspend fun request(path: String, body: JSONObject): Int {
        val request = try {
            Request.Builder().url(baseUrl.trimEnd('/') + path).post(body.toString().toRequestBody(JSON))
                .header("Accept", "application/json").build()
        } catch (error: Exception) { throw PasswordResetRequestBuildException(error) }
        val status = withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine<Int> { continuation ->
                val finished = AtomicBoolean(false)
                val call = http.newCall(request)
                fun fail(error: IOException) {
                    if (!finished.compareAndSet(false, true)) return
                    call.cancel()
                    continuation.resumeWithException(if (error is InterruptedIOException) PasswordResetTimeoutException()
                        else PasswordResetTransportException(error))
                }
                continuation.invokeOnCancellation { finished.set(true); call.cancel() }
                if (finished.get()) return@suspendCancellableCoroutine
                try {
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) { fail(e) }
                        override fun onResponse(call: Call, response: Response) {
                            try {
                                // Deliver headers status before Own cleanup. Do not call
                                // body.string(), parse JSON, or await body EOF. A truncated
                                // 200 response is still the source successful status.
                                if (finished.compareAndSet(false, true)) continuation.resume(response.code)
                            } finally {
                                try { response.body?.close() }
                                catch (_: IOException) { /* Own cleanup cannot replace obtained HTTP status. */ }
                            }
                        }
                    })
                } catch (error: Exception) {
                    if (finished.compareAndSet(false, true)) {
                        call.cancel()
                        continuation.resumeWithException(PasswordResetRequestBuildException(error))
                    }
                }
            }
        } ?: throw PasswordResetTimeoutException()
        currentCoroutineContext().ensureActive()
        return status
    }

    private companion object { val JSON = "application/json".toMediaType() }
}
