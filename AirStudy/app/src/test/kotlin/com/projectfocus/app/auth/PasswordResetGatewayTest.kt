package com.projectfocus.app.auth

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.network.di.TokenStore
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
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
import mockwebserver3.SocketEffect
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual anonymous HTTP and framing, never a fake status gateway or the source service. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PasswordResetGatewayTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private var serverClosed = false
    private val canceled = CountDownLatch(1)
    private val headers = ConcurrentLinkedQueue<Triple<String, Int, String?>>()
    private var release: CountDownLatch? = null

    @Before fun setUp() {
        TokenStore.clear()
        server = MockWebServer().apply { start() }
        client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun responseHeadersEnd(call: Call, response: Response) {
                headers += Triple(call.request().url.encodedPath, response.code, response.header("Content-Length"))
            }
            override fun callFailed(call: Call, ioe: IOException) { if (call.isCanceled()) canceled.countDown() }
        }).build()
    }
    @After fun tearDown() {
        release?.countDown()
        client.dispatcher.cancelAll()
        if (!serverClosed) server.close()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
        TokenStore.clear()
    }

    @Test fun anonymousRawWire() = runBlocking {
        TokenStore.set("existing-own-identity")
        val identity = TokenStore.snapshot()
        val inherited = AtomicInteger()
        val supplied = client.newBuilder().addInterceptor { chain ->
            inherited.incrementAndGet()
            chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer must-not-leak").build())
        }.build()
        val gateway = OwnPasswordResetGateway(supplied, server.url("/").toString())
        server.enqueue(MockResponse.Builder().code(200).body("not JSON").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"token":"must-not-save","data":{"id":"ignored"}}""").build())
        assertThat(gateway.sendCode(" user@example.test ")).isEqualTo(200)
        assertThat(gateway.reset(" user@example.test ", " alpha-code ", "  raw-new-password  ")).isEqualTo(200)
        val send = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        val reset = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        assertThat(send.url.encodedPath).isEqualTo("/auth/password-reset/code")
        assertThat(reset.url.encodedPath).isEqualTo("/auth/password-reset")
        val first = JSONObject(checkNotNull(send.body).utf8())
        val second = JSONObject(checkNotNull(reset.body).utf8())
        assertThat(first.keys().asSequence().toSet()).containsExactly("email")
        assertThat(first.getString("email")).isEqualTo(" user@example.test ")
        assertThat(second.keys().asSequence().toSet()).containsExactly("email", "code", "new_password")
        assertThat(second.getString("email")).isEqualTo(" user@example.test ")
        assertThat(second.getString("code")).isEqualTo(" alpha-code ")
        assertThat(second.getString("new_password")).isEqualTo("  raw-new-password  ")
        for (request in listOf(send, reset)) {
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.headers["Accept"]).isEqualTo("application/json")
            assertThat(request.headers["Content-Type"]).startsWith("application/json")
            assertThat(request.headers["Authorization"]).isNull()
            assertThat(request.headers["Cookie"]).isNull()
        }
        assertThat(inherited.get()).isEqualTo(0)
        assertThat(TokenStore.snapshot()).isEqualTo(identity)
    }

    @Test fun truncated200IsSuccess() = runBlocking {
        val gateway = OwnPasswordResetGateway(client, server.url("/").toString())
        repeat(2) {
            server.enqueue(MockResponse.Builder().code(200).body("short").setHeader("Content-Length", "4096")
                .setHeader("Connection", "close").onResponseEnd(SocketEffect.CloseSocket()).build())
        }
        assertThat(gateway.sendCode("user@example.test")).isEqualTo(200)
        assertThat(gateway.reset("user@example.test", "abc", "12345678")).isEqualTo(200)
        assertThat(headers).contains(Triple("/auth/password-reset/code", 200, "4096"))
        assertThat(headers).contains(Triple("/auth/password-reset", 200, "4096"))
        val first = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        val second = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        assertThat(first.url.encodedPath).isEqualTo("/auth/password-reset/code")
        assertThat(second.url.encodedPath).isEqualTo("/auth/password-reset")
        assertThat(TokenStore.peek()).isNull()
    }

    @Test fun statusWithoutBodyParse() = runBlocking {
        val gateway = OwnPasswordResetGateway(client, server.url("/").toString())
        for (status in listOf(200, 201, 302, 400, 404, 429, 503)) {
            server.enqueue(MockResponse.Builder().code(status).body("not a JSON envelope").build())
            assertThat(gateway.reset("user@example.test", "abc", "12345678")).isEqualTo(status)
        }
        assertThat(server.requestCount).isEqualTo(7)
    }

    @Test fun cancellationOwnBoundary() = runBlocking {
        val seen = CountDownLatch(1)
        val gate = CountDownLatch(1).also { release = it }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen.countDown()
                check(gate.await(5, TimeUnit.SECONDS))
                return MockResponse.Builder().code(200).body("ignored").build()
            }
        }
        val resumed = AtomicBoolean(false)
        val gateway = OwnPasswordResetGateway(client, server.url("/").toString())
        val pending = async(Dispatchers.Default) { gateway.reset("user@example.test", "abc", "12345678"); resumed.set(true) }
        assertThat(seen.await(3, TimeUnit.SECONDS)).isTrue()
        pending.cancelAndJoin()
        assertThat(canceled.await(3, TimeUnit.SECONDS)).isTrue()
        gate.countDown()
        assertThat(pending.isCancelled).isTrue()
        assertThat(resumed.get()).isFalse()
    }

    @Test fun failuresOwnBoundary() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("ignored").headersDelay(1, TimeUnit.SECONDS).build())
        try {
            OwnPasswordResetGateway(client, server.url("/").toString(), 120).sendCode("user@example.test")
            throw AssertionError("Expected own timeout")
        } catch (_: PasswordResetTimeoutException) { }
        try {
            OwnPasswordResetGateway(client, "not-an-http-base").sendCode("user@example.test")
            throw AssertionError("Expected own request construction failure")
        } catch (_: PasswordResetRequestBuildException) { }
        val endpoint = server.url("/").toString()
        server.close(); serverClosed = true
        try {
            OwnPasswordResetGateway(client, endpoint, 1_000).sendCode("user@example.test")
            throw AssertionError("Expected actual closed loopback failure")
        } catch (_: PasswordResetTransportException) { }
    }
}
