package com.projectfocus.server

import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.network.dto.FlightPlanActions
import com.projectfocus.server.config.ServerConfig
import com.projectfocus.server.security.JwtService
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Real Netty loopback + JDK WS + SQL; no route/service mocks or original service. */
class FlightPlanSocketTest {
    @Test
    fun flatRoundtripAndReplace() {
        RunningServer(config()).use { server ->
            server.socket(server.registerAndLogin("a").token).use { socket ->
                val absentLocales = JsonObject(airport().filterKeys { it !in setOf("name_i18n", "city_i18n") })
                val nullLocales = copy(copy(airport(), "name_i18n", JsonNull), "city_i18n", JsonNull)
                val nullableLeg = copy(copy(leg(2, "canceled", terminal = true), "departure_airport", absentLocales),
                    "arrival_airport", nullLocales)
                val originalLegs = JsonArray(listOf(leg(1), nullableLeg))
                val saved = socket.exchange(upsert("save", originalLegs))
                flat(saved, "save", true)
                assertThat(day(saved)["plan_date"]!!.jsonPrimitive.content).isEqualTo("2026-10-30")
                assertThat(day(saved)["local_plan_date"]!!.jsonPrimitive.content).isEqualTo("2026-10-31")
                assertThat(day(saved)["legs"]).isEqualTo(originalLegs)
                val read = socket.exchange(get("read"))
                flat(read, "read", true)
                assertThat(day(read)["legs"]).isEqualTo(originalLegs)
                val departure = day(read)["legs"]!!.jsonArray[0].jsonObject["departure_airport"]!!.jsonObject
                assertThat(departure["latitude"]!!.jsonPrimitive.isString).isTrue()
                assertThat(departure["id"]!!.jsonPrimitive.intOrNull).isEqualTo(-7)
                assertThat(departure["name_i18n"]).isInstanceOf(JsonObject::class.java)
                val restoredNull = day(read)["legs"]!!.jsonArray[1].jsonObject
                assertThat(restoredNull["departure_airport"]!!.jsonObject.containsKey("name_i18n")).isFalse()
                assertThat(restoredNull["arrival_airport"]!!.jsonObject["name_i18n"]).isEqualTo(JsonNull)
                assertThat(day(read)["legs"]!!.jsonArray[0].jsonObject.containsKey("arrival_diff_minutes")).isFalse()

                val replacement = JsonArray(listOf(leg(1, "canceled", terminal = true)))
                flat(socket.exchange(upsert("replace", replacement)), "replace", true)
                assertThat(day(socket.exchange(get("reread")))["legs"]).isEqualTo(replacement)
                flat(socket.exchange(upsert("empty", JsonArray(emptyList()))), "empty", true)
                assertThat(day(socket.exchange(get("empty-read")))["legs"]!!.jsonArray).isEmpty()
            }
            server.sql("SELECT COUNT(*) FROM flights") { assertThat(it).isEqualTo(0) }
        }
    }

    @Test
    fun jwtAndOwnerIsolation() {
        RunningServer(config()).use { server ->
            val a = server.registerAndLogin("a")
            val b = server.registerAndLogin("b")
            for (token in listOf<String?>(null, "invalid-token", JwtService(server.config.jwt).issueAccessToken(
                a.id, "a@pf.dev", System.currentTimeMillis() - server.config.jwt.ttlMillis - 60_000L,
            ))) {
                val error = assertThrows(ExecutionException::class.java) { server.connect(token).get(5, TimeUnit.SECONDS) }
                assertThat((error.cause as WebSocketHandshakeException).response.statusCode()).isEqualTo(401)
            }
            server.socket(a.token).use { one ->
                val spoofed = copy(upsert("owner", JsonArray(listOf(leg(1)))), "user_id", JsonPrimitive(b.id))
                flat(one.exchange(spoofed), "owner", true)
            }
            server.socket(b.token).use { other ->
                assertThat(day(other.exchange(get("other")))["legs"]!!.jsonArray).isEmpty()
                val history = other.exchange(history("other-history"))
                assertThat(data(history)["plans"]!!.jsonArray).isEmpty()
            }
            server.socket(a.token).use { one ->
                assertThat(day(one.exchange(get("own")))["legs"]!!.jsonArray).hasSize(1)
                // The same owner/local day in a different zone is a different key.
                assertThat(day(one.exchange(get("utc", zone = "UTC")))["legs"]!!.jsonArray).isEmpty()
                flat(one.exchange(upsert("utc-save", JsonArray(listOf(leg(1))), zone = "UTC")), "utc-save", true)
                assertThat(data(one.exchange(history("tz-history")))["plans"]!!.jsonArray).hasSize(1)
            }
        }
    }

