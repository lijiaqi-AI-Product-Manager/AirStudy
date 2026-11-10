package com.projectfocus.server

import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.freeflight.*
import com.projectfocus.core.network.dto.*
import com.projectfocus.server.db.CloudFlightRecords
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import java.net.URLEncoder
import java.util.UUID

class FlightRecordRoutesTest : ServerTestBase() {
    private val codec = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val airport = PlannedAirport(1, "PEK", "Airport", "City", "CN", 40.0, 116.0)
    private val landing = airport.copy(id = 2, code = "LHR", country = "GB", latitude = 51.4, longitude = -0.4)
    private fun down(id: String, status: String = "COMPLETED") = FlightRecordArchiveSnapshot(
        FlightRecordMode.DOWN, id, CountdownRecordArchive(id, null, "SOLO", null, airport, landing,
            1_000_000, 3_700_000, 45, 35, 600.25, status, "02A", 1_000_000, 3_700_000,
            logJson = "[{\"event\":\"landed\"}]", quote = "Arrived", quoteAuthor = "Traveller"))
    private fun up(id: String, session: String = id): FlightRecordArchiveSnapshot {
        val point = FreeFlightPoint(51.4, -0.4)
        val archive = FreeFlightArchive(sessionId = session, ownerScope = "device-private", revision = 5,
            departure = airport, seat = "05F", scene = FreeFlightScene(2, "Reading", "book"), flightNumber = "AS123",
            shieldingCategoryId = 99, startedAtWallMs = 1_000_000, updatedAtWallMs = 2_900_000, endedAtWallMs = 2_900_000,
            status = FreeFlightStatus.COMPLETED, phase = FreeFlightPhase.NO_MAP_ARRIVAL,
            clock = FreeFlightClock(1_900_000, 1_900_000, 1_900_000, "device-epoch", FreeFlightPause.TERMINAL),
            position = point, bearingDegrees = 270.0, cumulativeDistanceKm = 410.5,
            cruiseAnchor = FreeFlightCruiseAnchor(point, 270.0, 0, 0.0), landingAirport = landing,
            remoteRecordId = id, sampleSequence = 1)
        return FlightRecordArchiveSnapshot(FlightRecordMode.UP, id, countUp = archive, positions = listOf(
            FreeFlightPositionSample(session, 1, 1_900_000, point, 270.0, 410.5, 2_900_000)))
    }
    private fun path(mode: FlightRecordMode, id: String) = "/v2/flight-records/${mode.path}/${URLEncoder.encode(id, "UTF-8")}"
    private suspend fun login(c: TestClient, email: String): String = c.str(c.registerAndLogin(email), "token")!!
    private suspend fun send(c: TestClient, token: String?, method: HttpMethod, path: String, body: String? = null,
                             operation: String? = null, revision: Long? = null): HttpResponse = c.http.request(path) {
        this.method = method
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        operation?.let { header("Idempotency-Key", it) }; revision?.let { header("If-Match", "\"$it\"") }
        if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
    }
    private suspend fun upload(c: TestClient, token: String, record: FlightRecordArchiveSnapshot, operation: String? = null) =
        send(c, token, HttpMethod.Put, path(record.mode, record.id), codec.encodeToString(FlightRecordArchiveUpload(record)), operation)
    private suspend fun revision(response: HttpResponse): Long = Json.parseToJsonElement(response.bodyAsText())
        .jsonObject.getValue("data").jsonObject.getValue("revision").jsonPrimitive.long
    private suspend fun page(c: TestClient, token: String, query: String = ""): CloudFlightRecordPage {
        val response = send(c, token, HttpMethod.Get, "/v2/flight-records$query")
        assertThat(response.status.value).isEqualTo(200)
        return codec.decodeFromString<FlightRecordResponse<CloudFlightRecordPage>>(response.bodyAsText()).data!!
    }

    private suspend fun summaries(c: TestClient, token: String, query: String = "page=1&limit=100&timerType=all&status=all"): FlightRecordSummaryPage {
        val response = send(c, token, HttpMethod.Get, "/v2/flight-records?$query")
        assertThat(response.status.value).isEqualTo(200)
        return codec.decodeFromString<FlightRecordResponse<FlightRecordSummaryPage>>(response.bodyAsText()).data!!
    }

