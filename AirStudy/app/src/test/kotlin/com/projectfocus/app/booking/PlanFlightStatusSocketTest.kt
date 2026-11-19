package com.projectfocus.app.booking

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.FlightPlanAirportDto
import com.projectfocus.core.network.dto.FlightPlanLegSnapshot
import com.projectfocus.core.network.plan.SocketFlightPlanGateway
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual production WS gateway and three SP stores, with a local owned WS peer only. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PlanFlightStatusSocketTest {
    @After fun cleanup() { TokenStore.clear() }

    @Test fun `actual status frames keep mode2 pending restore waiting and reserve arrival fields for completion`() = runBlocking {
        TokenStore.set("r168-own-loopback-status-token")
        val server = MockWebServer().apply { start() }
        val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).callTimeout(0, TimeUnit.MILLISECONDS).build()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        try {
            val frames = LinkedBlockingQueue<JSONObject>()
            repeat(4) {
                server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val frame = JSONObject(text); frames.add(frame)
                        val response = JSONObject().put("request_id", frame.getString("request_id")).put("success", true)
                            .put("data", JSONObject().put("action", frame.getString("action")))
                        webSocket.send(response.toString()) // Valid nullable status ACK, not an invented replacement plan.
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                }).build())
            }
            val context = ApplicationProvider.getApplicationContext<Application>()
            fun prefs(kind: String) = context.getSharedPreferences("r168_socket_${kind}_" + UUID.randomUUID(), Context.MODE_PRIVATE)
            val pending = prefs("pending"); val receipts = prefs("receipts"); val globalPrefs = prefs("global")
            val clock = PreparationClock { ZonedDateTime.parse("2026-10-31T12:34:56.789+08:00[Asia/Shanghai]") }
            val candidate = DailyFlightLeg(FlightPlanLegSnapshot(7,
                FlightPlanAirportDto(1, "PEK", "Capital", "Beijing", "40.08", "116.58", "CN", countryRegionId = 156),
                FlightPlanAirportDto(2, "PVG", "Pudong", "Shanghai", "31.14", "121.8", "CN", countryRegionId = 156),
                "2026-10-31T04:00:00Z", "waiting_takeoff", 100.0, "PF7", "01A", 1, "Study",
                expectedArrivalAt = "2026-10-31T05:00:00Z"), null)
            val day = DailyFlightPlan(DATE, ZONE, legs = listOf(candidate), ownerScope = FlightPlanOwnership.scope())
            val global = SharedPreferencesGlobalFlightPlanStore(globalPrefs, clock)
            global.bindHost("r168-real-status-fixture")
            fun selected(): ActivePlanSelection {
                val pendingSeat = global.beginPending(day, candidate)
                check(global.acceptSeat(pendingSeat.requestId, "02C", day, candidate))
                return checkNotNull(global.current().activeSelection)
            }
            val gateway = SocketFlightPlanGateway(client, server.url("/").toString(), timeoutMillis = 3_000)
            val dispatch = SharedPreferencesPlanCompletionDispatch(pending, receipts, clock, gateway, scope,
                currentBacking = { day.legs }, clearActiveIfMatches = global::clearActiveIfMatches)
            val identity = TokenStore.snapshot()
            val selection = selected()
            dispatch.markInProgress(selection, START, identity)
            await { dispatch.snapshot()?.inProgressSent == true }
            val first = checkNotNull(frames.poll(3, TimeUnit.SECONDS))
            assertThat(first.getString("target_status")).isEqualTo("in_progress")
            assertThat(first.getInt("leg_index")).isEqualTo(7)
            assertThat(global.current().activeSelection).isEqualTo(selection)
            assertThat(receipts.all).isEmpty()
            assertNoArrival(first)

            assertThat(dispatch.notifyAborted(selection, identity)).isEqualTo(PlanAbortStatusAction.QUEUED_WAITING)
            await { dispatch.snapshot() == null }
            val second = checkNotNull(frames.poll(3, TimeUnit.SECONDS))
            assertThat(second.getString("target_status")).isEqualTo("waiting_takeoff")
            assertNoArrival(second)
            assertThat(global.current().activeSelection).isNull()
            assertThat(global.current().enabledDate).isEqualTo(DATE) // Aborting one flight is not global Off.
            assertThat(receipts.all).isEmpty()

            val next = selected()
            dispatch.markInProgress(next, START + 1, identity)
            await { dispatch.snapshot()?.inProgressSent == true }
            assertNoArrival(checkNotNull(frames.poll(3, TimeUnit.SECONDS)))
            dispatch.complete(next)
            await { dispatch.snapshot() == null }
            val completed = checkNotNull(frames.poll(3, TimeUnit.SECONDS))
            assertThat(completed.getString("target_status")).isEqualTo("completed")
            assertThat(completed.getString("request_id")).endsWith("_completed_r${START + 1}")
            assertThat(completed.getString("actual_arrival_at")).isEqualTo(PlanCompletionParameters.actualArrival(clock.now().toInstant().toEpochMilli()))
            assertThat(completed.has("arrival_diff_minutes")).isTrue()
            assertThat(dispatch.receiptMillis(candidate)).isEqualTo(clock.now().toInstant().toEpochMilli())
            assertThat(global.current().enabledDate).isEqualTo(DATE)
            assertThat(server.requestCount).isEqualTo(4)
        } finally {
            job.cancelAndJoin()
            client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
            server.close()
        }
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(3_000) {
        while (!condition()) delay(5)
    }
    private fun assertNoArrival(frame: JSONObject) {
        assertThat(frame.has("actual_arrival_at")).isFalse()
        assertThat(frame.has("arrival_diff_minutes")).isFalse()
        assertThat(frame.has("session_ts")).isFalse()
    }
    private companion object { const val DATE = "2026-10-31"; const val ZONE = "Asia/Shanghai"; const val START = 1793420000000L }
}
