package com.projectfocus.core.network.plan

import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.FlightPlanActions
import com.projectfocus.core.network.dto.UpdateFlightPlanLegStatusRequest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LegStatusFlightPlanGatewayTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

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

    @Test fun waitingCancelUsesTheExactFlatFrameAndOnlyItsCorrelatedReply() = runBlocking {
        val frames = reply { socket, request ->
            socket.send("""{"request_id":"another","data":{"action":"${FlightPlanActions.LEG_STATUS_UPDATE}","legs":{}}}""")
            socket.send("""{"request_id":"leg_test","data":{"action":"${FlightPlanActions.UPSERT}","legs":{}}}""")
            socket.send("""{"request_id":"leg_test","success":true,"data":{
                "action":"${request.getString("action")}","legs":{"local_plan_date":"2026-10-31",
                "plan_date":"2026-10-30","timezone":"Asia/Shanghai","legs":[
                {"leg_index":2,"leg_status":"waiting_takeoff","distance_km":100}]}}}""")
        }
        val result = gateway().updateLegStatus(request().copy(localPlanDate = " 2026-10-31 ",
            targetStatus = " waiting_takeoff ", flightNo = "", seatNo = "  "))!!
        assertThat(result.localPlanDate).isEqualTo("2026-10-31")
        assertThat(result.planDate).isEqualTo("2026-10-30")
        assertThat(result.legs.single().legIndex).isEqualTo(2)
        assertThat(result.legs.single().legStatus).isEqualTo("waiting_takeoff")
        val frame = frames.poll(3, TimeUnit.SECONDS)!!
        assertThat(frame.keys().asSequence().toSet()).containsExactly("action", "local_plan_date", "timezone",
            "timezone_offset_minutes", "leg_index", "target_status", "request_id")
        assertThat(frame.getString("action")).isEqualTo(FlightPlanActions.LEG_STATUS_UPDATE)
        assertThat(frame.getString("local_plan_date")).isEqualTo("2026-10-31")
        assertThat(frame.getString("target_status")).isEqualTo("waiting_takeoff")
        assertThat(frame.get("leg_index")).isInstanceOf(Int::class.javaObjectType)
        assertThat(frame.getInt("leg_index")).isEqualTo(2)
        val handshake = server.takeRequest()
        assertThat(handshake.url.encodedPath).isEqualTo("/flight-plan")
        assertThat(handshake.headers["Authorization"]).isEqualTo("Bearer project-status-test-token")
    }

    @Test fun completedOptionalsAndNullableStatusAckKeepTheObservedParserBoundary() = runBlocking {
        val frames = reply { socket, request -> socket.send("""{"request_id":"leg_test","success":true,
            "data":{"action":"${request.getString("action")}","remaining_legs":[]}}""") }
        val result = gateway().updateLegStatus(request().copy(targetStatus = "completed",
            actualArrivalAt = " 2026-10-31T00:06:00Z ", arrivalDiffMinutes = -2,
            flightNo = " PF8 ", seatNo = " 02C "))
        // Status's source dispatcher does not accept batch remaining_legs as a plan.
        assertThat(result).isNull()
        val frame = frames.poll(3, TimeUnit.SECONDS)!!
        assertThat(frame.getString("actual_arrival_at")).isEqualTo("2026-10-31T00:06:00Z")
        assertThat(frame.getInt("arrival_diff_minutes")).isEqualTo(-2)
        assertThat(frame.getString("flight_no")).isEqualTo("PF8")
        assertThat(frame.getString("seat_no")).isEqualTo("02C")
        for (key in listOf("plan_date", "data", "session_ts", "focus_icon_code", "focus_scene_text")) {
            assertThat(frame.has(key)).isFalse()
        }
        val omitted = JSONObject(FlightPlanRequestCodec.encode(request().copy(targetStatus = "completed",
            actualArrivalAt = "", arrivalDiffMinutes = Int.MIN_VALUE, flightNo = " ", seatNo = null)))
        for (key in listOf("actual_arrival_at", "arrival_diff_minutes", "flight_no", "seat_no")) {
            assertThat(omitted.has(key)).isFalse()
        }
        assertThat(FlightPlanResponseParser.status(JSONObject())).isNull()
        assertThat(FlightPlanResponseParser.status(JSONObject("""{"legs":{}}"""))!!.legs).isEmpty()
    }

    @Test fun rejectedStatusPreservesDataCodeForTerminalCleanupWithoutTreatingEveryFailureAsTerminal() = runBlocking {
        reply { socket, request -> socket.send("""{"request_id":"leg_test","success":false,"message":"航段已经结束",
            "data":{"action":"${request.getString("action")}","code":"LEG_TERMINAL_CONFLICT","error_code":4091}}""") }
        val terminal = failure { gateway().updateLegStatus(request()) } as FlightPlanRejectedException
        assertThat(terminal.code).isEqualTo("LEG_TERMINAL_CONFLICT")
        assertThat(terminal.message).isEqualTo("航段已经结束")
        assertThat(terminal.isTerminalConflict).isTrue()

        reply { socket, request -> socket.send("""{"request_id":"leg_test","success":false,"message":"稍后重试",
            "data":{"action":"${request.getString("action")}","error_code":5005}}""") }
        val temporary = failure { gateway().updateLegStatus(request()) } as FlightPlanRejectedException
        assertThat(temporary.code).isEmpty()
        assertThat(temporary.isTerminalConflict).isFalse()
        assertThat(FlightPlanRejectedException("legacy constructor").code).isEmpty()
    }

    @Test fun lostStatusAckAndCallerCancellationDoNotResendOrAcceptLaterReplies() = runBlocking {
        val first = reply { _, _ -> }
        assertThat(failure { gateway(300).updateLegStatus(request()) }).isInstanceOf(TimeoutCancellationException::class.java)
        assertThat(first.size).isEqualTo(1)
        assertThat(server.requestCount).isEqualTo(1)

        val opened = LinkedBlockingQueue<WebSocket>()
        val second = reply { socket, _ -> opened.add(socket) }
        val job = async(Dispatchers.Default) { gateway().updateLegStatus(request()) }
        val socket = withContext(Dispatchers.IO) { opened.poll(3, TimeUnit.SECONDS) }!!
        job.cancelAndJoin()
        socket.send("""{"request_id":"leg_test","success":true,"data":{"action":"${FlightPlanActions.LEG_STATUS_UPDATE}","legs":{}}}""")
        assertThat(job.isCancelled).isTrue()
        assertThat(second.size).isEqualTo(1)
        assertThat(server.requestCount).isEqualTo(2)
    }

    private fun request() = UpdateFlightPlanLegStatusRequest("2026-10-31", "Asia/Shanghai", 480,
        2, "waiting_takeoff", "leg_test")

    private fun gateway(timeout: Long = 3_000) = SocketFlightPlanGateway(client, server.url("/").toString(),
        { TokenStore.Snapshot("project-status-test-token", 1) }, timeout)

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
        throw AssertionError("Expected status request failure")
    }
}