    @Test fun summaryPagesKeepModeIdentityRealDurationAndStableFiltering() = serverTest("summary-pagination") { c ->
        val token = login(c, "summary-pages@airstudy.test")
        upload(c, token, down("shared")); upload(c, token, up("shared")); upload(c, token, down("cancelled", "CANCELLED"))
        val first = summaries(c, token, "page=1&limit=2&timerType=all&status=all")
        val second = summaries(c, token, "page=2&limit=2&timerType=all&status=all")
        assertThat(first.pagination.totalCount).isEqualTo(3); assertThat(first.pagination.totalPages).isEqualTo(2)
        val all = (first.records + second.records).map(FlightRecordSummary::parse)
        assertThat(all.map { it.mode to it.id }.toSet()).hasSize(3)
        val down = all.single { it.mode == FlightRecordMode.DOWN && it.id == "shared" }
        assertThat(down.durationSeconds).isEqualTo(2100.0); assertThat(down.distanceKm).isEqualTo(600.25)
        assertThat(down.departure?.latitude).isEqualTo(40.0); assertThat(down.flightNumber).isNull()
        val up = summaries(c, token, "page=1&limit=100&timerType=up&status=arrived").records.single().let(FlightRecordSummary::parse)
        assertThat(up.flightNumber).isEqualTo("AS123"); assertThat(up.durationSeconds).isEqualTo(1900.0)
        assertThat(summaries(c, token, "page=1&limit=100&timerType=down&status=cancelled").records.single().getValue("flightRecordId").jsonPrimitive.content).isEqualTo("cancelled")
        assertThat(summaries(c, token, "page=3&limit=2&timerType=all&status=all").records).isEmpty()
    }

    @Test fun legacySummariesKeepKnownFactsWithoutCreatingAnArchiveOrCoordinates() = serverTest("summary-legacy") { c ->
        val token = login(c, "summary-legacy@airstudy.test"); val id = UUID.randomUUID().toString()
        val body = """{"sessions":[{"id":"$id","departureAirportCode":"PEK","destinationAirportCode":"LHR","startTimeMillis":1000000,"durationMinutes":45,"actualFocusMinutes":35,"distanceKm":600.0,"status":"COMPLETED","createdAt":1000000,"updatedAt":3700000}]}"""
        assertThat(c.withToken(token).post("/session/sync", body).status.value).isEqualTo(200)
        val old = summaries(c, token).records.single().let(FlightRecordSummary::parse)
        assertThat(old.id).isEqualTo(id); assertThat(old.departure?.code).isEqualTo("PEK")
        assertThat(old.departure?.latitude).isNull(); assertThat(old.departure?.name).isNull()
        assertThat(old.durationSeconds).isEqualTo(2100.0); assertThat(old.flightNumber).isNull()
        assertThat(page(c, token).items).isEmpty()
        upload(c, token, down(id))
        assertThat(summaries(c, token).pagination.totalCount).isEqualTo(1)
        send(c, token, HttpMethod.Delete, path(FlightRecordMode.DOWN, id))
        assertThat(summaries(c, token).records).isEmpty()
        c.post("/session/sync", body)
        assertThat(summaries(c, token).records).isEmpty()
    }

    @Test fun summaryBackfillKeepsCursorRevisionsAndSceneChangesOutOfTheArchive() = serverTest("summary-backfill") { c ->
        val token = login(c, "summary-backfill@airstudy.test"); val record = up("backfill")
        upload(c, token, record)
        val before = page(c, token).items.single()
        transaction { CloudFlightRecords.update({ CloudFlightRecords.recordId eq record.id }) { it[summaryJson] = null } }
        val first = summaries(c, token).records.single()
        assertThat(first.toString()).doesNotContain("positions"); assertThat(first.toString()).doesNotContain("ownerScope")
        assertThat(first.toString()).doesNotContain("device-epoch")
        assertThat(page(c, token).items.single().revision).isEqualTo(before.revision)
        val stored = transaction { CloudFlightRecords.selectAll().where { CloudFlightRecords.recordId eq record.id }.single()[CloudFlightRecords.summaryJson] }
        assertThat(stored).isNotNull()
        send(c, token, HttpMethod.Patch, path(record.mode, record.id) + "/focus-scene", """{"focusIconType":3,"focusScenario":"Study"}""")
        val changed = summaries(c, token).records.single().let(FlightRecordSummary::parse)
        assertThat(changed.sceneLabel).isEqualTo("Study"); assertThat(changed.sceneType).isEqualTo(3)
        assertThat(page(c, token).items.single().archive).isEqualTo(before.archive)
        assertThat(changed.revision).isEqualTo(before.revision + 1)
    }

