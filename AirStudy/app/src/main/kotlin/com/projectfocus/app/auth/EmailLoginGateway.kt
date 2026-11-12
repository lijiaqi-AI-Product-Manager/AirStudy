package com.projectfocus.app.auth

import com.projectfocus.core.network.di.NetworkCore
import com.projectfocus.core.network.di.NetworkModule.FlightPlanOkHttpClient
import com.projectfocus.core.network.di.TokenStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class EmailAccount(val id: String, val email: String, val token: String)

class EmailLoginRejectedException(val status: Int) : IOException("Email sign-in rejected")
class EmailLoginIdentityChangedException : IOException("Account changed during sign-in")

/** Original input fields and password mode; own ApiEnvelope/JWT response adapter. */
@Singleton
class OwnEmailLoginGateway internal constructor(
    client: OkHttpClient,
    baseUrl: String,
    private val timeoutMillis: Long = 12_000L,
    private val identity: () -> TokenStore.Snapshot = TokenStore::snapshot,
) {
    @Inject constructor(core: NetworkCore, @FlightPlanOkHttpClient client: OkHttpClient) : this(client, core.baseUrl)
    private val http = client.newBuilder().cache(null).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()
    private val endpoint = baseUrl.trimEnd('/')+"/auth/login"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun login(email: String, password: String): EmailAccount {
        // CustomInputView.getText() is Editable.toString: no trimming of either field.
        val captured = identity()
        val payload = buildJsonObject { put("loginType", "email"); put("email", email); put("password", password) }
        val request = Request.Builder().url(endpoint).post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json").header("Cache-Control", "no-store").build()
        val result = withTimeout(timeoutMillis) {
            suspendCancellableCoroutine<EmailAccount> { continuation ->
                val done = AtomicBoolean(false)
                val call = http.newCall(request)
                fun fail(error: Throwable) { if (done.compareAndSet(false, true)) { call.cancel(); continuation.resumeWithException(error) } }
                continuation.invokeOnCancellation { done.set(true); call.cancel() }
                if (done.get()) return@suspendCancellableCoroutine
                if (captured != identity()) { fail(EmailLoginIdentityChangedException()); return@suspendCancellableCoroutine }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { fail(e) }
                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            if (done.get()) return
                            if (captured != identity()) { fail(EmailLoginIdentityChangedException()); return }
                            if (!response.isSuccessful) { fail(EmailLoginRejectedException(response.code)); return }
                            try {
                                val root = json.parseToJsonElement(response.body.string()).jsonObject
                                val success = root["success"]
                                val flag = if (success == null || success == JsonNull) null else
                                    (success as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                                        ?: throw IOException("Invalid sign-in envelope")
                                if (flag == false || (root["error"] != null && root["error"] != JsonNull)) {
                                    fail(EmailLoginRejectedException(response.code)); return
                                }
                                val data = root["data"] as? JsonObject ?: throw IOException("Missing sign-in payload")
                                fun text(name: String): String = (data[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
                                    ?.takeIf(String::isNotEmpty) ?: throw IOException("Invalid sign-in payload")
                                val account = EmailAccount(text("id"), text("email"), text("token"))
                                if (captured != identity()) { fail(EmailLoginIdentityChangedException()); return }
                                if (done.compareAndSet(false, true)) continuation.resume(account)
                            } catch (error: Exception) { fail(IOException("Could not read sign-in response", error)) }
                        }
                    }
                })
            }
        }
        currentCoroutineContext().ensureActive()
        if (captured != identity()) throw EmailLoginIdentityChangedException()
        return result
    }
}
