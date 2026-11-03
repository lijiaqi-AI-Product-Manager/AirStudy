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

interface RegistrationGateway {
    suspend fun sendCode(email: String): Int
    suspend fun register(email: String, code: String, password: String): Int
}

class RegistrationTransportException(cause: IOException) : IOException("Registration network failure", cause)
class RegistrationTimeoutException : IOException("Registration request timed out")
class RegistrationBodyReadException(cause: IOException) : IOException("Could not read registration response", cause)
class RegistrationRequestBuildException(cause: Exception) : IOException("Could not build registration request", cause)

/** Source field/status-only semantics, with own anonymous routes and cancellation/timeout adapter. */
@Singleton
class OwnRegistrationGateway internal constructor(
    client: OkHttpClient,
    private val baseUrl: String,
    private val timeoutMillis: Long = 12_000L,
) : RegistrationGateway {
    @Inject constructor(core: NetworkCore, @FlightPlanOkHttpClient client: OkHttpClient) : this(client, core.baseUrl)

    init { require(timeoutMillis > 0L) }

    private val http = client.newBuilder().apply {
        // Public Builder methods in the project's exact OkHttp5 artifact.
        interceptors().clear()
        networkInterceptors().clear()
    }.authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .cookieJar(CookieJar.NO_COOKIES).cache(null)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS).readTimeout(timeoutMillis, TimeUnit.MILLISECONDS).build()

    override suspend fun sendCode(email: String): Int = request("/auth/register/code",
        JSONObject().put("email", email))

    override suspend fun register(email: String, code: String, password: String): Int = request("/auth/register",
        JSONObject().put("email", email).put("code", code).put("password", password))

    private suspend fun request(path: String, body: JSONObject): Int {
        val request = try {
            Request.Builder().url(baseUrl.trimEnd('/') + path).post(body.toString().toRequestBody(JSON))
                .header("Accept", "application/json").build()
        } catch (error: Exception) {
            throw RegistrationRequestBuildException(error)
        }
        val responseEntered = AtomicBoolean(false)
        val result = withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine<Int> { continuation ->
                val finished = AtomicBoolean(false)
                val call = http.newCall(request)
                fun fail(error: IOException, bodyRead: Boolean = false) {
                    if (!finished.compareAndSet(false, true)) return
                    call.cancel()
                    continuation.resumeWithException(when {
                        bodyRead -> RegistrationBodyReadException(error)
                        error is InterruptedIOException -> RegistrationTimeoutException()
                        else -> RegistrationTransportException(error)
                    })
                }
                continuation.invokeOnCancellation { finished.set(true); call.cancel() }
                if (finished.get()) return@suspendCancellableCoroutine
                try {
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) { fail(e) }
                        override fun onResponse(call: Call, response: Response) {
                            try {
                                val status = response.use {
                                    if (finished.get()) return
                                    responseEntered.set(true)
                                    // Source r2 reads before status/UI dispatch. IOException
                                    // here never becomes the source onFailure notification.
                                    response.body?.string()
                                    response.code
                                }
                                if (finished.compareAndSet(false, true)) continuation.resume(status)
                            } catch (error: IOException) { fail(error, bodyRead = true) }
                        }
                    })
                } catch (error: Exception) {
                    if (finished.compareAndSet(false, true)) {
                        call.cancel()
                        continuation.resumeWithException(RegistrationRequestBuildException(error))
                    }
                }
            }
        } ?: if (responseEntered.get()) {
            // Same own deadline, retaining the already-entered response phase.
            throw RegistrationBodyReadException(InterruptedIOException("Registration body read timed out"))
        } else throw RegistrationTimeoutException()
        currentCoroutineContext().ensureActive()
        return result
    }

    private companion object { val JSON = "application/json".toMediaType() }
}