    @Test fun summaryListsNeverExposeAnotherAccountsDataAndRejectMixedPagination() = serverTest("summary-accounts") { c ->
        val first = login(c, "summary-first@airstudy.test"); upload(c, first, down("private-record"))
        val second = login(c, "summary-second@airstudy.test")
        assertThat(summaries(c, second).pagination.totalCount).isEqualTo(0)
        assertThat(summaries(c, first).records).hasSize(1)
        assertThat(send(c, null, HttpMethod.Get, "/v2/flight-records?page=1").status.value).isEqualTo(401)
        for (query in listOf("page=0", "page=1&limit=101", "page=1&after=0", "page=1&timerType=bad", "page=1&status=bad", "page=abc"))
            assertThat(send(c, first, HttpMethod.Get, "/v2/flight-records?$query").status.value).isEqualTo(400)
        assertThat(summaries(c, first).records).hasSize(1)
    }

    @Test fun theSummaryColumnIsAddedToAnExistingDatabaseWithoutLosingArchivesOrRevisions() {
        lateinit var token: String
        val original = down("before-summary-column")
        serverTest("summary-column-upgrade") { c ->
            token = login(c, "summary-upgrade@airstudy.test")
            upload(c, token, original)
            transaction { exec("ALTER TABLE cloud_flight_records DROP COLUMN summary_json") }
        }
        serverTest("summary-column-upgrade") { c ->
            val record = summaries(c, token).records.single().let(FlightRecordSummary::parse)
            assertThat(record.id).isEqualTo(original.id); assertThat(record.revision).isEqualTo(1L)
            assertThat(record.durationSeconds).isEqualTo(2100.0)
            assertThat(page(c, token).items.single().archive).isEqualTo(original)
            assertThat(page(c, token).items.single().revision).isEqualTo(1L)
        }
    }

    @Test fun assignedCountdownNumberSurvivesFullTransferSummaryBackfillAndSceneEdits() = serverTest("countdown-number") { c ->
        val token = login(c, "countdown-number@airstudy.test")
        val base = down("assigned-number")
        val record = base.copy(countdown = base.countdown!!.copy(flightNumber = "AS2701"))
        assertThat(upload(c, token, record).status.value).isEqualTo(200)
        assertThat(page(c, token).items.single().archive).isEqualTo(record)
        assertThat(summaries(c, token).records.single().let(FlightRecordSummary::parse).flightNumber).isEqualTo("AS2701")
        transaction { CloudFlightRecords.update({ CloudFlightRecords.recordId eq record.id }) { it[summaryJson] = null } }
        assertThat(summaries(c, token).records.single().let(FlightRecordSummary::parse).flightNumber).isEqualTo("AS2701")
        assertThat(send(c, token, HttpMethod.Patch, path(record.mode, record.id) + "/focus-scene",
            """{"focusIconType":3,"focusScenario":"Study"}""").status.value).isEqualTo(200)
        assertThat(summaries(c, token).records.single().let(FlightRecordSummary::parse).flightNumber).isEqualTo("AS2701")
        assertThat(page(c, token).items.single().archive).isEqualTo(record)
        val unassigned = down("unassigned-number")
        assertThat(upload(c, token, unassigned).status.value).isEqualTo(200)
        assertThat(summaries(c, token).records.map(FlightRecordSummary::parse).single { it.id == unassigned.id }.flightNumber).isNull()
        val invalid = base.copy(id = "invalid-number", countdown = base.countdown!!.copy(id = "invalid-number", flightNumber = "X".repeat(129)))
        assertThat(upload(c, token, invalid).status.value).isEqualTo(400)
    }

