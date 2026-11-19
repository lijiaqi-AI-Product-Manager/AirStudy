package com.projectfocus.core.network.plan

import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.FlightPlanActions
import com.projectfocus.core.network.dto.CancelBatchDeleteFlightPlanRequest
import com.projectfocus.core.network.dto.FlightPlanAirportDto
import com.projectfocus.core.network.dto.FlightPlanHistoryRequest
import com.projectfocus.core.network.dto.FlightPlanLegDto
import com.projectfocus.core.network.dto.GetFlightPlanRequest
import com.projectfocus.core.network.dto.UpsertFlightPlanRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SocketFlightPlanGatewayTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val account = AtomicReference(TokenStore.Snapshot("test-plan-token", 1))

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS).followRedirects(false).build()
    }
    @After fun teardown() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
        server.close()
    }

    @Test fun realWebSocketUsesFlatGetAndCorrelatesReplies() = runBlocking {
        val frames = reply { socket, request ->
            socket.send("""{"request_id":"other","data":{"legs":{}}}""")
            socket.send("""{"request_id":"get_test","action":"unrelated","data":{"legs":{}}}""")
            socket.send("""{"request_id":" get_test ","action":"root-ignored","data":{
                "action":"${request.getString("action")}","legs":{"plan_date":"2026-10-30",
                "local_plan_date":"2026-10-31","timezone":"Asia/Shanghai","legs":[]}}}""")
        }
        val result = gateway().get(get())!!
        assertThat(result.planDate).isEqualTo("2026-10-30")
        assertThat(result.localPlanDate).isEqualTo("2026-10-31")
        val frame = frames.poll(3, TimeUnit.SECONDS)!!
        assertThat(frame.getString("action")).isEqualTo(FlightPlanActions.GET)
        assertThat(frame.getString("plan_date")).isEqualTo("2026-10-31")
        assertThat(frame.getString("local_plan_date")).isEqualTo("2026-10-31")
        assertThat(frame.getInt("timezone_offset_minutes")).isEqualTo(480)
        assertThat(frame.has("data")).isFalse()
        val handshake = server.takeRequest()
        assertThat(handshake.url.encodedPath).isEqualTo("/flight-plan")
        assertThat(handshake.headers["Authorization"]).isEqualTo("Bearer test-plan-token")
        assertThat(handshake.headers["Upgrade"]).isEqualTo("websocket")
    }

    @Test fun wholeUpsertKeepsCancelledAndTerminalFieldsButOmitsUnsetOptionals() = runBlocking {
        val frames = reply { socket, request -> socket.send(success(request, "null")) }
        val first = leg().copy(legIndex = 78, legStatus = "canceled", focusIconCode = 99,
            arrivalDiffMinutes = -4, actualArrivalAt = "2026-10-31T04:30:00Z")
        val second = leg().copy(legIndex = -1, focusIconCode = 0, arrivalDiffMinutes = Int.MIN_VALUE,
            scheduledDepartureLocal = "", expectedArrivalAt = "")
        assertThat(gateway().upsert(UpsertFlightPlanRequest("2026-10-30", "2026-10-31", "Asia/Shanghai", 480,
            "upsert_test", listOf(first, second)))).isNull()
        val frame = frames.poll(3, TimeUnit.SECONDS)!!
        assertThat(frame.getString("plan_date")).isEqualTo("2026-10-30")
        assertThat(frame.getString("local_plan_date")).isEqualTo("2026-10-31")
        val legs = frame.getJSONArray("legs")
        assertThat(legs.length()).isEqualTo(2)
        assertThat(legs.getJSONObject(0).getString("leg_status")).isEqualTo("canceled")
        assertThat(legs.getJSONObject(0).getInt("leg_index")).isEqualTo(1)
        assertThat(legs.getJSONObject(0).getInt("focus_icon_code")).isEqualTo(20)
        assertThat(legs.getJSONObject(0).getInt("arrival_diff_minutes")).isEqualTo(-4)
        assertThat(legs.getJSONObject(0).getString("actual_arrival_at")).isEqualTo("2026-10-31T04:30:00Z")
        assertThat(legs.getJSONObject(1).getInt("leg_index")).isEqualTo(2)
        assertThat(legs.getJSONObject(1).getInt("focus_icon_code")).isEqualTo(1)
        for (field in listOf("arrival_diff_minutes", "scheduled_departure_local", "expected_arrival_at")) {
            assertThat(legs.getJSONObject(1).has(field)).isFalse()
        }
        assertThat(legs.getJSONObject(0).getJSONObject("departure_airport").get("latitude")).isInstanceOf(String::class.java)
    }

    @Test fun historyUsesMonthlyDatesAndSourceReadOnlyDefaults() = runBlocking {
        val frames = reply { socket, request -> socket.send("""{"request_id":"history_test","success":true,
            "data":{"action":"${request.getString("action")}","timezone":"UTC","plans":[
            {"local_plan_date":"2026-09-15","legs":[{"leg_status":"completed"}]}]}}""") }
        val plans = gateway().history(FlightPlanHistoryRequest("2026-09-01", "2026-09-30", "UTC", "history_test"))
        assertThat(plans.single().legs.single().readOnly).isTrue()
        val frame = frames.poll(3, TimeUnit.SECONDS)!!
        assertThat(frame.getString("startDate")).isEqualTo("2026-09-01")
        assertThat(frame.getString("endDate")).isEqualTo("2026-09-30")
        assertThat(frame.getInt("limit")).isEqualTo(200)
    }

    @Test fun batchDeletionUsesAllObservedAliasesAndOnlyAcceptsItsCorrelatedAction() = runBlocking {
        val frames = reply { socket, request ->
            socket.send("""{"request_id":"batch_delete_test","data":{"action":"${FlightPlanActions.UPSERT}","legs":null}}""")
            socket.send("""{"request_id":"batch_delete_test","data":{"action":"${request.getString("action")}",
                "deleted_count":2,"targetLocalPlanDate":"2026-10-31","plan_date":"2026-10-30",
                "timezone":"Asia/Shanghai","remaining_legs":[{"leg_index":1,"leg_status":"completed"}]}}""")
        }
        val result = gateway().deleteCanceled(CancelBatchDeleteFlightPlanRequest(" 2026-10-30 ", " 2026-10-31 ",
            "Asia/Shanghai", 480, "2026-10-31", "2026-11-01", "batch_delete_test", " apple:test-user "))!!
        assertThat(result.deletedCount).isEqualTo(2)
        assertThat(result.localPlanDate).isEqualTo("2026-10-31")
        assertThat(result.legs.single().legStatus).isEqualTo("completed")
        val frame = frames.poll(3, TimeUnit.SECONDS)!!
        assertThat(frame.getString("action")).isEqualTo(FlightPlanActions.CANCEL_BATCH_DELETE)
        assertThat(frame.getString("plan_date")).isEqualTo("2026-10-30")
        for (key in listOf("local_plan_date", "localPlanDate", "target_local_plan_date", "targetLocalPlanDate")) {
            assertThat(frame.getString(key)).isEqualTo("2026-10-31")
        }
        for (key in listOf("local_today", "localToday")) assertThat(frame.getString(key)).isEqualTo("2026-10-31")
        for (key in listOf("local_tomorrow", "localTomorrow")) assertThat(frame.getString(key)).isEqualTo("2026-11-01")
        for (key in listOf("user_scope_id", "userScopeId")) assertThat(frame.getString(key)).isEqualTo("apple:test-user")
        assertThat(frame.has("legs")).isFalse()
        assertThat(frame.has("data")).isFalse()
    }

    @Test fun batchDeletionOmitsUnavailableScopeAndDoesNotRetryALostAcknowledgement() = runBlocking {
        val frames = reply { _, _ -> }
        val error = failure { gateway(300).deleteCanceled(CancelBatchDeleteFlightPlanRequest("2026-10-30", "2026-10-31",
            "Asia/Shanghai", 480, "2026-10-31", "2026-11-01", "batch_delete_lost", "  ")) }
        assertThat(error).isInstanceOf(TimeoutCancellationException::class.java)
        val frame = frames.single()
        assertThat(frame.has("user_scope_id")).isFalse()
        assertThat(frame.has("userScopeId")).isFalse()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun explicitFailureAndMalformedRepliesNeverBecomeASavedPlan() = runBlocking {
        reply { socket, request -> socket.send("""{"request_id":"${request.getString("request_id")}",
            "success":false,"message":"denied","data":{}}""") }
        assertThat(failure { gateway().get(get()) }).isInstanceOf(FlightPlanRejectedException::class.java)
        reply { socket, _ -> socket.send("not json") }
        assertThat(failure { gateway().get(get()) }).isInstanceOf(IOException::class.java)
    }

    @Test fun lostAcknowledgementTimesOutWithoutRetryingMutation() = runBlocking {
        val frames = reply { _, _ -> }
        val error = failure { gateway(300).upsert(UpsertFlightPlanRequest("2026-10-30", "2026-10-31", "Asia/Shanghai",
            480, "upsert_test", listOf(leg()))) }
        assertThat(error).isInstanceOf(TimeoutCancellationException::class.java)
        assertThat(frames.size).isEqualTo(1)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun callerCancellationCancelsTheSocketAndDoesNotAcceptLaterData() = runBlocking {
        val received = LinkedBlockingQueue<WebSocket>()
        reply { socket, _ -> received.add(socket) }
        val job = async(Dispatchers.Default, start = CoroutineStart.DEFAULT) { gateway().get(get()) }
        val socket = withContext(Dispatchers.IO) { received.poll(3, TimeUnit.SECONDS) }!!
        job.cancelAndJoin()
        assertThat(job.isCancelled).isTrue()
        socket.send("""{"request_id":"get_test","data":{"legs":{}}}""")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun accountGenerationChangeRejectsEvenAnIdenticalTokenReply() = runBlocking {
        reply { socket, request ->
            account.set(TokenStore.Snapshot("test-plan-token", 2))
            socket.send(success(request, "{}"))
        }
        assertThat(failure { gateway().get(get()) }).isInstanceOf(FlightPlanIdentityChangedException::class.java)
    }

    @Test fun missingAuthenticationNeverContactsAnEndpoint() = runBlocking {
        account.set(TokenStore.Snapshot(null, 2))
        assertThat(failure { gateway().get(get()) }).isInstanceOf(FlightPlanAuthenticationException::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }

    private fun gateway(timeout: Long = 3_000) = SocketFlightPlanGateway(client, server.url("/").toString(), account::get, timeout)
    private fun get() = GetFlightPlanRequest("2026-10-31", "2026-10-31", "Asia/Shanghai", 480, "get_test")
    private fun leg() = FlightPlanLegDto(1, airport("PEK"), airport("PVG"), "2026-10-31T04:00:00Z",
        "waiting_takeoff", 1099.5, "", "01A", 1, "学习")
    private fun airport(code: String) = FlightPlanAirportDto(0, code, code, code, "30", "120", "", countryRegionId = 0)
    private fun success(request: JSONObject, plan: String) = """{"request_id":"${request.getString("request_id")}",
        "success":true,"data":{"action":"${request.getString("action")}","legs":$plan}}"""
    private fun reply(handler: (WebSocket, JSONObject) -> Unit): LinkedBlockingQueue<JSONObject> {
        val frames = LinkedBlockingQueue<JSONObject>()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = JSONObject(text)
                frames.add(frame)
                handler(webSocket, frame)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        }).build())
        return frames
    }
    private suspend fun failure(block: suspend () -> Any?): Throwable {
        try { block() } catch (error: Throwable) { return error }
        throw AssertionError("Expected request failure")
    }
}
