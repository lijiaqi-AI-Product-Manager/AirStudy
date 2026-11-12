package com.projectfocus.core.network.freeflight

import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.network.dto.*
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Candidate only: actual own loopback HTTP/OkHttp; no original host or remote credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HttpFreeFlightAdmissionGatewayTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        client = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer wrong-current-token").build())
        }.build()
    }
    @After fun teardown() {
        client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow(); server.close()
    }

    @Test fun prepareAndFormalUseTheExactCapturedAccountAndDeviceOnlyInPostBodies() = runBlocking {
        val gateway = gateway()
        server.enqueue(MockResponse.Builder().body("""{"success":true,"data":{"allowed":true,"source":"DEVICE","reason":"ALLOWED","remainingUses":1,"totalUses":1}}""").build())
        assertThat(gateway.prepare(device(), "captured-account").allowed).isTrue()
        val prepared = server.takeRequest()
        assertThat(prepared.url.encodedPath).isEqualTo("/freeflight/admission/prepare")
        assertThat(prepared.url.query).isNull()
        assertThat(prepared.headers["Authorization"]).isEqualTo("Bearer captured-account")
        assertThat(Json.parseToJsonElement(prepared.body!!.utf8()).jsonObject["credential"]!!.jsonPrimitive.content).isEqualTo(credential)

        val request = formal()
        server.enqueue(MockResponse.Builder().body(envelope(request)).build())
        val latch = CountDownLatch(1); val reply = AtomicReference<Result<FreeFlightFormalReply>>()
        gateway.submit(request, null) { reply.set(it); latch.countDown() }
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(reply.get().getOrThrow().remainingUses).isEqualTo(0)
        val submitted = server.takeRequest()
        assertThat(submitted.method).isEqualTo("POST")
        assertThat(submitted.url.encodedPath).isEqualTo("/freeflight/admission/formal")
        assertThat(submitted.headers["Authorization"]).isNull()
        assertThat(submitted.headers["Cache-Control"]).contains("no-store")
    }

    @Test fun formalActualCallRemainsAliveBeyondEightSecondsForItsRealLateCallback() {
        val request = formal()
        server.enqueue(MockResponse.Builder().body(envelope(request)).headersDelay(8_050, TimeUnit.MILLISECONDS).build())
        val latch = CountDownLatch(1); val reply = AtomicReference<Result<FreeFlightFormalReply>>()
        gateway().submit(request, "captured-account") { reply.set(it); latch.countDown() }
        assertThat(server.takeRequest(3, TimeUnit.SECONDS)).isNotNull()
        assertThat(latch.await(3, TimeUnit.SECONDS)).isFalse()
        assertThat(latch.await(8, TimeUnit.SECONDS)).isTrue()
        assertThat(reply.get().getOrThrow().approved).isTrue()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun redirectsAndServiceErrorsAreNotAutomaticallyRetriedOrTreatedAsQuotaRejections() {
        for (response in listOf(MockResponse.Builder().code(307).addHeader("Location", server.url("/other")).build(),
            MockResponse.Builder().code(503).body("maintenance").build())) {
            server.enqueue(response)
            val latch = CountDownLatch(1); val reply = AtomicReference<Result<FreeFlightFormalReply>>()
            gateway().submit(formal(), null) { reply.set(it); latch.countDown() }
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(reply.get().exceptionOrNull()).isInstanceOf(FreeFlightAdmissionHttpException::class.java)
        }
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun missingOrFalseEnvelopeCannotApproveAndQueriesKeepTheSameOperation() = runBlocking {
        for (body in listOf("{}", """{"success":true,"data":null}""", """{"success":false,"data":{}}""")) {
            server.enqueue(MockResponse.Builder().body(body).build())
            assertThat(failure { gateway().prepare(device(), null) }).isInstanceOf(Exception::class.java)
        }
        server.enqueue(MockResponse.Builder().body("""{"success":true,"data":{"found":false,"decision":null,"localStartFailureRecorded":false}}""").build())
        val operationId = UUID.randomUUID().toString()
        assertThat(gateway().operation(FreeFlightOperationRequest(hwid, credential, operationId), "captured-account").found).isFalse()
        repeat(3) { server.takeRequest() }
        val query = server.takeRequest()
        assertThat(query.method).isEqualTo("POST")
        assertThat(query.url.encodedPath).isEqualTo("/freeflight/admission/operation")
        assertThat(Json.parseToJsonElement(query.body!!.utf8()).jsonObject["operationId"]!!.jsonPrimitive.content).isEqualTo(operationId)
    }

    private fun gateway() = HttpFreeFlightAdmissionGateway(client, server.url("/").toString())
    private suspend fun failure(block: suspend () -> Any?): Throwable { try { block() } catch (error: Throwable) { return error }; throw AssertionError("Expected failure") }
    private fun envelope(request: FreeFlightFormalRequest) = """{"success":true,"data":{"operationId":"${request.operationId}","sessionId":"${request.sessionId}","requestFingerprint":"${request.requestFingerprint}","approved":true,"source":"DEVICE","reason":"ALLOWED","remainingUses":0,"decidedAtMillis":1000}}"""
    companion object {
        private val hwid = "a".repeat(64)
        private val credential = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 })
        private fun device() = FreeFlightDeviceRequest(hwid, credential)
        private fun formal() = FreeFlightFormalRequest(hwid, credential, UUID.randomUUID().toString(), "session-" + UUID.randomUUID(), "c".repeat(64))
    }
}