    @Test fun bothModesKeepTheirCompleteSnapshotsAndPositionSamplesSeparate() = serverTest("cloud-roundtrip") { c ->
        val token = login(c, "roundtrip@airstudy.test"); val id = "same-record"
        assertThat(upload(c, token, down(id)).status.value).isEqualTo(200)
        assertThat(upload(c, token, up(id, "local-session")).status.value).isEqualTo(200)
        val records = page(c, token).items
        assertThat(records.map { it.mode }).containsExactly(FlightRecordMode.DOWN, FlightRecordMode.UP).inOrder()
        assertThat(records[0].archive).isEqualTo(down(id))
        assertThat(records[1].archive).isEqualTo(up(id, "local-session").withoutDeviceOwnership())
        assertThat(records[1].archive!!.countUp!!.ownerScope).isEqualTo("cloud")
        assertThat(records[1].archive!!.countUp!!.shieldingCategoryId).isNull()
        assertThat(c.withToken(token).str(c.get("/user/me"), "totalFocusedMinutes")).isEqualTo("67")
    }

    @Test fun theSameIdentifierBelongsToEachAccountIndependently() = serverTest("cloud-account-key") { c ->
        val first = login(c, "key-first@airstudy.test"); val second = login(c, "key-second@airstudy.test")
        upload(c, first, down("shared")); upload(c, second, down("shared", "CANCELLED"))
        val deleted = send(c, second, HttpMethod.Delete, path(FlightRecordMode.DOWN, "shared"))
        assertThat(deleted.status.value).isEqualTo(200)
        assertThat(page(c, first).items.single().deleted).isFalse()
        assertThat(page(c, second).items.single().deleted).isTrue()
        assertThat(page(c, first).items.single().archive!!.countdown!!.status).isEqualTo("COMPLETED")
    }

    @Test fun acceptedDeletionIsDurableIdempotentAndCannotBeResurrectedByEitherUploadPath() = serverTest("cloud-delete-marker") { c ->
        val token = login(c, "delete-marker@airstudy.test"); val id = UUID.randomUUID().toString()
        upload(c, token, down(id)); val upRevision = revision(upload(c, token, up(id)))
        val response = send(c, token, HttpMethod.Delete, path(FlightRecordMode.DOWN, id), operation = "delete-once")
        val deletedRevision = revision(response)
        assertThat(response.status.value).isEqualTo(200)
        assertThat(revision(send(c, token, HttpMethod.Delete, path(FlightRecordMode.DOWN, id), operation = "delete-once"))).isEqualTo(deletedRevision)
        assertThat(revision(send(c, token, HttpMethod.Delete, path(FlightRecordMode.DOWN, id), operation = "delete-again"))).isEqualTo(deletedRevision)
        assertThat(upload(c, token, down(id)).status.value).isEqualTo(410)
        val oldBody = """{"sessions":[{"id":"$id","departureAirportCode":"PEK","destinationAirportCode":"LHR","startTimeMillis":1000000,"durationMinutes":45,"actualFocusMinutes":35,"distanceKm":600.0,"status":"COMPLETED","createdAt":1000000,"updatedAt":3700000}]}"""
        val old = c.withToken(token).post("/session/sync", oldBody)
        assertThat(c.str(old, "accepted")).isEqualTo("0")
        assertThat(c.str(old, "rejected")).isEqualTo("1")
        val records = page(c, token).items
        assertThat(records.single { it.mode == FlightRecordMode.DOWN }.archive).isNull()
        assertThat(records.single { it.mode == FlightRecordMode.UP }.revision).isEqualTo(upRevision)
        assertThat(c.str(c.get("/user/me"), "totalFocusedMinutes")).isEqualTo("32")
    }

