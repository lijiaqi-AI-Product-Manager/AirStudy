package com.projectfocus.core.network.freeflight

import com.projectfocus.core.network.dto.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Captured bearer only. No automatic consume retry, cookie, redirect, cache or current-token interceptor. */
class HttpFreeFlightAdmissionGateway(
    client: OkHttpClient,
    baseUrl: String,
    /** Own network bound; independent of the source eight-second Pending UI boundary. */
    transportTimeoutMillis: Long = 60_000,
) : FreeFlightAdmissionGateway {
    private val base = (baseUrl.trimEnd('/') + "/").toHttpUrl()
    private val json = Json { ignoreUnknownKeys = true; isLenient = false; encodeDefaults = true }
    private val http = client.newBuilder().apply {
        interceptors().clear(); networkInterceptors().clear()
        authenticator(Authenticator.NONE); proxyAuthenticator(Authenticator.NONE); cookieJar(CookieJar.NO_COOKIES)
        cache(null); followRedirects(false); followSslRedirects(false); retryOnConnectionFailure(false)
        require(transportTimeoutMillis > 8_000)
        callTimeout(transportTimeoutMillis, TimeUnit.MILLISECONDS)
        readTimeout(transportTimeoutMillis, TimeUnit.MILLISECONDS)
    }.build()

    override suspend fun prepare(request: FreeFlightDeviceRequest, bearer: String?) =
        await(make("prepare", request, FreeFlightDeviceRequest.serializer(), bearer), FreeFlightPrepareReply.serializer())
    override fun submit(request: FreeFlightFormalRequest, bearer: String?, callback: (Result<FreeFlightFormalReply>) -> Unit) {
        try { enqueue(make("formal", request, FreeFlightFormalRequest.serializer(), bearer), FreeFlightFormalReply.serializer(), callback) }
        catch (error: Exception) { callback(Result.failure(error)) }
    }
    override suspend fun operation(request: FreeFlightOperationRequest, bearer: String?) =
        await(make("operation", request, FreeFlightOperationRequest.serializer(), bearer), FreeFlightOperationReply.serializer())
    override suspend fun localStartFailed(request: FreeFlightLocalFailureRequest, bearer: String?) =
        await(make("local-start-failed", request, FreeFlightLocalFailureRequest.serializer(), bearer), FreeFlightLocalFailureReply.serializer())

    private fun <T> make(path: String, value: T, serializer: SerializationStrategy<T>, bearer: String?): Call {
        val target = requireNotNull(base.resolve("freeflight/admission/$path"))
        val builder = Request.Builder().url(target).post(json.encodeToString(serializer, value).toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "application/json").header("Cache-Control", "no-cache, no-store")
        bearer?.takeIf(String::isNotBlank)?.let { builder.header("Authorization", "Bearer $it") }
        return http.newCall(builder.build())
    }
    private fun <T> enqueue(call: Call, serializer: KSerializer<T>, callback: (Result<T>) -> Unit) {
        val done = AtomicBoolean(false)
        fun deliver(result: Result<T>) { if (done.compareAndSet(false, true)) callback(result) }
        try {
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { deliver(Result.failure(e)) }
                override fun onResponse(call: Call, response: Response) {
                    val result = response.use {
                        runCatching {
                            if (!it.isSuccessful) throw FreeFlightAdmissionHttpException(it.code)
                            val envelope = json.decodeFromString(ApiEnvelope.serializer(serializer), it.body.string())
                            if (envelope.success != true || envelope.error != null || envelope.data == null) throw FreeFlightAdmissionProtocolException()
                            requireNotNull(envelope.data)
                        }
                    }
                    deliver(result)
                }
            })
        } catch (error: Exception) { deliver(Result.failure(error)) }
    }
    private suspend fun <T> await(call: Call, serializer: KSerializer<T>): T = suspendCancellableCoroutine { continuation ->
        val closed = AtomicBoolean(false)
        continuation.invokeOnCancellation { closed.set(true); call.cancel() }
        enqueue(call, serializer) { result ->
            if (closed.compareAndSet(false, true)) result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
        }
    }
}

class FreeFlightAdmissionHttpException(val status: Int) : IOException("Free-flight admission transport was not successful")
class FreeFlightAdmissionProtocolException : IOException("Free-flight admission response was invalid")