    @Test
    fun historyRangeIsNotLegCap() {
        RunningServer(config()).use { server ->
            server.socket(server.registerAndLogin("a").token).use { socket ->
                val many = JsonArray((1..201).map { leg(it, if (it == 2) "canceled" else "waiting_takeoff") })
                flat(socket.exchange(upsert("many", many)), "many", true)
                flat(socket.exchange(upsert("old", JsonArray(listOf(leg(1))), local = "2026-10-29")), "old", true)
                flat(socket.exchange(upsert("past", JsonArray(listOf(leg(1))), local = "2026-10-30")), "past", true)
                val result = socket.exchange(history("range", "2026-10-30", "2026-10-31"))
                flat(result, "range", true)
                val plans = data(result)["plans"]!!.jsonArray
                assertThat(plans).hasSize(2)
                assertThat(plans[0].jsonObject["local_plan_date"]!!.jsonPrimitive.content).isEqualTo("2026-10-30")
                assertThat(plans[0].jsonObject["archived"]!!.jsonPrimitive.booleanOrNull).isTrue()
                assertThat(plans[0].jsonObject["readonly"]!!.jsonPrimitive.booleanOrNull).isTrue()
                assertThat(plans[1].jsonObject["readonly"]!!.jsonPrimitive.booleanOrNull).isFalse()
                assertThat(plans[1].jsonObject["legs"]!!.jsonArray).hasSize(201)
                val limited = socket.exchange(history("one-day", "2026-10-31", "2026-10-31", limit = 1))
                assertThat(data(limited)["plans"]!!.jsonArray.single().jsonObject["legs"]!!.jsonArray).hasSize(201)
            }
        }
    }

    @Test
    fun invalidFramesKeepSession() {
        RunningServer(config()).use { server ->
            server.socket(server.registerAndLogin("a").token).use { socket ->
                flat(socket.exchange("{broken"), "", false)
                flat(socket.exchange("""{"request_id":"unknown","action":"not-supported"}"""), "unknown", false)
                val original = JsonArray(listOf(leg(1)))
                flat(socket.exchange(upsert("initial", original)), "initial", true)
                val invalid = listOf(
                    upsert("bad-index", JsonArray(listOf(copy(leg(1), "leg_index", JsonPrimitive(2))))),
                    upsert("numeric-coord", JsonArray(listOf(copy(leg(1), "departure_airport",
                        copy(airport(), "latitude", JsonPrimitive(1.0)))))),
                    upsert("nan-coord", JsonArray(listOf(copy(leg(1), "departure_airport",
                        copy(airport(), "latitude", JsonPrimitive("NaN")))))),
                    upsert("range-coord", JsonArray(listOf(copy(leg(1), "arrival_airport",
                        copy(airport(), "longitude", JsonPrimitive("181")))))),
                    upsert("array-i18n", JsonArray(listOf(copy(leg(1), "arrival_airport",
                        copy(airport(), "city_i18n", JsonArray(emptyList())))))),
                    copy(get("bad-date"), "plan_date", JsonPrimitive("2026-2-3")),
                    copy(history("bad-range"), "startDate", JsonPrimitive("2026-11-02")),
                )
                for (request in invalid) {
                    flat(socket.exchange(request), request["request_id"]!!.jsonPrimitive.content, false)
                }
                assertThat(day(socket.exchange(get("still-present")))["legs"]).isEqualTo(original)
            }
        }
    }