    @Test fun deletingAMissingOwnRecordCreatesAMarkerWithoutTouchingAnotherAccount() = serverTest("cloud-missing-delete") { c ->
        val first = login(c, "missing-first@airstudy.test"); val other = login(c, "missing-other@airstudy.test")
        upload(c, first, down("private-record"))
        assertThat(send(c, other, HttpMethod.Delete, path(FlightRecordMode.DOWN, "private-record")).status.value).isEqualTo(200)
        assertThat(page(c, other).items.single().deleted).isTrue()
        assertThat(page(c, first).items.single().deleted).isFalse()
        assertThat(upload(c, other, down("private-record")).status.value).isEqualTo(410)
    }

    @Test fun scenePatchUsesTheConfirmedFieldsAndPreservesOriginalArchive() = serverTest("cloud-scene") { c ->
        val token = login(c, "scene@airstudy.test"); val record = down("scene-record")
        val initial = revision(upload(c, token, record))
        val response = send(c, token, HttpMethod.Patch, path(record.mode, record.id) + "/focus-scene",
            """{"focusIconType":0,"focusScenario":"复习"}""", "scene-once", initial)
        assertThat(response.status.value).isEqualTo(200)
        val next = revision(response); assertThat(next).isGreaterThan(initial)
        val stored = page(c, token).items.single()
        assertThat(stored.archive).isEqualTo(record)
        assertThat(stored.scene).isEqualTo(FlightRecordScenePatch(1, "复习"))
        assertThat(upload(c, token, record).status.value).isEqualTo(200)
        assertThat(page(c, token).items.single().scene).isEqualTo(stored.scene)
        assertThat(revision(send(c, token, HttpMethod.Patch, path(record.mode, record.id) + "/focus-scene",
            """{"focusIconType":0,"focusScenario":"复习"}""", "scene-once", initial))).isEqualTo(next)
    }

    @Test fun staleRevisionsAndReusedOperationKeysAreRejectedWithoutChangingAnything() = serverTest("cloud-conflict") { c ->
        val token = login(c, "conflict@airstudy.test"); val record = down("conflict-record")
        val initial = revision(upload(c, token, record))
        val endpoint = path(record.mode, record.id) + "/focus-scene"
        val body = """{"focusIconType":2,"focusScenario":"Reading"}"""
        assertThat(send(c, token, HttpMethod.Patch, endpoint, body, "scene-key", initial).status.value).isEqualTo(200)
        val before = page(c, token)
        assertThat(send(c, token, HttpMethod.Delete, path(record.mode, record.id), operation = "scene-key").status.value).isEqualTo(409)
        assertThat(send(c, token, HttpMethod.Delete, path(record.mode, record.id), revision = initial).status.value).isEqualTo(409)
        assertThat(send(c, token, HttpMethod.Patch, endpoint, body, "different-key", initial).status.value).isEqualTo(409)
        assertThat(page(c, token)).isEqualTo(before)
    }

    @Test fun sceneChangesRejectMissingCancelledAndDeletedRecords() = serverTest("cloud-scene-protection") { c ->
        val token = login(c, "protection@airstudy.test"); val id = "uneditable"
        val endpoint = path(FlightRecordMode.UP, id) + "/focus-scene"
        val body = """{"focusIconType":1,"focusScenario":"Study"}"""
        assertThat(send(c, token, HttpMethod.Patch, endpoint, body).status.value).isEqualTo(404)
        upload(c, token, down(id, "CANCELLED"))
        assertThat(send(c, token, HttpMethod.Patch, path(FlightRecordMode.DOWN, id) + "/focus-scene", body).status.value).isEqualTo(409)
        send(c, token, HttpMethod.Delete, path(FlightRecordMode.DOWN, id))
        assertThat(send(c, token, HttpMethod.Patch, path(FlightRecordMode.DOWN, id) + "/focus-scene", body).status.value).isEqualTo(404)
    }

    @Test fun formEncodedIdentifiersAreDecodedExactlyOnce() = serverTest("cloud-encoded-key") { c ->
        val token = login(c, "encoded@airstudy.test"); val id = "航班 + %2F / F"
        assertThat(upload(c, token, down(id)).status.value).isEqualTo(200)
        assertThat(page(c, token).items.single().id).isEqualTo(id)
        assertThat(send(c, token, HttpMethod.Delete, path(FlightRecordMode.DOWN, id)).status.value).isEqualTo(200)
        assertThat(page(c, token).items.single().deleted).isTrue()
    }

