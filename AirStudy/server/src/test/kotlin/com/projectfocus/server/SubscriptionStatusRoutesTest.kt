package com.projectfocus.server

import com.google.common.truth.Truth.assertThat
import com.projectfocus.server.config.ServerConfig
import com.projectfocus.server.db.SubscriptionEntitlements
import com.projectfocus.server.db.Tables.Users
import com.projectfocus.server.security.JwtService
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Real Netty/JDK HTTP/JWT/SQL. Grants below are isolated DB fixtures, not API operations. */
class SubscriptionStatusRoutesTest {
    private val start = Instant.parse("2026-10-31T00:00:00Z")

    @Test fun jwtOwnerAndReadOnlyEndpointDoNotTreatUsersPremiumAsAnEntitlement() {
        RunningServer(config(), MutableClock(start)).use { server ->
            val one = server.registerAndLogin("sub-a")
            val two = server.registerAndLogin("sub-b")
            server.legacyPremium(one.id)
            val missing = server.status(one.token)
            assertThat(missing.statusCode()).isEqualTo(200)
            assertThat(missing.headers().firstValue("Cache-Control").orElse("")).contains("no-store")
            assertStatus(payload(missing), active = false, premium = false, days = 0, expiry = null)
            server.entitlement(two.id, start.minusSeconds(1), start.plusSeconds(86_400), premium = true)
            assertStatus(payload(server.status(two.token)), true, true, 1, start.plusSeconds(86_400).toString())
            assertStatus(payload(server.status(one.token, "?owner_id=${two.id}")), false, false, 0, null)

            val jwt = JwtService(server.config.jwt)
            val invalid = listOf<String?>(null, "invalid-token",
                jwt.issueAccessToken(one.id, "plan-sub-a@pf.dev", System.currentTimeMillis() - server.config.jwt.ttlMillis - 60_000),
                jwt.issueAccessToken(UUID.randomUUID().toString(), "missing@pf.dev"))
            for (token in invalid) assertThat(server.status(token).statusCode()).isEqualTo(401)
            for (path in listOf("/subscription/status", "/subscription/grant")) {
                val attemptedGrant = server.request(path, one.token, "POST", """{"isPremium":true,"remainingDays":9999}""")
                assertThat(attemptedGrant.statusCode()).isAtLeast(400)
                assertThat(attemptedGrant.statusCode()).isLessThan(500)
            }
            assertStatus(payload(server.status(one.token)), false, false, 0, null)
            server.count("SELECT COUNT(*) FROM subscription_entitlements") { assertThat(it).isEqualTo(1) }
        }
    }

    @Test fun finiteClockWindowUsesInclusiveStartExclusiveExpiryAndFixedDurationCeil() {
        val clock = MutableClock(start)
        RunningServer(config(), clock).use { server ->
            val user = server.registerAndLogin("sub-clock")
            val expiry = start.plusMillis(86_400_001)
            server.entitlement(user.id, start, expiry, premium = true)
            data class Case(val now: Instant, val active: Boolean, val days: Int)
            for (case in listOf(
                Case(start.minusMillis(1), false, 0), Case(start, true, 2),
                Case(start.plusMillis(1), true, 1), Case(expiry.minusMillis(1), true, 1),
                Case(expiry, false, 0), Case(expiry.plusMillis(1), false, 0))) {
                clock.set(case.now)
                assertStatus(payload(server.status(user.token)), case.active, case.active, case.days, expiry.toString())
            }
            clock.set(start)
            server.entitlement(user.id, start, start.plusSeconds(86_400), premium = false)
            assertStatus(payload(server.status(user.token)), true, false, 1, start.plusSeconds(86_400).toString())
            server.entitlement(user.id, start, start.plusSeconds(86_400), premium = true, revoked = true)
            assertStatus(payload(server.status(user.token)), false, false, 0, start.plusSeconds(86_400).toString())
            // Invalid persisted intervals are fail-closed, never an infinite grant.
            server.entitlement(user.id, start.plusSeconds(1), start, premium = true)
            assertStatus(payload(server.status(user.token)), false, false, 0, start.toString())
        }
    }

    @Test fun realSqlOutageReturnsFailureAndRecoveryReadsTheOriginalFiniteRecord() {
        RunningServer(config(), MutableClock(start)).use { server ->
            val user = server.registerAndLogin("sub-sql")
            val expiry = start.plusSeconds(86_400)
            server.entitlement(user.id, start, expiry, premium = true)
            server.execute("ALTER TABLE subscription_entitlements RENAME TO subscription_entitlements_offline")
            val failed = server.status(user.token)
            assertThat(failed.statusCode()).isEqualTo(503)
            val error = Json.parseToJsonElement(failed.body()).jsonObject["error"]!!.jsonObject
            assertThat(error["code"]!!.jsonPrimitive.intOrNull).isEqualTo(5001)
            server.execute("ALTER TABLE subscription_entitlements_offline RENAME TO subscription_entitlements")
            assertStatus(payload(server.status(user.token)), true, true, 1, expiry.toString())
        }
    }