    @Test
    fun sqlFailureRollsBack() {
        RunningServer(config()).use { server ->
            server.socket(server.registerAndLogin("a").token).use { socket ->
                val before = day(socket.exchange(upsert("before", JsonArray(listOf(leg(1), leg(2, "canceled"))))))
                server.execute("ALTER TABLE flight_plan_days ADD CONSTRAINT plan_test_rollback " +
                    "CHECK (legs_json NOT LIKE '%reject-in-db%')")
                val rejected = copy(leg(1), "flight_no", JsonPrimitive("reject-in-db"))
                val failure = socket.exchange(upsert("reject", JsonArray(listOf(rejected)), planDate = "2026-10-28"))
                flat(failure, "reject", false)
                assertThat(data(failure)["error_code"]!!.jsonPrimitive.intOrNull).isEqualTo(5005)
                assertThat(day(socket.exchange(get("after")))).isEqualTo(before)
            }
        }
    }

    @Test
    fun diskReopenRetainsPlan(@TempDir directory: Path) {
        val url = "jdbc:h2:file:${directory.resolve("p").toAbsolutePath().toString().replace('\\', '/')};MODE=PostgreSQL"
        val config = config(url)
        val expected = JsonArray(listOf(leg(1), leg(2, "canceled", terminal = true)))
        val token = RunningServer(config).use { first ->
            val value = first.registerAndLogin("a").token
            first.socket(value).use { socket -> flat(socket.exchange(upsert("disk-save", expected)), "disk-save", true) }
            value
        } // pool stop + explicit H2 SHUTDOWN flushes and closes the actual file.
        RunningServer(config).use { second ->
            second.socket(token).use { socket ->
                val result = socket.exchange(get("disk-read"))
                flat(result, "disk-read", true)
                assertThat(day(result)["legs"]).isEqualTo(expected)
                assertThat(day(result)["plan_date"]!!.jsonPrimitive.content).isEqualTo("2026-10-30")
            }
        }
    }

    @Test
    fun canceledBatchDeletesOnlyTheAuthenticatedExactDayAndRetainsOtherFields() {
        RunningServer(config()).use { server ->
            val owner = server.registerAndLogin("batch-owner")
            val other = server.registerAndLogin("batch-other")
            val mixed = JsonArray(listOf(leg(1, "canceled"), leg(2),
                copy(leg(3, "completed", terminal = true), "future_field", JsonPrimitive("preserved")),
                leg(4, "canceled", terminal = true)))
            server.socket(other.token).use { socket -> flat(socket.exchange(upsert("other-save", mixed)), "other-save", true) }
            server.socket(owner.token).use { socket ->
                flat(socket.exchange(upsert("batch-save", mixed)), "batch-save", true)
                flat(socket.exchange(upsert("batch-tomorrow", mixed, local = "2026-11-01")), "batch-tomorrow", true)
                flat(socket.exchange(upsert("batch-utc", mixed, zone = "UTC")), "batch-utc", true)
                val spoofed = copy(cancelBatch("batch-delete"), "user_scope_id", JsonPrimitive(other.id))
                val response = socket.exchange(copy(spoofed, "userScopeId", JsonPrimitive(other.id)))
                flat(response, "batch-delete", true)
                assertThat(data(response)["deletedCount"]!!.jsonPrimitive.intOrNull).isEqualTo(2)
                assertThat(day(response)["plan_date"]!!.jsonPrimitive.content).isEqualTo("2026-10-30")
                val retained = JsonArray(listOf(copy(mixed[1].jsonObject, "leg_index", JsonPrimitive(1)),
                    copy(mixed[2].jsonObject, "leg_index", JsonPrimitive(2))))
                assertThat(day(response)["legs"]).isEqualTo(retained)
                assertThat(day(socket.exchange(get("batch-read")))["legs"]).isEqualTo(retained)
                assertThat(day(socket.exchange(get("tomorrow-read", local = "2026-11-01")))["legs"]).isEqualTo(mixed)
                assertThat(day(socket.exchange(get("utc-read", zone = "UTC")))["legs"]).isEqualTo(mixed)
                val repeated = socket.exchange(cancelBatch("batch-repeat"))
                assertThat(data(repeated)["deletedCount"]!!.jsonPrimitive.intOrNull).isEqualTo(0)
                assertThat(day(repeated)["legs"]).isEqualTo(retained)
            }
            server.socket(other.token).use { socket ->
                assertThat(day(socket.exchange(get("other-read")))["legs"]).isEqualTo(mixed)
            }
            server.sql("SELECT COUNT(*) FROM flights") { assertThat(it).isEqualTo(0) }
        }
    }