    @Test fun cursorsIncludeDeletionMarkersWithoutRepeatingUnchangedRecords() = serverTest("cloud-cursor") { c ->
        val token = login(c, "cursor@airstudy.test")
        for (id in listOf("first", "second", "third")) upload(c, token, down(id))
        val first = page(c, token, "?after=0&limit=2")
        assertThat(first.items.map { it.id }).containsExactly("first", "second").inOrder(); assertThat(first.hasMore).isTrue()
        send(c, token, HttpMethod.Delete, path(FlightRecordMode.DOWN, "first"))
        val next = page(c, token, "?after=${first.nextCursor}&limit=2")
        assertThat(next.items.map { it.id }).containsExactly("third", "first").inOrder()
        assertThat(next.items.last().deleted).isTrue(); assertThat(next.hasMore).isFalse()
        assertThat(page(c, token, "?after=${next.nextCursor}").items).isEmpty()
    }

    @Test fun simultaneousCopiesOfAnOperationProduceOneReceiptAndOneRevision() = serverTest("cloud-concurrent") { c ->
        val token = login(c, "concurrent@airstudy.test"); val record = down("concurrent")
        upload(c, token, record)
        val responses = coroutineScope {
            val gate = CompletableDeferred<Unit>()
            val work = (1..4).map { async { gate.await(); send(c, token, HttpMethod.Delete, path(record.mode, record.id), operation = "same-delete") } }
            gate.complete(Unit); work.awaitAll()
        }
        assertThat(responses.map { it.status.value }).containsExactly(200, 200, 200, 200)
        assertThat(responses.map { revision(it) }.distinct()).hasSize(1)
        assertThat(page(c, token).items.single().revision).isEqualTo(2L)
    }

    @Test fun legacyAndFullArchivesDoNotDoubleCountAndBothDeletionPathsUpdateTotals() = serverTest("cloud-legacy-totals") { c ->
        val token = login(c, "legacy-totals@airstudy.test"); val id = UUID.randomUUID().toString()
        val body = """{"sessions":[{"id":"$id","departureAirportCode":"PEK","destinationAirportCode":"LHR","startTimeMillis":1000000,"durationMinutes":45,"actualFocusMinutes":35,"distanceKm":600.0,"status":"COMPLETED","createdAt":1000000,"updatedAt":3700000}]}"""
        c.withToken(token).post("/session/sync", body); upload(c, token, down(id)); upload(c, token, up("free"))
        assertThat(c.str(c.get("/user/me"), "totalFocusedMinutes")).isEqualTo("67")
        assertThat(c.delete("/session/$id").status.value).isEqualTo(200)
        assertThat(page(c, token).items.single { it.id == id }.deleted).isTrue()
        assertThat(c.str(c.get("/user/me"), "totalFocusedMinutes")).isEqualTo("32")
        assertThat(c.str(c.post("/session/sync", body), "accepted")).isEqualTo("0")
    }

    @Test fun legacySceneAnnotationsKeepTheirExistingTotalsUntilAFullSnapshotArrives() = serverTest("cloud-legacy-scene") { c ->
        val token = login(c, "legacy-scene@airstudy.test"); val id = UUID.randomUUID().toString()
        val body = """{"sessions":[{"id":"$id","departureAirportCode":"PEK","destinationAirportCode":"LHR","startTimeMillis":1000000,"durationMinutes":45,"actualFocusMinutes":35,"distanceKm":600.0,"status":"COMPLETED","createdAt":1000000,"updatedAt":3700000}]}"""
        c.withToken(token).post("/session/sync", body)
        assertThat(send(c, token, HttpMethod.Patch, path(FlightRecordMode.DOWN, id) + "/focus-scene",
            """{"focusIconType":2,"focusScenario":"Read"}""").status.value).isEqualTo(200)
        c.post("/session/sync", body)
        assertThat(c.str(c.get("/user/me"), "totalFocusedMinutes")).isEqualTo("35")
        assertThat(page(c, token).items.single().archive).isNull()
        upload(c, token, down(id))
        assertThat(page(c, token).items.single().scene).isEqualTo(FlightRecordScenePatch(2, "Read"))
        assertThat(c.str(c.get("/user/me"), "totalFocusedMinutes")).isEqualTo("35")
    }