    @Test fun entitlementAndRevocationSurvivePoolCloseAndDiskReopen(@TempDir directory: Path) {
        val url = "jdbc:h2:file:${directory.resolve("subscription").toAbsolutePath().toString().replace('\\', '/')};MODE=PostgreSQL"
        val config = config(url)
        val expiry = start.plusSeconds(2 * 86_400L)
        val user = RunningServer(config, MutableClock(start)).use { server ->
            val registered = server.registerAndLogin("sub-disk")
            server.entitlement(registered.id, start, expiry, premium = true)
            assertStatus(payload(server.status(registered.token)), true, true, 2, expiry.toString())
            registered
        }
        RunningServer(config, MutableClock(start.plusMillis(86_400_001))).use { server ->
            assertStatus(payload(server.status(user.token)), true, true, 1, expiry.toString())
            server.entitlement(user.id, start, expiry, premium = true, revoked = true)
        }
        RunningServer(config, MutableClock(start.plusMillis(86_400_001))).use { server ->
            assertStatus(payload(server.status(user.token)), false, false, 0, expiry.toString())
            server.count("SELECT COUNT(*) FROM subscription_entitlements") { assertThat(it).isEqualTo(1) }
        }
    }

    private fun payload(response: HttpResponse<String>): JsonObject {
        assertThat(response.statusCode()).isEqualTo(200)
        val root = Json.parseToJsonElement(response.body()).jsonObject
        assertThat(root["success"]!!.jsonPrimitive.booleanOrNull).isTrue()
        return root["data"]!!.jsonObject
    }

    private fun assertStatus(data: JsonObject, active: Boolean, premium: Boolean, days: Int, expiry: String?) {
        assertThat(data.keys).containsExactly("isActive", "isPremium", "remainingDays", "expiresAt")
        assertThat(data["isActive"]!!.jsonPrimitive.booleanOrNull).isEqualTo(active)
        assertThat(data["isPremium"]!!.jsonPrimitive.booleanOrNull).isEqualTo(premium)
        assertThat(data["remainingDays"]!!.jsonPrimitive.intOrNull).isEqualTo(days)
        if (expiry == null) assertThat(data["expiresAt"]).isEqualTo(JsonNull)
        else assertThat(data["expiresAt"]!!.jsonPrimitive.content).isEqualTo(expiry)
    }

    private data class User(val id: String, val token: String)
    private class MutableClock(private val now: AtomicReference<Instant>, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
        constructor(value: Instant) : this(AtomicReference(value))
        fun set(value: Instant) { now.set(value) }
        override fun instant(): Instant = now.get()
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
    }

    private class RunningServer(val config: ServerConfig, clock: Clock) : AutoCloseable {
        private val port = ServerSocket(0).use { it.localPort }
        private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        private val mailbox = FixtureRegistrationMailbox()
        private val engine = embeddedServer(Netty, port = port, host = "127.0.0.1") {
            module(config, subscriptionClock = clock, registrationEmailDelivery = mailbox, registrationClock = clock)
        }.start(wait = false)
        private val fixtures = Database.connect(config.db.url,
            driver = if (config.db.isH2) "org.h2.Driver" else "org.postgresql.Driver",
            user = config.db.user, password = config.db.password)

        fun registerAndLogin(suffix: String): User {
            val data = registerAndLoginOverHttp(http, "http://127.0.0.1:$port", mailbox,
                email = "plan-$suffix@pf.dev", password = "test-only-password-123!", nickname = "Pilot$suffix")
            return User(data["id"]!!.jsonPrimitive.content, data["token"]!!.jsonPrimitive.content)
        }
        fun status(token: String?, query: String = "") = request("/subscription/status$query", token)
        fun request(path: String, token: String?, method: String = "GET", body: String? = null): HttpResponse<String> {
            val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(5))
            token?.let { builder.header("Authorization", "Bearer $it") }
            if (body != null) builder.header("Content-Type", "application/json")
            builder.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        }
        fun entitlement(owner: String, starts: Instant, expires: Instant, premium: Boolean, revoked: Boolean = false) {
            transaction(fixtures) {
                SubscriptionEntitlements.deleteWhere { ownerId eq owner }
                SubscriptionEntitlements.insert {
                    it[ownerId] = owner; it[startsAt] = starts; it[expiresAt] = expires
                    it[isPremium] = premium; it[SubscriptionEntitlements.revoked] = revoked
                }
            }
        }
        fun legacyPremium(owner: String) {
            transaction(fixtures) { Users.update({ Users.id eq owner }) { it[isPremium] = true } }
        }
        fun execute(sql: String) {
            DriverManager.getConnection(config.db.url, config.db.user, config.db.password).use { connection ->
                connection.createStatement().use { it.execute(sql) }
            }
        }
        fun count(sql: String, check: (Int) -> Unit) {
            DriverManager.getConnection(config.db.url, config.db.user, config.db.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(sql).use { rows -> check(if (rows.next()) rows.getInt(1) else -1) }
                }
            }
        }
        override fun close() {
            engine.stop(100, 1_000)
            if (config.db.url.startsWith("jdbc:h2:file:")) execute("SHUTDOWN")
        }
    }

    private fun config(url: String = "jdbc:h2:mem:subscription_${UUID.randomUUID().toString().replace("-", "")};DB_CLOSE_DELAY=-1;MODE=PostgreSQL") =
        ServerConfig.fromEnv { key -> when (key) {
            "PF_ENV" -> "dev"; "PF_DB_URL" -> url; "PF_DB_USER" -> "sa"; "PF_DB_PASSWORD" -> ""
            "PF_JWT_SECRET" -> "subscription-test-only-secret-at-least-32-bytes"
            "PF_RL_GENERAL", "PF_RL_JOIN", "PF_RL_CHAT" -> "100000"
            else -> null
        } }
}