    @Test
    fun canceledBatchRejectsConflictingAliasesAndRollsBackSqlFailure() {
        RunningServer(config()).use { server ->
            server.socket(server.registerAndLogin("batch-failure").token).use { socket ->
                val original = JsonArray(listOf(leg(1, "canceled"), leg(2, "completed")))
                flat(socket.exchange(upsert("batch-before", original)), "batch-before", true)
                val inconsistent = copy(cancelBatch("batch-conflict"), "targetLocalPlanDate", JsonPrimitive("2026-11-01"))
                flat(socket.exchange(inconsistent), "batch-conflict", false)
                assertThat(day(socket.exchange(get("after-conflict")))["legs"]).isEqualTo(original)
                server.execute("ALTER TABLE flight_plan_days ADD CONSTRAINT batch_rollback " +
                    "CHECK (legs_json LIKE '%canceled%')")
                val rejected = socket.exchange(cancelBatch("batch-sql-failed"))
                flat(rejected, "batch-sql-failed", false)
                assertThat(data(rejected)["error_code"]!!.jsonPrimitive.intOrNull).isEqualTo(5005)
                assertThat(day(socket.exchange(get("after-batch-failure")))["legs"]).isEqualTo(original)
            }
        }
    }

    @Test
    fun legStatusCancelAndCompletionUpdateInPlaceAndKeepFirstTerminalMetadata() {
        RunningServer(config()).use { server ->
            server.socket(server.registerAndLogin("status-owner").token).use { socket ->
                val active = copy(copy(leg(1, "in_progress"), "expected_arrival_at",
                    JsonPrimitive("2026-10-31T00:08:00Z")), "scheduled_arrival_at",
                    JsonPrimitive("2026-10-31T00:10:00Z"))
                val original = JsonArray(listOf(active, leg(2), leg(3, "completed", terminal = true)))
                flat(socket.exchange(upsert("status-save", original)), "status-save", true)

                val canceled = socket.exchange(legStatus("status-cancel", 1, "waiting_takeoff"))
                flat(canceled, "status-cancel", true)
                val afterCancel = JsonArray(listOf(copy(active, "leg_status", JsonPrimitive("waiting_takeoff")),
                    original[1], original[2]))
                assertThat(day(canceled)["legs"]).isEqualTo(afterCancel)
                assertThat(day(canceled)["plan_date"]!!.jsonPrimitive.content).isEqualTo("2026-10-30")

                val restarted = socket.exchange(legStatus("status-restart", 1, "in_progress", flight = "PF8", seat = "02C"))
                flat(restarted, "status-restart", true)
                val restartedLeg = day(restarted)["legs"]!!.jsonArray[0].jsonObject
                assertThat(restartedLeg["flight_no"]!!.jsonPrimitive.content).isEqualTo("PF8")
                assertThat(restartedLeg["seat_no"]!!.jsonPrimitive.content).isEqualTo("02C")
                assertThat(restartedLeg["future_metadata"]).isEqualTo(active["future_metadata"])

                val finished = socket.exchange(legStatus("status-complete", 1, "completed",
                    actual = "2026-10-31T00:06:00Z", diff = -2))
                flat(finished, "status-complete", true)
                val expected = JsonObject(restartedLeg + mapOf("leg_status" to JsonPrimitive("completed"),
                    "actual_arrival_at" to JsonPrimitive("2026-10-31T00:06:00Z"),
                    "arrival_diff_minutes" to JsonPrimitive(-2)))
                assertThat(day(finished)["legs"]).isEqualTo(JsonArray(listOf(expected, original[1], original[2])))

                // Own state idempotence: changed retry metadata never overwrites first completion.
                val duplicate = socket.exchange(legStatus("status-complete-retry", 1, "completed",
                    actual = "2026-10-31T00:07:59Z", diff = 0, seat = "03A"))
                flat(duplicate, "status-complete-retry", true)
                assertThat(day(duplicate)).isEqualTo(day(finished))
                val conflict = socket.exchange(legStatus("status-late-cancel", 1, "waiting_takeoff"))
                flat(conflict, "status-late-cancel", false)
                assertThat(data(conflict)["code"]!!.jsonPrimitive.content).isEqualTo("LEG_TERMINAL_CONFLICT")
                assertThat(day(socket.exchange(get("status-read")))).isEqualTo(day(finished))
            }
            server.sql("SELECT COUNT(*) FROM flights") { assertThat(it).isEqualTo(0) }
        }
    }

