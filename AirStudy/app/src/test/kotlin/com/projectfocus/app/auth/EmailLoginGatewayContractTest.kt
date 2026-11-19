package com.projectfocus.app.auth

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.AuthUserDto
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real loopback HTTP; own flat AuthUserDto adapter, not the original service envelope. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class EmailLoginGatewayContractTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val identity = AtomicReference(TokenStore.Snapshot("email-gateway-existing", 1))
    private val callCanceled = CountDownLatch(1)
    private var releaseResponse: CountDownLatch? = null

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callFailed(call: Call, ioe: IOException) {
                if (call.isCanceled()) callCanceled.countDown()
            }
        }).build()
    }

    @After fun tearDown() {
        releaseResponse?.countDown()
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
        server.close()
    }

    @Test fun `POST preserves both input strings and accepts own missing or null success envelope`() = runBlocking {
        val email = "  Reader+tag@Example.test  "
        val password = "  pA ss\\word+秘密  "
        val user = AuthUserDto("own-user-17", "reader@example.test", "own-response-token")
        for (explicitNull in listOf(false, true)) {
            val envelope = buildJsonObject {
                if (explicitNull) put("success", JsonNull)
                put("data", Json.encodeToJsonElement(AuthUserDto.serializer(), user))
            }
            respond(envelope.toString())
            assertThat(gateway().login(email, password)).isEqualTo(EmailAccount(user.id, user.email, user.token))
            val request = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.url.encodedPath).isEqualTo("/auth/login")
            assertThat(request.headers["Content-Type"]).contains("application/json")
            assertThat(request.headers["Cache-Control"]).contains("no-store")
            assertThat(Json.parseToJsonElement(request.body!!.utf8()).jsonObject).isEqualTo(buildJsonObject {
                put("loginType", "email"); put("email", email); put("password", password)
            })
        }
    }

    @Test fun `HTTP 401 and explicit own envelope denials are rejected despite valid account data`() = runBlocking {
        val replies = listOf(
            401 to valid,
            200 to """{"success":false,"data":{"id":"u","email":"u@example.test","token":"denied"}}""",
            200 to """{"error":{"code":"DENIED"},"data":{"id":"u","email":"u@example.test","token":"denied"}}""",
        )
        for ((status, text) in replies) {
            server.enqueue(MockResponse.Builder().code(status).body(text).build())
            val error = failure { gateway().login("u@example.test", "fixture-password") }
            assertThat(error).isInstanceOf(EmailLoginRejectedException::class.java)
            assertThat((error as EmailLoginRejectedException).status).isEqualTo(status)
        }
    }

    @Test fun `bad or hypothetical nested payload cannot become an own flat account`() = runBlocking {
        val replies = listOf("not-json", "[]", "{}", """{"data":[]}""",
            """{"success":"true","data":{"id":"u","email":"u@e.test","token":"t"}}""",
            """{"data":{"id":17,"email":"u@e.test","token":"t"}}""",
            """{"data":{"id":"u","email":"","token":"t"}}""",
            """{"data":{"id":"u","email":"u@e.test","token":""}}""",
            """{"data":{"token":"t","user":{"id":17,"email":"u@e.test"}}}""")
        for (text in replies) {
            respond(text)
            val error = failure { gateway().login("u@example.test", "fixture-password") }
            assertThat(error).isInstanceOf(IOException::class.java)
            assertThat(error).isNotInstanceOf(EmailLoginRejectedException::class.java)
        }
    }

    @Test fun `same token with a later identity generation rejects a real late reply`() = runBlocking {
        val seen = CountDownLatch(1)
        val release = CountDownLatch(1).also { releaseResponse = it }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "test did not release HTTP response" }
                return MockResponse.Builder().body(valid).build()
            }
        }
        val pending = async(Dispatchers.Default) { failure { gateway().login("u@example.test", "fixture-password") } }
        try {
            assertThat(seen.await(3, TimeUnit.SECONDS)).isTrue()
            identity.set(TokenStore.Snapshot("email-gateway-existing", 2))
        } finally { release.countDown() }
        assertThat(pending.await()).isInstanceOf(EmailLoginIdentityChangedException::class.java)
    }

    @Test fun `caller cancellation cancels the real call instead of returning an account`() = runBlocking {
        server.enqueue(MockResponse.Builder().body(valid).headersDelay(1, TimeUnit.SECONDS).build())
        val pending = async(Dispatchers.Default) { gateway().login("u@example.test", "fixture-password") }
        assertThat(server.takeRequest(3, TimeUnit.SECONDS)).isNotNull()
        pending.cancelAndJoin()
        assertThat(pending.isCancelled).isTrue()
        assertThat(callCanceled.await(3, TimeUnit.SECONDS)).isTrue()
    }

    @Test fun `own deadline remains cancellation typed and cancels the HTTP call`() = runBlocking {
        server.enqueue(MockResponse.Builder().body(valid).headersDelay(1, TimeUnit.SECONDS).build())
        assertThat(failure { gateway(timeoutMillis = 150).login("u@example.test", "fixture-password") })
            .isInstanceOf(TimeoutCancellationException::class.java)
        assertThat(callCanceled.await(3, TimeUnit.SECONDS)).isTrue()
    }

    private fun gateway(timeoutMillis: Long = 3_000) = OwnEmailLoginGateway(client,
        server.url("/").toString(), timeoutMillis, identity::get)
    private fun respond(text: String) { server.enqueue(MockResponse.Builder().body(text).build()) }
    private suspend fun failure(block: suspend () -> Any?): Throwable {
        try { block() } catch (error: Throwable) { return error }
        throw AssertionError("Expected sign-in failure")
    }
    private val valid = """{"data":{"id":"own-user","email":"own@example.test","token":"own-token"}}"""
}