    @Test fun metadataOnlyCountUpRecordsCanChangeScenesAndDeleteWithoutCreatingArchives() = serverTest("summary-only-actions") { c ->
        val token = login(c, "summary-actions@airstudy.test")
        val user = c.str(c.withToken(token).get("/user/me"), "id")!!
        val id = "summary-action-record"
        seedSummary(user, id, "up", "arrived")
        upload(c, token, down(id))
        val response = send(c, token, HttpMethod.Patch, path(FlightRecordMode.UP, id) + "/focus-scene",
            """{"focusIconType":2,"focusScenario":"Work"}""", "summary-scene", 1)
        assertThat(response.status.value).isEqualTo(200)
        val receipt = revision(response)
        assertThat(page(c, token).items.single { it.mode == FlightRecordMode.UP }.archive).isNull()
        val partial = summaries(c, token).records.map(FlightRecordSummary::parse).single { it.mode == FlightRecordMode.UP }
        assertThat(partial.sceneLabel).isEqualTo("Work"); assertThat(partial.departure?.latitude).isNull()
        assertThat(partial.flightNumber).isEqualTo("AS321")
        assertThat(send(c, token, HttpMethod.Delete, path(FlightRecordMode.UP, id), operation = "summary-delete", revision = receipt).status.value).isEqualTo(200)
        assertThat(summaries(c, token).records.map(FlightRecordSummary::parse).single().mode).isEqualTo(FlightRecordMode.DOWN)
        val second = login(c, "summary-actions-other@airstudy.test")
        assertThat(send(c, second, HttpMethod.Patch, path(FlightRecordMode.DOWN, id) + "/focus-scene",
            """{"focusIconType":2,"focusScenario":"Work"}""").status.value).isEqualTo(404)
    }

    @Test fun metadataActionsProtectUnknownAndOngoingRecordsInTheirOwnMode() = serverTest("summary-action-protection") { c ->
        val token = login(c, "summary-protection@airstudy.test")
        val user = c.str(c.withToken(token).get("/user/me"), "id")!!
        for ((index, status) in listOf("in_progress", "landing", "unknown", "pending").withIndex()) {
            val id = "protected-$index"; seedSummary(user, id, "up", status, index + 1L)
            upload(c, token, down(id))
            val patch = send(c, token, HttpMethod.Patch, path(FlightRecordMode.UP, id) + "/focus-scene",
                """{"focusIconType":2,"focusScenario":"Work"}""")
            assertThat(patch.status.value).isEqualTo(409)
            assertThat(send(c, token, HttpMethod.Delete, path(FlightRecordMode.UP, id)).status.value).isEqualTo(409)
        }
        val id = "cancelled-summary"; seedSummary(user, id, "up", "cancelled", 20)
        assertThat(send(c, token, HttpMethod.Patch, path(FlightRecordMode.UP, id) + "/focus-scene",
            """{"focusIconType":2,"focusScenario":"Work"}""").status.value).isEqualTo(409)
        assertThat(send(c, token, HttpMethod.Delete, path(FlightRecordMode.UP, id)).status.value).isEqualTo(200)
    }

    private fun seedSummary(user: String, id: String, mode: String, status: String, revision: Long = 1) = transaction {
        val next = maxOf(revision, (CloudFlightRecords.selectAll().where { CloudFlightRecords.userId eq user }
            .maxOfOrNull { it[CloudFlightRecords.revision] } ?: 0L) + 1L)
        CloudFlightRecords.insert {
            it[userId] = user; it[CloudFlightRecords.recordId] = id; it[CloudFlightRecords.mode] = mode
            it[CloudFlightRecords.revision] = next; it[deleted] = false; it[archiveJson] = null; it[sceneJson] = null
            it[summaryJson] = buildJsonObject {
                put("flightRecordId", id); put("recordType", mode); put("flightStatus", status)
                put("flightNumber", "AS321"); put("departureAirportCode", "PEK")
            }.toString()
        }
    }

