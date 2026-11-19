package com.projectfocus.core.network.subscription

import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.SubscriptionStatusSnapshot
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Project-owned GET/ApiEnvelope/JWT adapter; never an original-service URL.
 * No premium or cache fallback is synthesized here. The provider owns its cache
 * and must also check its account generation when committing a returned result.
 */
class HttpSubscriptionStatusGateway(
    client: OkHttpClient,
    baseUrl: String,
    private val identity: () -> TokenStore.Snapshot = TokenStore::snapshot,
    private val timeoutMillis: Long = 12_000,
) : SubscriptionStatusGateway {
    private val endpoint = requireNotNull(baseUrl.toHttpUrl().resolve("subscription/status"))
    private val http = client.newBuilder().cache(null).followRedirects(false)
        .followSslRedirects(false).retryOnConnectionFailure(false).build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    init { require(timeoutMillis > 0) }

    override suspend fun fetch(): SubscriptionStatusSnapshot? {
        val account = identity()
        val token = account.token?.takeIf(String::isNotBlank) ?: throw SubscriptionStatusAuthenticationException()
        val request = Request.Builder().url(endpoint).get().header("Accept", "application/json")
            .header("Authorization", "Bearer $token").header("Cache-Control", "no-cache, no-store").build()

        // withTimeoutOrNull propagates caller cancellation/outer deadlines unchanged.
        return (withTimeoutOrNull(timeoutMillis) { Received(await(http.newCall(request), account)) }
            ?: throw SubscriptionStatusTimeoutException()).snapshot
    }

    private data class Received(val snapshot: SubscriptionStatusSnapshot?)

    private suspend fun await(call: Call, account: TokenStore.Snapshot): SubscriptionStatusSnapshot? =
        suspendCancellableCoroutine { continuation ->
            val done = AtomicBoolean(false)
            fun fail(error: Throwable) {
                if (done.compareAndSet(false, true)) {
                    call.cancel()
                    continuation.resumeWithException(error)
                }
            }
            fun ensureAccount(): Boolean {
                if (identity() == account) return true
                fail(SubscriptionStatusIdentityChangedException())
                return false
            }
            continuation.invokeOnCancellation { done.set(true); call.cancel() }
            if (done.get() || !ensureAccount()) return@suspendCancellableCoroutine
            try {
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        if (done.get() || !ensureAccount()) return
                        fail(if (error is InterruptedIOException) SubscriptionStatusTimeoutException(error)
                            else SubscriptionStatusNetworkException(error))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            if (done.get() || !ensureAccount()) return
                            if (!response.isSuccessful) { fail(SubscriptionStatusHttpException(response.code)); return }
                            try {
                                val result = parse(response.body.string())
                                if (!done.get() && ensureAccount() && done.compareAndSet(false, true)) {
                                    continuation.resume(result)
                                }
                            } catch (error: SubscriptionStatusParseException) {
                                if (ensureAccount()) fail(error)
                            } catch (error: IOException) {
                                if (ensureAccount()) fail(if (error is InterruptedIOException)
                                    SubscriptionStatusTimeoutException(error) else SubscriptionStatusNetworkException(error))
                            }
                        }
                    }
                })
            } catch (error: Exception) {
                if (ensureAccount()) fail(SubscriptionStatusNetworkException(IOException("Could not start subscription call", error)))
            }
        }

    /** Strict own envelope/types; only the source qualification rule is asserted as original. */
    private fun parse(text: String): SubscriptionStatusSnapshot? {
        return try {
            val root = json.parseToJsonElement(text) as? JsonObject ?: throw SubscriptionStatusParseException()
            val success = root["success"]
            if (success != null && success != JsonNull) {
                val flag = (success as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                    ?: throw SubscriptionStatusParseException()
                if (!flag) return null
            }
            val error = root["error"]
            if (error != null && error != JsonNull) {
                if (error !is JsonObject) throw SubscriptionStatusParseException()
                return null
            }
            val data = root["data"] ?: return null
            if (data == JsonNull) return null
            val payload = data as? JsonObject ?: throw SubscriptionStatusParseException()
            fun flag(name: String): Boolean = (payload[name] as? JsonPrimitive)?.takeIf { !it.isString }
                ?.booleanOrNull ?: throw SubscriptionStatusParseException()
            val days = (payload["remainingDays"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                ?: throw SubscriptionStatusParseException()
            val expiry = payload["expiresAt"] ?: throw SubscriptionStatusParseException()
            val expires = if (expiry == JsonNull) null else (expiry as? JsonPrimitive)?.takeIf { it.isString }
                ?.content ?: throw SubscriptionStatusParseException()
            SubscriptionStatusSnapshot(flag("isActive"), flag("isPremium"), days, expires)
        } catch (error: SubscriptionStatusParseException) {
            throw error
        } catch (error: Exception) {
            throw SubscriptionStatusParseException(error)
        }
    }
}
