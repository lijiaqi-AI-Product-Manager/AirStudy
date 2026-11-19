package com.projectfocus.core.network.subscription

import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.network.di.TokenStore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual own HTTP/OkHttp adapter; fixture responses are never production entitlements. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SubscriptionStatusGatewayTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val account = AtomicReference(TokenStore.Snapshot("subscription-test-token", 1))

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        client = OkHttpClient.Builder().build()
    }
    @After fun teardown() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
        server.close()
    }

    @Test fun realGetUsesCapturedJwtAndOwnFourFieldEnvelopeWithoutParsingTheOpaqueExpiry() = runBlocking {
        respond("""{"success":true,"data":{"isActive":true,"isPremium":true,"remainingDays":2,
            "expiresAt":"opaque-cache-value","ignored":"future-field"}}""")
        val status = gateway().fetch()!!
        assertThat(status.isEligible).isTrue()
        assertThat(status.remainingDays).isEqualTo(2)
        assertThat(status.expiresAt).isEqualTo("opaque-cache-value")
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.url.encodedPath).isEqualTo("/subscription/status")
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer subscription-test-token")
        assertThat(request.headers["Cache-Control"]).contains("no-store")
    }

    @Test fun explicitRejectionAndMissingPayloadReturnNullButACompleteDeniedSnapshotRemainsAResult() = runBlocking {
        for (text in listOf("{}", """{"data":null}""", """{"success":false,"data":{"bad":"not-read"}}""",
            """{"error":{"code":1001},"data":{"bad":"not-read"}}""")) {
            respond(text)
            assertThat(gateway().fetch()).isNull()
        }
        respond("""{"data":{"isActive":false,"isPremium":false,"remainingDays":0,"expiresAt":null}}""")
        val denied = gateway().fetch()!!
        assertThat(denied.isEligible).isFalse()
        assertThat(denied.expiresAt).isNull()
        respond("""{"data":{"isActive":true,"isPremium":true,"remainingDays":-2,"expiresAt":"past"}}""")
        assertThat(gateway().fetch()!!.isEligible).isFalse()
    }

    @Test fun parseFailureIsDistinctFromMissingPayloadAndNeverSynthesizesFalseOrPremium() = runBlocking {
        val invalid = listOf("not-json", "[]", """{"success":"false"}""", """{"data":[]}""",
            """{"data":{}}""", """{"error":"wrong-type"}""",
            """{"data":{"isActive":"true","isPremium":true,"remainingDays":1,"expiresAt":null}}""",
            """{"data":{"isActive":true,"isPremium":true,"remainingDays":"1","expiresAt":null}}""",
            """{"data":{"isActive":true,"isPremium":true,"remainingDays":1.5,"expiresAt":null}}""",
            """{"data":{"isActive":true,"isPremium":true,"remainingDays":1,"expiresAt":123}}""")
        for (text in invalid) {
            respond(text)
            assertThat(failure { gateway().fetch() }).isInstanceOf(SubscriptionStatusParseException::class.java)
        }
    }

    @Test fun httpTransportAndOwnTimeoutHaveDifferentTypesAndDoNotBecomeNull() = runBlocking {
        server.enqueue(MockResponse.Builder().code(503).body("not-a-success-envelope").build())
        val http = failure { gateway().fetch() } as SubscriptionStatusHttpException
        assertThat(http.statusCode).isEqualTo(503)
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        assertThat(failure { gateway().fetch() }).isInstanceOf(SubscriptionStatusNetworkException::class.java)
        server.enqueue(MockResponse.Builder().body(valid).headersDelay(400, TimeUnit.MILLISECONDS).build())
        assertThat(failure { gateway(100).fetch() }).isInstanceOf(SubscriptionStatusTimeoutException::class.java)
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test fun identicalTokenWithNewAccountGenerationRejectsTheLateReply() = runBlocking {
        server.enqueue(MockResponse.Builder().body(valid).headersDelay(350, TimeUnit.MILLISECONDS).build())
        val pending = async(Dispatchers.Default) { failure { gateway().fetch() } }
        assertThat(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) }).isNotNull()
        account.set(TokenStore.Snapshot("subscription-test-token", 2))
        assertThat(pending.await()).isInstanceOf(SubscriptionStatusIdentityChangedException::class.java)
    }

    @Test fun callerCancellationAndOuterDeadlinePropagateAndMissingAuthenticationNeverSends() = runBlocking {
        server.enqueue(MockResponse.Builder().body(valid).headersDelay(350, TimeUnit.MILLISECONDS).build())
        val canceled = async(Dispatchers.Default) { gateway().fetch() }
        assertThat(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) }).isNotNull()
        canceled.cancelAndJoin()
        assertThat(canceled.isCancelled).isTrue()
        server.enqueue(MockResponse.Builder().body(valid).headersDelay(350, TimeUnit.MILLISECONDS).build())
        assertThat(failure { withTimeout(100) { gateway(5_000).fetch() } })
            .isInstanceOf(TimeoutCancellationException::class.java)
        val requests = server.requestCount
        account.set(TokenStore.Snapshot(null, 2))
        assertThat(failure { gateway().fetch() }).isInstanceOf(SubscriptionStatusAuthenticationException::class.java)
        assertThat(server.requestCount).isEqualTo(requests)
    }

    private fun gateway(timeoutMillis: Long = 3_000) = HttpSubscriptionStatusGateway(client,
        server.url("/").toString(), account::get, timeoutMillis)
    private fun respond(text: String) { server.enqueue(MockResponse.Builder().body(text).build()) }
    private suspend fun failure(block: suspend () -> Any?): Throwable {
        try { block() } catch (error: Throwable) { return error }
        throw AssertionError("Expected classified subscription failure")
    }
    private val valid = """{"data":{"isActive":true,"isPremium":true,"remainingDays":1,"expiresAt":"2026-11-01T00:00:00Z"}}"""
}