    @Test
    fun legStatusUsesJwtOwnerAndExactDayZoneAndRejectsBadFieldsWithoutCreatingRows() {
        RunningServer(config()).use { server ->
            val owner = server.registerAndLogin("status-isolated")
            val other = server.registerAndLogin("status-other")
            val original = JsonArray(listOf(leg(1), leg(2, "canceled")))
            server.socket(owner.token).use { socket -> flat(socket.exchange(upsert("status-owned", original)), "status-owned", true) }
            server.socket(other.token).use { socket ->
                val spoofed = copy(legStatus("status-spoof", 1, "in_progress", flight = "not-owner"), "owner_id", JsonPrimitive(owner.id))
                flat(socket.exchange(spoofed), "status-spoof", false)
            }
            server.socket(owner.token).use { socket ->
                val requests = listOf(
                    legStatus("status-wrong-zone", 1, "in_progress", zone = "UTC"),
                    legStatus("status-wrong-day", 1, "in_progress", local = "2026-11-01"),
                    legStatus("status-no-leg", 99, "in_progress"),
                    legStatus("status-zero", 0, "in_progress"),
                    copy(legStatus("status-index-string", 1, "in_progress"), "leg_index", JsonPrimitive("1")),
                    copy(legStatus("status-offset-string", 1, "in_progress"), "timezone_offset_minutes", JsonPrimitive("480")),
                    legStatus("status-unknown", 1, "unknown-status"),
                    legStatus("status-invalid-zone", 1, "in_progress", zone = "Invalid/Zone"),
                    legStatus("status-bad-date", 1, "in_progress", local = "2026-2-3"),
                    legStatus("status-bad-arrival", 1, "completed", actual = "not-a-time"),
                    legStatus("status-waiting-arrival", 1, "waiting_takeoff", actual = "2026-10-31T00:06:00Z"),
                    copy(legStatus("status-flight-number", 1, "in_progress"), "flight_no", JsonPrimitive(123)),
                )
                for (request in requests) {
                    flat(socket.exchange(request), request["request_id"]!!.jsonPrimitive.content, false)
                }
                val terminal = socket.exchange(legStatus("status-canceled-conflict", 2, "in_progress"))
                flat(terminal, "status-canceled-conflict", false)
                assertThat(data(terminal)["code"]!!.jsonPrimitive.content).isEqualTo("LEG_TERMINAL_CONFLICT")
                assertThat(day(socket.exchange(get("status-unchanged")))["legs"]).isEqualTo(original)
                val noArrival = socket.exchange(legStatus("status-nullable-completion", 1, "completed"))
                flat(noArrival, "status-nullable-completion", true)
                val completed = day(noArrival)["legs"]!!.jsonArray[0].jsonObject
                assertThat(completed["leg_status"]!!.jsonPrimitive.content).isEqualTo("completed")
                assertThat(completed.containsKey("actual_arrival_at")).isFalse()
            }
            server.sql("SELECT COUNT(*) FROM flight_plan_days") { assertThat(it).isEqualTo(1) }
        }
    }

