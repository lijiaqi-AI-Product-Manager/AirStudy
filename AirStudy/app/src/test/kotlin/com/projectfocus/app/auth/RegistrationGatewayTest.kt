package com.projectfocus.app.auth

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.network.di.TokenStore
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real project HTTP adapter; never the source service, mail relay, or a mocked success gateway. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RegistrationGatewayTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private var closedServer = false
    private val canceled = CountDownLatch(1)
    private var release: CountDownLatch? = null

    @Before fun setUp() {
        TokenStore.clear()
        server = MockWebServer().apply { start() }
        client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callFailed(call: Call, ioe: IOException) {
                if (call.isCanceled()) canceled.countDown()
            }
        }).build()
    }

    @After fun tearDown() {
        release?.countDown()
        client.dispatcher.cancelAll()
        if (!closedServer) server.close()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
        TokenStore.clear()
    }

    @Test fun `actual routes preserve passed fields use anonymous JSON and do not publish response account`() = runBlocking {
        TokenStore.set("existing-own-account-token")
        val identity = TokenStore.snapshot()
        val inherited = AtomicInteger()
        val suppliedClient = client.newBuilder().addInterceptor { chain ->
            inherited.incrementAndGet()
            chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer must-not-leak").build())
        }.build()
        val gateway = OwnRegistrationGateway(suppliedClient, server.url("/").toString())
        server.enqueue(MockResponse.Builder().code(200).body("this body is deliberately not JSON").build())
        server.enqueue(MockResponse.Builder().code(201).body("""{"token":"not-an-authentication-result","data":{"id":"ignored"}}""").build())
        assertThat(gateway.sendCode(" \tuser@example.test ")).isEqualTo(200)
        assertThat(gateway.register(" user@example.test ", " 001234 ", "  raw-password  ")).isEqualTo(201)
        val send = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        val register = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        assertThat(send.url.encodedPath).isEqualTo("/auth/register/code")
        assertThat(register.url.encodedPath).isEqualTo("/auth/register")
        val first = JSONObject(checkNotNull(send.body).utf8())
        val second = JSONObject(checkNotNull(register.body).utf8())
        assertThat(first.keys().asSequence().toSet()).containsExactly("email")
        assertThat(first.getString("email")).isEqualTo(" \tuser@example.test ")
        assertThat(second.keys().asSequence().toSet()).containsExactly("email", "code", "password")
        assertThat(second.getString("email")).isEqualTo(" user@example.test ")
        assertThat(second.getString("code")).isEqualTo(" 001234 ")
        assertThat(second.getString("password")).isEqualTo("  raw-password  ")
        for (request in listOf(send, register)) {
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.headers["Accept"]).isEqualTo("application/json")
            assertThat(request.headers["Content-Type"]).startsWith("application/json")
            assertThat(request.headers["Authorization"]).isNull()
            assertThat(request.headers["Cookie"]).isNull()
        }
        assertThat(inherited.get()).isEqualTo(0)
        assertThat(TokenStore.snapshot()).isEqualTo(identity)
    }

    @Test fun `HTTP statuses remain statuses regardless of envelope missing fields or malformed JSON`() = runBlocking {
        val gateway = OwnRegistrationGateway(client, server.url("/").toString())
        for (status in listOf(200, 201, 400, 403, 409, 429, 503)) {
            server.enqueue(MockResponse.Builder().code(status).body("not JSON at all").build())
            assertThat(gateway.register("user@example.test", "123456", "12345678")).isEqualTo(status)
        }
        assertThat(server.requestCount).isEqualTo(7)
        assertThat(TokenStore.peek()).isNull()
    }

    @Test fun `caller cancellation cancels its real request and does not resume on a late response`() = runBlocking {
        val seen = CountDownLatch(1)
        val gate = CountDownLatch(1).also { release = it }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen.countDown()
                check(gate.await(5, TimeUnit.SECONDS))
                return MockResponse.Builder().code(201).body("ignored").build()
            }
        }
        val resumed = AtomicBoolean(false)
        val gateway = OwnRegistrationGateway(client, server.url("/").toString())
        val pending = async(Dispatchers.Default) {
            gateway.register("user@example.test", "123456", "12345678")
            resumed.set(true)
        }
        assertThat(seen.await(3, TimeUnit.SECONDS)).isTrue()
        pending.cancelAndJoin()
        assertThat(canceled.await(3, TimeUnit.SECONDS)).isTrue()
        gate.countDown()
        assertThat(pending.isCancelled).isTrue()
        assertThat(resumed.get()).isFalse()
        assertThat(TokenStore.peek()).isNull()
    }

    @Test fun `timeout request construction and transport failure are distinct from an HTTP status`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("ignored").headersDelay(1, TimeUnit.SECONDS).build())
        try {
            OwnRegistrationGateway(client, server.url("/").toString(), 120).sendCode("user@example.test")
            throw AssertionError("Expected real request timeout")
        } catch (_: RegistrationTimeoutException) { }
        try {
            OwnRegistrationGateway(client, "not-an-http-base").sendCode("user@example.test")
            throw AssertionError("Expected construction failure")
        } catch (_: RegistrationRequestBuildException) { }
        val endpoint = server.url("/").toString()
        server.close(); closedServer = true
        try {
            OwnRegistrationGateway(client, endpoint, 1_000).sendCode("user@example.test")
            throw AssertionError("Expected closed loopback transport failure")
        } catch (_: RegistrationTransportException) { }
        assertThat(TokenStore.peek()).isNull()
    }
}
