package com.projectfocus.core.network.record

import android.app.Application
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.freeflight.*
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mockwebserver3.*
import okhttp3.OkHttpClient
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
class HttpRecordArchiveGatewayTest {
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var gateway: HttpRecordArchiveGateway
    private val codec = Json { encodeDefaults = true }
    private val airport = PlannedAirport(1, "PEK", "Airport", "City", "CN", 40.0, 116.0)
    @Before fun open() {
        TokenStore.set("archive-owner")
        server = MockWebServer().also { it.start() }
        http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
        gateway = HttpRecordArchiveGateway(http, server.url("/").toString())
    }
    @After fun close() {
        server.close(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll(); TokenStore.clear()
    }
    private fun response(body: String, code: Int = 200) = MockResponse.Builder().code(code).body(body).build()
    private fun record(id: String = "shared") = FlightRecordArchiveSnapshot(FlightRecordMode.DOWN, id,
        CountdownRecordArchive(id = id, focusType = "SOLO", departure = airport, destination = airport,
            startTimeMillis = 1000, endTimeMillis = 2_701_000, durationMinutes = 45, actualFocusMinutes = 45,
            distanceKm = 600.0, status = "COMPLETED", createdAt = 1000, updatedAt = 2_701_000))
    private fun up(): FlightRecordArchiveSnapshot {
        val engine = FreeFlightEngine(); val now = FreeFlightClockReading(0, 0, 1000, "private-device-epoch")
        val start = engine.start(FreeFlightStartRequest("local-session", "private-owner", airport, 90.0,
            "01A", FreeFlightScene(1, "Study", "study"), shieldingCategoryId = 42), now)
        val at = now.copy(elapsedRealtimeMs = 90_001, uptimeMillis = 90_001, wallMs = 91_001)
        val selection = engine.prepareLanding(start, at)
        val chosen = engine.selectLanding(selection, airport, selection.landingSelectionGeneration)
        val completed = engine.completeLandingWithoutMap(engine.beginLandingWithoutMap(chosen, chosen.landingSelectionGeneration, at), at)
            .copy(remoteRecordId = "shared", sampleSequence = 1)
        return FlightRecordArchiveSnapshot(FlightRecordMode.UP, "shared", countUp = completed, positions = listOf(
            FreeFlightPositionSample(completed.sessionId, 1, completed.clock.activeElapsedMs, completed.position,
                completed.bearingDegrees, completed.cumulativeDistanceKm, completed.updatedAtWallMs)))
    }
    private fun pageBody(page: CloudFlightRecordPage): String = codec.encodeToString(FlightRecordResponse(true, data = page))

    @Test fun uploadCarriesCapturedCredentialsTheStableModeKeyAndItsRetryHeaders() = runBlocking {
        server.enqueue(response("""{"success":true,"data":{"revision":7}}"""))
        val record = record("a/b 学习")
        val outcome = gateway.upload(TokenStore.snapshot(), FlightRecordArchiveUpload(record, FlightRecordScenePatch(2, "Work")),
            FlightRecordMutationOptions("upload-once", 0))
        assertEquals(RecordMutationOutcome.Acknowledged(revision = 7), outcome)
        val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("PUT", sent.method); assertEquals("/v2/flight-records/down/a%2Fb+%E5%AD%A6%E4%B9%A0", sent.url.encodedPath)
        assertEquals("Bearer archive-owner", sent.headers["Authorization"])
        assertEquals("upload-once", sent.headers["Idempotency-Key"]); assertEquals("\"0\"", sent.headers["If-Match"])
        assertEquals("AirStudy/1.0", sent.headers["User-Agent"])
        val body = codec.decodeFromString(FlightRecordArchiveUpload.serializer(), requireNotNull(sent.body).utf8())
        assertEquals(record, body.archive); assertEquals(FlightRecordScenePatch(2, "Work"), body.scene)
    }

    @Test fun countUpUploadRetainsActualSamplesButRemovesDeviceOwnershipAndCategoryForeignKeys() = runBlocking {
        server.enqueue(response("""{"success":true,"data":{"revision":8}}"""))
        val record = up()
        assertEquals(RecordMutationOutcome.Acknowledged(revision = 8), gateway.upload(TokenStore.snapshot(), FlightRecordArchiveUpload(record)))
        val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/v2/flight-records/up/shared", sent.url.encodedPath)
        val body = codec.decodeFromString(FlightRecordArchiveUpload.serializer(), requireNotNull(sent.body).utf8())
        assertEquals(record.withoutDeviceOwnership(), body.archive)
        assertEquals("local-session", body.archive.countUp!!.sessionId)
        assertNull(body.archive.countUp!!.shieldingCategoryId)
        assertEquals(record.positions, body.archive.positions)
    }

    @Test fun incrementalReadsKeepBothModesDeletionMarkersAndTheExactCursor() = runBlocking {
        val down = record(); val up = up().withoutDeviceOwnership()
        val page = CloudFlightRecordPage(listOf(CloudFlightRecord(down.mode, down.id, 11, false, down),
            CloudFlightRecord(up.mode, up.id, 12, false, up), CloudFlightRecord(FlightRecordMode.DOWN, "removed", 13, true)),
            hasMore = true, nextCursor = 13)
        server.enqueue(response(pageBody(page)))
        assertEquals(RecordArchiveReadResult.Loaded(page), gateway.changes(TokenStore.snapshot(), 10, 3))
        val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("GET", sent.method); assertEquals("/v2/flight-records", sent.url.encodedPath)
        assertEquals("10", sent.url.queryParameter("after")); assertEquals("3", sent.url.queryParameter("limit"))
        server.enqueue(response(pageBody(CloudFlightRecordPage(emptyList(), hasMore = false, nextCursor = 13))))
        assertEquals(RecordArchiveReadResult.Loaded(CloudFlightRecordPage(emptyList(), hasMore = false, nextCursor = 13)),
            gateway.changes(TokenStore.snapshot(), 13))
    }

    @Test fun omittedNullableFieldsFromActualServerSerializationRemainReadable() = runBlocking {
        val record = record(); val page = CloudFlightRecordPage(listOf(CloudFlightRecord(record.mode, record.id, 1, false, record)),
            hasMore = false, nextCursor = 1)
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        val wire = Json { encodeDefaults = true; explicitNulls = false }.encodeToString(FlightRecordResponse(true, data = page))
        assertFalse(wire.contains("roomId")); assertFalse(wire.contains("seat"))
        server.enqueue(response(wire))
        assertEquals(RecordArchiveReadResult.Loaded(page), gateway.changes(TokenStore.snapshot(), 0))
    }

    @Test fun invalidCursorPagesAreRejectedBeforeTheyCanAdvanceLocalProgress() = runBlocking {
        val valid = CloudFlightRecord(FlightRecordMode.DOWN, "shared", 2, false, record())
        val pages = listOf(
            CloudFlightRecordPage(emptyList(), hasMore = true, nextCursor = 1),
            CloudFlightRecordPage(listOf(valid), hasMore = false, nextCursor = 3),
            CloudFlightRecordPage(listOf(valid.copy(revision = 1)), hasMore = false, nextCursor = 1),
            CloudFlightRecordPage(listOf(valid, valid.copy(revision = 3)), hasMore = false, nextCursor = 3),
            CloudFlightRecordPage(listOf(valid.copy(id = "other")), hasMore = false, nextCursor = 2),
            CloudFlightRecordPage(listOf(valid.copy(deleted = true)), hasMore = false, nextCursor = 2))
        for (page in pages) {
            server.enqueue(response(pageBody(page)))
            assertEquals(RecordArchiveReadResult.Failure(RecordMutationOutcome.InvalidResponse), gateway.changes(TokenStore.snapshot(), 1))
        }
    }

    @Test fun responseAcknowledgementAndHttpStatusMustBothAcceptAnUpload() = runBlocking {
        for (body in listOf("{}", "not json", """{"success":true}""", """{"success":true,"data":{"revision":0}}""")) {
            server.enqueue(response(body))
            assertFalse(gateway.upload(TokenStore.snapshot(), FlightRecordArchiveUpload(record())) is RecordMutationOutcome.Acknowledged)
        }
        for (status in listOf(401, 409, 410, 429, 503)) {
            server.enqueue(response("""{"success":true,"data":{"revision":8}}""", status))
            assertEquals(RecordMutationOutcome.Rejected(status, retryable = status == 429 || status >= 500),
                gateway.upload(TokenStore.snapshot(), FlightRecordArchiveUpload(record())))
        }
    }

    @Test fun missingAuthStaleAccountsAndInvalidArchivesDoNotSendAnyRequests() = runBlocking {
        val old = TokenStore.snapshot(); TokenStore.clear()
        assertEquals(RecordMutationOutcome.Rejected(401), gateway.upload(TokenStore.snapshot(), FlightRecordArchiveUpload(record())))
        assertEquals(RecordArchiveReadResult.Failure(RecordMutationOutcome.Obsolete), gateway.changes(old, 0))
        TokenStore.set("next-owner")
        assertEquals(RecordArchiveReadResult.Failure(RecordMutationOutcome.Rejected(400)), gateway.changes(TokenStore.snapshot(), -1))
        val archive = record().copy(countdown = record().countdown!!.copy(status = "ONGOING"))
        assertEquals(RecordMutationOutcome.Rejected(400), gateway.upload(TokenStore.snapshot(), FlightRecordArchiveUpload(archive)))
        assertEquals(0, server.requestCount)
    }

    @Test fun changingAccountsCancelsAnActualPendingDownloadAndSuppressesItsLateResult() = runBlocking {
        val held = CompletableDeferred<Unit>(); val release = java.util.concurrent.CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                held.complete(Unit); release.await(5, TimeUnit.SECONDS)
                return response(pageBody(CloudFlightRecordPage(emptyList(), hasMore = false, nextCursor = 0)))
            }
        }
        val pending = async(Dispatchers.IO) { gateway.changes(TokenStore.snapshot(), 0) }
        try {
            withTimeout(5000) { held.await() }; TokenStore.setCancellable("another-account")
            assertEquals(RecordArchiveReadResult.Failure(RecordMutationOutcome.Obsolete), withTimeout(2000) { pending.await() })
        } finally { release.countDown(); pending.cancelAndJoin() }
    }
}