    @Test
    fun legStatusSqlFailureRollsBackAndTheIdenticalFrameCanRetry() {
        RunningServer(config()).use { server ->
            server.socket(server.registerAndLogin("status-rollback").token).use { socket ->
                val before = day(socket.exchange(upsert("status-before", JsonArray(listOf(leg(1), leg(2))))))
                server.execute("ALTER TABLE flight_plan_days ADD CONSTRAINT status_rollback " +
                    "CHECK (legs_json NOT LIKE '%status-reject-db%')")
                val request = legStatus("status-retry-same-id", 1, "completed",
                    actual = "2026-10-31T00:06:00Z", diff = -2, flight = "status-reject-db")
                val failed = socket.exchange(request)
                flat(failed, "status-retry-same-id", false)
                assertThat(data(failed)["error_code"]!!.jsonPrimitive.intOrNull).isEqualTo(5005)
                assertThat(day(socket.exchange(get("status-after-failure")))).isEqualTo(before)
                server.execute("ALTER TABLE flight_plan_days DROP CONSTRAINT status_rollback")
                val retried = socket.exchange(request)
                flat(retried, "status-retry-same-id", true)
                val changed = day(retried)["legs"]!!.jsonArray
                assertThat(changed).hasSize(2)
                assertThat(changed[0].jsonObject["leg_status"]!!.jsonPrimitive.content).isEqualTo("completed")
                assertThat(changed[0].jsonObject["actual_arrival_at"]!!.jsonPrimitive.content).isEqualTo("2026-10-31T00:06:00Z")
                assertThat(changed[1]).isEqualTo(before["legs"]!!.jsonArray[1])
            }
        }
    }

    @Test
    fun legStatusCompletionSurvivesSqlDiskReopen(@TempDir directory: Path) {
        val url = "jdbc:h2:file:${directory.resolve("status").toAbsolutePath().toString().replace('\\', '/')};MODE=PostgreSQL"
        val config = config(url)
        val token = RunningServer(config).use { server ->
            val user = server.registerAndLogin("status-durable")
            server.socket(user.token).use { socket ->
                flat(socket.exchange(upsert("status-disk-save", JsonArray(listOf(leg(1), leg(2, "canceled"))))), "status-disk-save", true)
                flat(socket.exchange(legStatus("status-disk-complete", 1, "completed",
                    actual = "2026-10-31T00:06:00Z", diff = -2)), "status-disk-complete", true)
            }
            user.token
        }
        RunningServer(config).use { server ->
            server.socket(token).use { socket ->
                val result = socket.exchange(get("status-disk-read"))
                flat(result, "status-disk-read", true)
                assertThat(day(result)["plan_date"]!!.jsonPrimitive.content).isEqualTo("2026-10-30")
                val legs = day(result)["legs"]!!.jsonArray
                assertThat(legs).hasSize(2)
                assertThat(legs[0].jsonObject["leg_status"]!!.jsonPrimitive.content).isEqualTo("completed")
                assertThat(legs[0].jsonObject["arrival_diff_minutes"]!!.jsonPrimitive.intOrNull).isEqualTo(-2)
                assertThat(legs[0].jsonObject["actual_arrival_at"]!!.jsonPrimitive.content).isEqualTo("2026-10-31T00:06:00Z")
                assertThat(legs[1].jsonObject["leg_status"]!!.jsonPrimitive.content).isEqualTo("canceled")
            }
        }
    }

