package com.projectfocus.core.network.record

import android.app.Application
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import mockwebserver3.*
import okhttp3.OkHttpClient
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class HttpRecordSummaryGatewayTest {
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var gateway: HttpRecordSummaryGateway
    @Before fun open() {
        TokenStore.set("summary-owner")
        server = MockWebServer().also { it.start() }
        http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
        gateway = HttpRecordSummaryGateway(http, server.url("/").toString())
    }
    @After fun close() {
        server.close(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll(); TokenStore.clear()
    }
    private fun response(body: String, code: Int = 200) = MockResponse.Builder().code(code).body(body).build()
    private fun body(records: String, pagination: String = "{\"totalPages\":1,\"totalCount\":1}") =
        """{"success":true,"data":{"records":[$records],"pagination":$pagination}}"""
    private fun read() = runBlocking { gateway.page(TokenStore.snapshot(), FlightRecordSummaryQuery()) }

    @Test fun readsTheFourListParametersAndAcceptsAvailableMetadataWithoutAnArchive() = runBlocking {
        server.enqueue(response(body("""{"flightRecordId":"old-flight","timer_type":"count_up","flightStatus":"已抵达","flightNumber":"AS321","seatNumber":"03F","departureAirport":{"code":"PEK","city":"北京"},"arrivalAirportCode":"LHR","flightStartTime":"2026-11-09T08:15:30.125+08:00","durationSeconds":90.125,"flightDistanceKm":12.75} """,
            """{"totalPages":3,"totalCount":5}""")))
        val result = gateway.page(TokenStore.snapshot(), FlightRecordSummaryQuery(2, 2, "up", "arrived")) as RecordSummaryReadResult.Loaded
        val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/v2/flight-records", request.url.encodedPath)
        assertEquals(setOf("page", "limit", "timerType", "status"), request.url.queryParameterNames)
        assertEquals(listOf("2", "2", "up", "arrived"), listOf("page", "limit", "timerType", "status").map(request.url::queryParameter))
        assertEquals("Bearer summary-owner", request.headers["Authorization"])
        assertEquals("AirStudy/1.0", request.headers["User-Agent"])
        val record = result.page.records.single()
        assertEquals(FlightRecordMode.UP, record.mode); assertEquals(SummaryFlightStatus.ARRIVED, record.status)
        assertEquals("AS321", record.flightNumber); assertEquals("03F", record.seat)
        assertEquals("PEK", record.departure?.code); assertEquals("LHR", record.arrival?.code)
        assertEquals(90.125, record.durationSeconds!!, 0.0); assertEquals(12.75, record.distanceKm!!, 0.0)
        assertEquals(java.time.Instant.parse("2026-11-09T00:15:30.125Z").toEpochMilli(), record.startedAtWallMs)
        assertEquals(3, result.page.totalPages); assertEquals(5, result.page.totalCount)
    }

    @Test fun missingAndUnknownFieldsRemainUnknownAndOpaqueMetadataSurvivesParsing() {
        server.enqueue(response(body("""{"id":"partial","status":"new_server_state","extra":{"future":7},"departureAirport":{"latitude":999,"longitude":"NaN"},"durationSeconds":-1,"distanceKm":-4,"flightStartTime":"bad-date"}""")))
        val record = (read() as RecordSummaryReadResult.Loaded).page.records.single()
        assertEquals(SummaryFlightStatus.UNKNOWN, record.status)
        assertNull(record.startedAtWallMs); assertNull(record.endedAtWallMs)
        assertNull(record.departure?.latitude); assertNull(record.departure?.longitude)
        assertNull(record.arrival); assertNull(record.durationSeconds); assertNull(record.distanceKm)
        assertEquals(7, record.raw.getValue("extra").jsonObject.getValue("future").jsonPrimitive.int)
    }

    @Test fun equalIdentifiersAcrossModesAreDistinctButRepeatsWithinOneModeAreInvalid() {
        val down = """{"id":"same","recordType":"down"}"""
        val up = """{"id":"same","freeFlightMode":true}"""
        server.enqueue(response(body("$down,$up", """{"totalPages":1,"totalCount":2}""")))
        assertEquals(listOf(FlightRecordMode.DOWN, FlightRecordMode.UP), (read() as RecordSummaryReadResult.Loaded).page.records.map { it.mode })
        server.enqueue(response(body("$down,$down", """{"totalPages":1,"totalCount":2}""")))
        assertEquals(RecordSummaryReadResult.Failure(RecordMutationOutcome.InvalidResponse), read())
    }

    @Test fun neitherHttpFailureNorBusinessFailureCanBecomeASuccessfulEmptyList() {
        for (code in listOf(401, 408, 429, 503)) {
            server.enqueue(response(body("""{"id":"one"}"""), code))
            val reason = (read() as RecordSummaryReadResult.Failure).reason as RecordMutationOutcome.Rejected
            assertEquals(code, reason.status); assertEquals(code in setOf(408, 429, 503), reason.retryable)
        }
        server.enqueue(response("""{"success":false,"data":{"records":[]}}"""))
        assertTrue((read() as RecordSummaryReadResult.Failure).reason is RecordMutationOutcome.Rejected)
        for (wire in listOf("not json", "{}", """{"success":true,"data":{"records":[{}]}}""",
            body("", """{"totalPages":2,"totalCount":2}"""))) {
            server.enqueue(response(wire))
            assertEquals(RecordSummaryReadResult.Failure(RecordMutationOutcome.InvalidResponse), read())
        }
    }

    @Test fun absentPaginationUsesTheRequestedPageWithoutInventingATotal() = runBlocking {
        server.enqueue(response("""{"success":true,"data":{"records":[{"id":"one"}]}}"""))
        val result = gateway.page(TokenStore.snapshot(), FlightRecordSummaryQuery(page = 4)) as RecordSummaryReadResult.Loaded
        assertEquals(4, result.page.page); assertEquals(4, result.page.totalPages); assertNull(result.page.totalCount)
    }

    @Test fun invalidQueriesAndStaleOrGuestIdentitiesNeverLeaveTheClient() = runBlocking {
        val identity = TokenStore.snapshot()
        for (query in listOf(FlightRecordSummaryQuery(page = 0), FlightRecordSummaryQuery(limit = 101),
            FlightRecordSummaryQuery(timerType = "mixed"), FlightRecordSummaryQuery(status = "anything")))
            assertEquals(RecordSummaryReadResult.Failure(RecordMutationOutcome.Rejected(400)), gateway.page(identity, query))
        TokenStore.set("other-owner")
        assertEquals(RecordSummaryReadResult.Failure(RecordMutationOutcome.Obsolete), gateway.page(identity, FlightRecordSummaryQuery()))
        TokenStore.clear()
        assertEquals(RecordSummaryReadResult.Failure(RecordMutationOutcome.Rejected(401)), gateway.page(TokenStore.snapshot(), FlightRecordSummaryQuery()))
        assertEquals(0, server.requestCount)
    }

    @Test fun changingAccountsCancelsAnActualPendingResponse() = runBlocking {
        server.enqueue(MockResponse.Builder().headersDelay(5, TimeUnit.SECONDS).body(body("""{"id":"one"}""")).build())
        val pending = async(Dispatchers.IO) { gateway.page(TokenStore.snapshot(), FlightRecordSummaryQuery()) }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
        TokenStore.set("replacement-owner")
        assertEquals(RecordSummaryReadResult.Failure(RecordMutationOutcome.Obsolete), withTimeout(3_000) { pending.await() })
    }

    @Test fun responsesOverTheBoundAreRejectedBeforeMetadataIsPublished() {
        server.enqueue(response(body("""{"id":"large","opaque":"${"x".repeat(8 * 1024 * 1024)}"}""")))
        assertEquals(RecordSummaryReadResult.Failure(RecordMutationOutcome.InvalidResponse), read())
    }
}