    @Test fun failedAggregateWriteRollsBackDeletionMarkerArchiveAndRetryReceiptTogether() = serverTest("cloud-rollback") { c ->
        val token = login(c, "rollback@airstudy.test"); val record = down("rollback-record")
        upload(c, token, record)
        val me = c.withToken(token).get("/user/me"); val user = c.str(me, "id")!!
        transaction { exec("ALTER TABLE users ADD CONSTRAINT cloud_minutes_nonzero CHECK (id <> '$user' OR total_focused_minutes > 0)") }
        val failed = send(c, token, HttpMethod.Delete, path(record.mode, record.id), operation = "recoverable-delete")
        assertThat(failed.status.value).isEqualTo(503)
        assertThat(page(c, token).items.single().archive).isEqualTo(record)
        transaction { exec("ALTER TABLE users DROP CONSTRAINT cloud_minutes_nonzero") }
        assertThat(send(c, token, HttpMethod.Delete, path(record.mode, record.id), operation = "recoverable-delete").status.value).isEqualTo(200)
        assertThat(page(c, token).items.single().deleted).isTrue()
        assertThat(page(c, token).items.single().revision).isEqualTo(2L)
    }

    @Test fun missingAuthenticationInvalidModeAndInvalidCursorNeverChangeData() = serverTest("cloud-invalid-request") { c ->
        val token = login(c, "invalid@airstudy.test")
        assertThat(send(c, null, HttpMethod.Get, "/v2/flight-records").status.value).isEqualTo(401)
        assertThat(send(c, "invalid", HttpMethod.Delete, path(FlightRecordMode.DOWN, "unknown")).status.value).isEqualTo(401)
        assertThat(send(c, token, HttpMethod.Delete, "/v2/flight-records/other/unknown").status.value).isEqualTo(400)
        for (query in listOf("?after=-1", "?after=oops", "?limit=0", "?limit=101"))
            assertThat(send(c, token, HttpMethod.Get, "/v2/flight-records$query").status.value).isEqualTo(400)
        assertThat(send(c, token, HttpMethod.Put, path(FlightRecordMode.UP, "mismatch"),
            codec.encodeToString(FlightRecordArchiveUpload(down("mismatch")))).status.value).isEqualTo(400)
        assertThat(page(c, token).items).isEmpty()
    }

    @Test fun deletionMarkersAndRetryReceiptsSurviveAnApplicationRestart() {
        var token = ""
        var acceptedRevision = 0L
        val record = down("restart-record")
        serverTest("cloud-restart") { c ->
            token = login(c, "restart@airstudy.test")
            upload(c, token, record)
            acceptedRevision = revision(send(c, token, HttpMethod.Delete, path(record.mode, record.id), operation = "before-restart"))
        }
        serverTest("cloud-restart") { c ->
            val retried = send(c, token, HttpMethod.Delete, path(record.mode, record.id), operation = "before-restart")
            assertThat(retried.status.value).isEqualTo(200)
            assertThat(revision(retried)).isEqualTo(acceptedRevision)
            assertThat(page(c, token).items.single().deleted).isTrue()
            assertThat(upload(c, token, record).status.value).isEqualTo(410)
        }
    }

    @Test fun ongoingArchivesAndInvalidPositionSequencesCannotEnterCloudHistory() = serverTest("cloud-invalid-archive") { c ->
        val token = login(c, "invalid-archive@airstudy.test")
        val record = up("invalid-positions")
        val duplicates = record.copy(positions = record.positions + record.positions)
        assertThat(upload(c, token, duplicates).status.value).isEqualTo(400)
        val ongoing = record.copy(countUp = record.countUp!!.copy(status = FreeFlightStatus.ONGOING,
            phase = FreeFlightPhase.CRUISING, endedAtWallMs = null, landingAirport = null))
        assertThat(upload(c, token, ongoing).status.value).isEqualTo(400)
        assertThat(upload(c, token, down("ongoing").copy(countdown = down("ongoing").countdown!!.copy(status = "ONGOING"))).status.value).isEqualTo(400)
        assertThat(page(c, token).items).isEmpty()
    }
}