    private fun legStatus(id: String, index: Int, target: String, actual: String? = null, diff: Int? = null,
                          flight: String? = null, seat: String? = null, local: String = "2026-10-31", zone: String = "Asia/Shanghai") = buildJsonObject {
        put("action", FlightPlanActions.LEG_STATUS_UPDATE); put("request_id", id)
        put("local_plan_date", local); put("timezone", zone)
        put("timezone_offset_minutes", if (zone == "Asia/Shanghai") 480 else 0)
        put("leg_index", index); put("target_status", target)
        actual?.let { put("actual_arrival_at", it) }; diff?.let { put("arrival_diff_minutes", it) }
        flight?.let { put("flight_no", it) }; seat?.let { put("seat_no", it) }
    }

    private fun cancelBatch(id: String) = buildJsonObject {
        put("action", FlightPlanActions.CANCEL_BATCH_DELETE)
        put("request_id", id)
        put("plan_date", "2026-10-30")
        for (key in listOf("local_plan_date", "localPlanDate", "target_local_plan_date", "targetLocalPlanDate")) {
            put(key, "2026-10-31")
        }
        put("timezone", "Asia/Shanghai")
        put("timezone_offset_minutes", 480)
        put("local_today", "2026-10-31"); put("localToday", "2026-10-31")
        put("local_tomorrow", "2026-11-01"); put("localTomorrow", "2026-11-01")
    }

    private data class User(val id: String, val token: String)

    private class RunningServer(val config: ServerConfig) : AutoCloseable {
        private val port = ServerSocket(0).use { it.localPort }
        private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        private val mailbox = FixtureRegistrationMailbox()
        private val engine = embeddedServer(Netty, port = port, host = "127.0.0.1") {
            module(config, flightPlanClock = Clock.fixed(Instant.parse("2026-10-31T00:00:00Z"), ZoneOffset.UTC),
                registrationEmailDelivery = mailbox)
        }.start(wait = false)

        fun registerAndLogin(suffix: String): User {
            val data = registerAndLoginOverHttp(http, "http://127.0.0.1:$port", mailbox,
                email = "plan-$suffix@pf.dev", password = "test-only-password-123!", nickname = "Pilot$suffix")
            return User(data["id"]!!.jsonPrimitive.content, data["token"]!!.jsonPrimitive.content)
        }

        fun connect(token: String?, listener: WebSocket.Listener = Frames()): CompletableFuture<WebSocket> {
            val builder = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
            token?.let { builder.header("Authorization", "Bearer $it") }
            return builder.buildAsync(URI("ws://127.0.0.1:$port/flight-plan"), listener)
        }

        fun socket(token: String): Socket {
            val frames = Frames()
            return Socket(connect(token, frames).get(5, TimeUnit.SECONDS), frames)
        }

        fun execute(sql: String) {
            DriverManager.getConnection(config.db.url, config.db.user, config.db.password).use { connection ->
                connection.createStatement().use { it.execute(sql) }
            }
        }

        fun sql(sql: String, check: (Int) -> Unit) {
            DriverManager.getConnection(config.db.url, config.db.user, config.db.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(sql).use { rows -> check(rows.let { if (it.next()) it.getInt(1) else -1 }) }
                }
            }
        }

