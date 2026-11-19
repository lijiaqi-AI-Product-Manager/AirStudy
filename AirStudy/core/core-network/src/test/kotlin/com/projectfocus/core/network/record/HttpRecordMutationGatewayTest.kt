package com.projectfocus.core.network.record

import android.app.Application
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.FlightRecordMode
import com.projectfocus.core.network.dto.FlightRecordMutationOptions
import com.projectfocus.core.network.dto.FlightRecordScenePatch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class HttpRecordMutationGatewayTest {
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var gateway: HttpRecordMutationGateway
    @Before fun open() {
        TokenStore.set("record-owner-a")
        server = MockWebServer().also { it.start() }
        http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
        gateway = HttpRecordMutationGateway(http, server.url("/").toString())
    }
    @After fun close() {
        server.close()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
        TokenStore.clear()
    }
    private fun response(code: Int = 200, body: String = """{"success":true}""") =
        MockResponse.Builder().code(code).body(body).build()
    private fun request(): RecordedRequest = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))

    @Test fun sameIdentifierUsesSeparateModePathsAndCapturedAuthorization() = runBlocking {
        for (mode in FlightRecordMode.entries) {
            server.enqueue(response())
            assertEquals(RecordMutationOutcome.Acknowledged(), gateway.delete(TokenStore.snapshot(),mode,"same-id"))
            val sent = request()
            assertEquals("DELETE",sent.method)
            assertEquals("/v2/flight-records/${mode.path}/same-id",sent.url.encodedPath)
            assertEquals(0L,sent.bodySize)
            assertEquals("Bearer record-owner-a",sent.headers["Authorization"])
            assertEquals("AirStudy/1.0",sent.headers["User-Agent"])
        }
    }

    @Test fun sceneRequestKeepsTheTwoWireFieldsAndNormalizesOnlyItsIconType() = runBlocking {
        server.enqueue(response(body="""{"success":true,"data":{"revision":8}}"""))
        val result = gateway.changeScene(TokenStore.snapshot(),FlightRecordMode.UP," a/b 学习 ",
            FlightRecordScenePatch(0,"阅读与学习"),FlightRecordMutationOptions("op-record-42",7))
        assertEquals(RecordMutationOutcome.Acknowledged(revision=8),result)
        val sent = request()
        assertEquals("PATCH",sent.method)
        assertEquals("/v2/flight-records/up/a%2Fb+%E5%AD%A6%E4%B9%A0/focus-scene",sent.url.encodedPath)
        val body = JSONObject(requireNotNull(sent.body).utf8())
        assertEquals(setOf("focusIconType","focusScenario"),body.keys().asSequence().toSet())
        assertEquals(1,body.getInt("focusIconType"))
        assertEquals("阅读与学习",body.getString("focusScenario"))
        assertEquals("op-record-42",sent.headers["Idempotency-Key"])
        assertEquals("\"7\"",sent.headers["If-Match"])
    }

    @Test fun successfulHttpWithoutAcknowledgementDoesNotReportSuccess() = runBlocking {
        for (body in listOf("{}","""{"success":false,"message":"changed"}""","""{"success":null}""")) {
            server.enqueue(response(body=body))
            val result = gateway.delete(TokenStore.snapshot(),FlightRecordMode.DOWN,"record-a")
            assertTrue(result is RecordMutationOutcome.Rejected)
            assertEquals(200,(result as RecordMutationOutcome.Rejected).status)
        }
        for (body in listOf("not json","[]","")) {
            server.enqueue(response(body=body))
            assertEquals(RecordMutationOutcome.InvalidResponse,gateway.delete(TokenStore.snapshot(),FlightRecordMode.DOWN,"record-a"))
        }
        server.enqueue(response(204,""))
        assertEquals(RecordMutationOutcome.InvalidResponse,gateway.delete(TokenStore.snapshot(),FlightRecordMode.DOWN,"record-a"))
    }

    @Test fun acknowledgementUsesTheJsonBooleanCoercionOfTheRecordProtocol() = runBlocking {
        server.enqueue(response(body="""{"success":"TrUe"}"""))
        assertEquals(RecordMutationOutcome.Acknowledged(),gateway.delete(TokenStore.snapshot(),FlightRecordMode.DOWN,"record-a"))
        server.enqueue(response(body="""{"success":1}"""))
        assertTrue(gateway.delete(TokenStore.snapshot(),FlightRecordMode.DOWN,"record-a") is RecordMutationOutcome.Rejected)
    }

    @Test fun missingDeletedRecordIsAcknowledgedButMissingSceneTargetIsNot() = runBlocking {
        server.enqueue(response(404,"not json"))
        assertEquals(RecordMutationOutcome.Acknowledged(alreadyMissing=true),gateway.delete(TokenStore.snapshot(),FlightRecordMode.DOWN,"missing"))
        server.enqueue(response(404,"""{"success":false,"message":"missing"}"""))
        assertEquals(RecordMutationOutcome.Rejected(404,"missing"),gateway.changeScene(TokenStore.snapshot(),FlightRecordMode.UP,"missing",FlightRecordScenePatch(2,"工作")))
    }

    @Test fun errorHttpStatusesRemainFailuresEvenWhenTheBodySaysSuccess() = runBlocking {
        for (code in listOf(400,401,409,429,500,503)) {
            server.enqueue(response(code,"""{"success":true,"message":"rejected"}"""))
            assertEquals(RecordMutationOutcome.Rejected(code,"rejected",code==429||code>=500),
                gateway.delete(TokenStore.snapshot(),FlightRecordMode.DOWN,"record-a"))
        }
        assertEquals(6,server.requestCount)
    }

    @Test fun anonymousBlankAndObsoleteTargetsNeverReachTheServer() = runBlocking {
        val stale = TokenStore.snapshot()
        TokenStore.clear()
        assertEquals(RecordMutationOutcome.Rejected(401),gateway.delete(TokenStore.snapshot(),FlightRecordMode.DOWN,"record-a"))
        assertEquals(RecordMutationOutcome.Obsolete,gateway.delete(stale,FlightRecordMode.DOWN,"record-a"))
        TokenStore.set("record-owner-a")
        assertEquals(RecordMutationOutcome.Rejected(400),gateway.delete(TokenStore.snapshot(),FlightRecordMode.DOWN,"  "))
        TokenStore.set("bad\ncredential")
        assertEquals(RecordMutationOutcome.Rejected(-1),gateway.delete(TokenStore.snapshot(),FlightRecordMode.DOWN,"record-a"))
        assertEquals(0,server.requestCount)
    }

    private fun heldResponse(release: CountDownLatch) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                check(release.await(10,TimeUnit.SECONDS))
                return response()
            }
        }
    }
    @Test fun accountChangeCancelsAnActualPendingCallAndSuppressesItsLateSuccess() = runBlocking {
        val release = CountDownLatch(1)
        heldResponse(release)
        try {
            val pending = async(Dispatchers.IO) { gateway.delete(TokenStore.snapshot(),FlightRecordMode.UP,"record-a") }
            assertEquals("Bearer record-owner-a",request().headers["Authorization"])
            TokenStore.set("record-owner-b")
            assertEquals(RecordMutationOutcome.Obsolete,withTimeout(3000) { pending.await() })
            assertEquals("record-owner-b",TokenStore.peek())
        } finally { release.countDown() }
    }

    @Test fun aNewGenerationOfTheSameCredentialInvalidatesThePendingCall() = runBlocking {
        val release = CountDownLatch(1)
        heldResponse(release)
        try {
            val identity = TokenStore.snapshot()
            val pending = async(Dispatchers.IO) { gateway.changeScene(identity,FlightRecordMode.DOWN,"record-a",FlightRecordScenePatch(1,"学习")) }
            request()
            TokenStore.set("record-owner-a")
            assertEquals(RecordMutationOutcome.Obsolete,withTimeout(3000) { pending.await() })
        } finally { release.countDown() }
    }

    @Test fun cancellationWhileWaitingForTheIdentityLeaseNeverSendsALateRequest() = runBlocking {
        val identity = TokenStore.snapshot()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val held = launch(Dispatchers.IO) { TokenStore.withSnapshotLease(identity) { entered.complete(Unit);release.await() } }
        entered.await()
        try {
            val pending = launch(start=CoroutineStart.UNDISPATCHED) { gateway.delete(identity,FlightRecordMode.UP,"record-a") }
            withTimeout(3000) { pending.cancelAndJoin() }
            assertEquals(0,server.requestCount)
        } finally { release.complete(Unit);held.join() }
    }

    @Test fun redirectNeverForwardsTheCapturedCredentialToAnotherHost() = runBlocking {
        val foreign = MockWebServer().also { it.start() }
        try {
            server.enqueue(MockResponse.Builder().code(302).body("{\"success\":true}").addHeader("Location",foreign.url("/receive")).build())
            assertTrue(gateway.delete(TokenStore.snapshot(),FlightRecordMode.DOWN,"record-a") is RecordMutationOutcome.Rejected)
            assertEquals(0,foreign.requestCount)
        } finally { foreign.close() }
    }

    @Test fun networkFailureAndOversizedResponsesNeverAcknowledgeAMutation() = runBlocking {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        assertEquals(RecordMutationOutcome.NetworkFailure,gateway.delete(TokenStore.snapshot(),FlightRecordMode.DOWN,"record-a"))
        server.enqueue(response(body="""{"success":true,"message":"${"x".repeat(66_000)}"}"""))
        assertEquals(RecordMutationOutcome.InvalidResponse,gateway.delete(TokenStore.snapshot(),FlightRecordMode.DOWN,"record-a"))
    }
}