        override fun close() {
            engine.stop(100, 1_000)
            if (config.db.url.startsWith("jdbc:h2:file:")) execute("SHUTDOWN")
        }
    }

    private class Frames : WebSocket.Listener {
        val messages = LinkedBlockingQueue<String>()
        private val partial = StringBuilder()
        override fun onOpen(webSocket: WebSocket) { webSocket.request(1) }
        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
            partial.append(data)
            if (last) { messages.offer(partial.toString()); partial.setLength(0) }
            webSocket.request(1)
            return CompletableFuture.completedFuture<Void?>(null)
        }
    }

    private class Socket(private val socket: WebSocket, private val frames: Frames) : AutoCloseable {
        fun exchange(request: JsonObject): JsonObject = exchange(request.toString())
        fun exchange(request: String): JsonObject {
            socket.sendText(request, true).get(5, TimeUnit.SECONDS)
            val reply = checkNotNull(frames.messages.poll(5, TimeUnit.SECONDS)) { "No WS response" }
            return Json.parseToJsonElement(reply).jsonObject
        }
        override fun close() {
            try { socket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").get(5, TimeUnit.SECONDS) }
            finally { socket.abort() }
        }
    }

    private fun flat(root: JsonObject, requestId: String, success: Boolean) {
        assertThat(root.keys).containsExactly("request_id", "success", "message", "data")
        assertThat(root["request_id"]!!.jsonPrimitive.content).isEqualTo(requestId)
        assertThat(root["success"]!!.jsonPrimitive.booleanOrNull).isEqualTo(success)
        assertThat(root["message"]!!.jsonPrimitive.isString).isTrue()
        assertThat(root["data"]).isInstanceOf(JsonObject::class.java)
    }

    private fun data(root: JsonObject) = root["data"]!!.jsonObject
    private fun day(root: JsonObject) = data(root)["legs"]!!.jsonObject
    private fun copy(root: JsonObject, key: String, value: kotlinx.serialization.json.JsonElement) =
        JsonObject(root + (key to value))

    private fun get(id: String, zone: String = "Asia/Shanghai", local: String = "2026-10-31") = buildJsonObject {
        put("action", FlightPlanActions.GET); put("request_id", id)
        put("plan_date", local); put("local_plan_date", local)
        put("timezone", zone); put("timezone_offset_minutes", 480)
    }

    private fun upsert(id: String, legs: JsonArray, local: String = "2026-10-31", zone: String = "Asia/Shanghai",
                       planDate: String = "2026-10-30") = buildJsonObject {
        put("action", FlightPlanActions.UPSERT); put("request_id", id)
        put("plan_date", planDate); put("local_plan_date", local)
        put("timezone", zone); put("timezone_offset_minutes", 480); put("legs", legs)
    }

    private fun history(id: String, start: String = "2026-10-01", end: String = "2026-10-31", limit: Int = 200) = buildJsonObject {
        put("action", FlightPlanActions.HISTORY); put("request_id", id)
        put("startDate", start); put("endDate", end); put("timezone", "Asia/Shanghai"); put("limit", limit)
    }

    private fun airport() = buildJsonObject {
        put("id", -7); put("code", "AAA"); put("name", "Frozen Airport"); put("city", "Frozen City")
        put("latitude", "0.000"); put("longitude", "120.000"); put("region", "CN")
        put("country_region_id", 0)
        put("name_i18n", buildJsonObject { put("zh", "机场") })
        put("city_i18n", buildJsonObject { put("zh", "城市") })
    }

    private fun leg(index: Int, status: String = "waiting_takeoff", terminal: Boolean = false) = buildJsonObject {
        put("leg_index", index); put("departure_airport", airport()); put("arrival_airport", airport())
        put("scheduled_departure_at", "2026-10-31T00:00:00Z"); put("scheduled_departure_local", "2026-10-31 08:00:00")
        put("leg_status", status); put("distance_km", 100.0); put("flight_no", ""); put("seat_no", "1A")
        put("focus_icon_code", 3); put("focus_scene_text", "阅读")
        put("future_metadata", buildJsonObject { put("keep", true) })
        if (terminal) {
            put("arrival_diff_minutes", -3); put("actual_arrival_at", "2026-10-31T00:05:00Z")
            put("expected_arrival_at", "2026-10-31T00:08:00Z")
        }
    }

    private fun config(url: String = "jdbc:h2:mem:plan_${UUID.randomUUID().toString().replace("-", "")};DB_CLOSE_DELAY=-1;MODE=PostgreSQL") =
        ServerConfig.fromEnv { key -> when (key) {
            "PF_ENV" -> "dev"
            "PF_DB_URL" -> url
            "PF_DB_USER" -> "sa"
            "PF_DB_PASSWORD" -> ""
            "PF_JWT_SECRET" -> "flight-plan-test-only-secret-at-least-32-bytes"
            "PF_RL_GENERAL", "PF_RL_JOIN", "PF_RL_CHAT" -> "100000"
            else -> null
        } }
}
