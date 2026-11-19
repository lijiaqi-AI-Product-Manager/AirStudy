package com.projectfocus.server

import at.favre.lib.crypto.bcrypt.BCrypt
import com.google.common.truth.Truth.assertThat
import com.projectfocus.server.config.ServerConfig
import com.projectfocus.server.db.PasswordResetChallenges
import com.projectfocus.server.db.RegistrationChallenges
import com.projectfocus.server.db.Tables
import com.projectfocus.server.domain.AuthService
import com.projectfocus.server.domain.EmailChallengePurpose
import com.projectfocus.server.domain.HttpRegistrationEmailDelivery
import com.projectfocus.server.domain.PasswordResetService
import com.projectfocus.server.domain.RegistrationEmail
import com.projectfocus.server.domain.RegistrationEmailDelivery
import com.projectfocus.server.security.JwtService
import com.projectfocus.server.security.TokenMath
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Real module/Netty/JDK HTTP/SQL, with an owned delivery port; no real email. */
class PasswordResetRoutesTest {
    @TempDir lateinit var temporary: Path

    @Test fun updatesOnlyTheExistingAccountAndRevokesRefreshButNotAlreadyIssuedJwt() {
        val mail = ConcurrentLinkedQueue<RegistrationEmail>()
        Server(RegistrationEmailDelivery { mail += it; true }).use { server ->
            val owner = server.seed("captain@pf.test")
            val other = server.seed("other@pf.test")
            val token = token(server.post("/auth/login", json("email" to "captain@pf.test", "password" to OLD)))
            val oldRefresh = server.refreshFixture(owner)
            val otherRefresh = server.refreshFixture(other)
            val before = transaction(server.database) {
                Tables.Users.selectAll().where { Tables.Users.id eq owner }.single().let {
                    listOf(it[Tables.Users.id], it[Tables.Users.username], it[Tables.Users.isPremium], it[Tables.Users.xp])
                }
            }
            assertThat(server.post("/auth/password-reset/code", json("email" to "  CAPTAIN@pf.test ")).statusCode()).isEqualTo(200)
            val message = mail.single()
            assertThat(message.recipient).isEqualTo("captain@pf.test")
            assertThat(message.purpose).isEqualTo(EmailChallengePurpose.PASSWORD_RESET)
            assertThat(message.validForSeconds).isEqualTo(600L)
            assertThat(message.code).matches("[0-9]{6}")
            val password = "  updated-password123  "
            val result = server.post("/auth/password-reset", reset(" CAPTAIN@pf.test ", " ${message.code} ", password))
            assertThat(result.statusCode()).isEqualTo(200)
            assertThat(result.body()).doesNotContain("token")
            assertThat(result.body()).doesNotContain("refresh")
            assertThat(result.body()).doesNotContain(message.code)
            transaction(server.database) {
                assertThat(Tables.Users.selectAll().count()).isEqualTo(2L)
                val row = Tables.Users.selectAll().where { Tables.Users.id eq owner }.single()
                assertThat(listOf(row[Tables.Users.id], row[Tables.Users.username], row[Tables.Users.isPremium], row[Tables.Users.xp])).isEqualTo(before)
                assertThat(row[Tables.Users.passwordHash]).startsWith("\$2")
                assertThat(BCrypt.verifyer().verify(password.toCharArray(), row[Tables.Users.passwordHash].toCharArray()).verified).isTrue()
                val challenge = PasswordResetChallenges.selectAll().single()
                assertThat(challenge[PasswordResetChallenges.digest]).hasLength(64)
                assertThat(challenge[PasswordResetChallenges.digest]).isNotEqualTo(message.code)
                assertThat(challenge[PasswordResetChallenges.consumed]).isTrue()
                assertThat(challenge[PasswordResetChallenges.ready]).isFalse()
                assertThat(RegistrationChallenges.selectAll().count()).isEqualTo(0L)
                assertThat(Tables.RefreshTokens.selectAll().where { Tables.RefreshTokens.userId eq owner }.count()).isEqualTo(0L)
            }
            assertThat(server.post("/auth/login", json("email" to message.recipient, "password" to OLD)).statusCode()).isEqualTo(401)
            assertThat(server.post("/auth/login", json("email" to message.recipient, "password" to password.trim())).statusCode()).isEqualTo(401)
            assertThat(server.post("/auth/login", json("email" to message.recipient, "password" to password)).statusCode()).isEqualTo(200)
            assertThat(server.post("/auth/refresh", json("refreshToken" to oldRefresh)).statusCode()).isEqualTo(401)
            assertThat(server.post("/auth/refresh", json("refreshToken" to otherRefresh)).statusCode()).isEqualTo(200)
            assertThat(server.get("/user/me", token).statusCode()).isEqualTo(200) // Own access-JWT expiry policy, not full revocation.
            assertThat(server.post("/auth/password-reset", reset(message.recipient, message.code, "another-password123")).statusCode()).isEqualTo(400)
        }
    }

    @Test fun purposeAndAccountIsolationPreserveRegister201AndNeverCreateAnAccountByReset() {
        val mail = ConcurrentLinkedQueue<RegistrationEmail>()
        Server(RegistrationEmailDelivery { mail += it; true }).use { server ->
            server.seed("owner@pf.test"); server.seed("other@pf.test")
            assertThat(server.post("/auth/register/code", json("email" to "owner@pf.test")).statusCode()).isEqualTo(200)
            val registerCode = mail.single()
            assertThat(registerCode.purpose).isEqualTo(EmailChallengePurpose.REGISTER)
            assertThat(server.post("/auth/password-reset", reset(registerCode.recipient, registerCode.code)).statusCode()).isEqualTo(400)
            transaction(server.database) {
                assertThat(PasswordResetChallenges.selectAll().count()).isEqualTo(0L)
                assertThat(RegistrationChallenges.selectAll().single()[RegistrationChallenges.consumed]).isFalse()
            }
            assertThat(server.post("/auth/password-reset/code", json("email" to "owner@pf.test")).statusCode()).isEqualTo(200)
            val resetCode = mail.last()
            assertThat(server.post("/auth/password-reset", reset("other@pf.test", resetCode.code)).statusCode()).isEqualTo(400)
            assertThat(server.post("/auth/login", json("email" to "other@pf.test", "password" to OLD)).statusCode()).isEqualTo(200)
            assertThat(server.post("/auth/login", json("email" to "other@pf.test", "password" to NEW)).statusCode()).isEqualTo(401)
            assertThat(server.post("/auth/password-reset", reset("missing@pf.test", resetCode.code)).statusCode()).isEqualTo(404)
            assertThat(server.post("/auth/password-reset/code", json("email" to "missing@pf.test")).statusCode()).isEqualTo(404)
            assertThat(mail).hasSize(2)
            assertThat(server.post("/auth/password-reset", reset(resetCode.recipient, resetCode.code)).statusCode()).isEqualTo(200)
            assertThat(server.post("/auth/register/code", json("email" to "new@pf.test")).statusCode()).isEqualTo(200)
            val fresh = mail.last()
            val registered = server.post("/auth/register", json("email" to fresh.recipient, "code" to fresh.code, "password" to OLD))
            assertThat(registered.statusCode()).isEqualTo(201)
            assertThat(registered.body()).doesNotContain("token")
            transaction(server.database) {
                assertThat(Tables.Users.selectAll().count()).isEqualTo(3L)
                assertThat(RegistrationChallenges.selectAll().where { RegistrationChallenges.email eq "owner@pf.test" }.single()[RegistrationChallenges.consumed]).isFalse()
            }
        }
    }

    @Test fun fiveFailedGuessesCommitTheirCounterAndDoNotChangeThePassword() {
        val mail = ConcurrentLinkedQueue<RegistrationEmail>()
        Server(RegistrationEmailDelivery { mail += it; true }).use { server ->
            server.seed("guesses@pf.test")
            server.post("/auth/password-reset/code", json("email" to "guesses@pf.test"))
            val message = mail.single()
            val wrong = if (message.code == "000000") "999999" else "000000"
            repeat(PasswordResetService.MAX_ATTEMPTS) {
                assertThat(server.post("/auth/password-reset", reset(message.recipient, wrong)).statusCode()).isEqualTo(400)
            }
            assertThat(server.post("/auth/password-reset", reset(message.recipient, message.code)).statusCode()).isEqualTo(400)
            transaction(server.database) {
                val row = PasswordResetChallenges.selectAll().single()
                assertThat(row[PasswordResetChallenges.attempts]).isEqualTo(5)
                assertThat(row[PasswordResetChallenges.ready]).isFalse()
                assertThat(row[PasswordResetChallenges.consumed]).isFalse()
            }
            assertThat(server.post("/auth/login", json("email" to message.recipient, "password" to OLD)).statusCode()).isEqualTo(200)
        }
    }

    @Test fun resendAndExpiryUseExactPersistentClockBoundaries() {
        val mail = ConcurrentLinkedQueue<RegistrationEmail>()
        val clock = MutableClock()
        Server(RegistrationEmailDelivery { mail += it; true }, clock).use { server ->
            server.seed("clock@pf.test")
            val body = json("email" to "clock@pf.test")
            assertThat(server.post("/auth/password-reset/code", body).statusCode()).isEqualTo(200)
            val first = transaction(server.database) { PasswordResetChallenges.selectAll().single()[PasswordResetChallenges.generation] }
            clock.advance(29_999)
            assertThat(server.post("/auth/password-reset/code", body).statusCode()).isEqualTo(429)
            assertThat(mail).hasSize(1)
            clock.advance(1)
            assertThat(server.post("/auth/password-reset/code", body).statusCode()).isEqualTo(200)
            val second = mail.last()
            transaction(server.database) {
                assertThat(PasswordResetChallenges.selectAll().single()[PasswordResetChallenges.generation]).isNotEqualTo(first)
            }
            clock.advance(PasswordResetService.VALID_MILLIS)
            assertThat(server.post("/auth/password-reset", reset(second.recipient, second.code)).statusCode()).isEqualTo(400)
            assertThat(server.post("/auth/login", json("email" to second.recipient, "password" to OLD)).statusCode()).isEqualTo(200)
        }
    }

    @Test fun malformedRequestsAndPasswordRejectionCannotConsumeTheValidChallenge() {
        val mail = ConcurrentLinkedQueue<RegistrationEmail>()
        Server(RegistrationEmailDelivery { mail += it; true }).use { server ->
            server.seed("input@pf.test")
            assertThat(server.post("/auth/password-reset/code", "{\"email\":42}").statusCode()).isEqualTo(400)
            assertThat(server.post("/auth/password-reset/code", "not-json").statusCode()).isEqualTo(400)
            // The same real ContentNegotiation/StatusPages path also serves
            // existing auth routes. A malformed request is never a server fault.
            assertThat(server.post("/auth/login", "{\"email\":42,\"password\":\"original-password123\"}").statusCode()).isEqualTo(400)
            assertThat(server.post("/auth/register", "not-json").statusCode()).isEqualTo(400)
            server.post("/auth/password-reset/code", json("email" to "input@pf.test"))
            val message = mail.single()
            for (password in listOf("short", "😀".repeat(20))) {
                assertThat(server.post("/auth/password-reset", reset(message.recipient, message.code, password)).statusCode()).isEqualTo(400)
            }
            assertThat(server.post("/auth/password-reset", "{\"email\":\"input@pf.test\",\"code\":7,\"new_password\":\"updated-password123\"}").statusCode()).isEqualTo(400)
            assertThat(server.post("/auth/password-reset", json("email" to message.recipient, "code" to message.code, "password" to NEW)).statusCode()).isEqualTo(400)
            transaction(server.database) {
                val row = PasswordResetChallenges.selectAll().single()
                assertThat(row[PasswordResetChallenges.ready]).isTrue()
                assertThat(row[PasswordResetChallenges.consumed]).isFalse()
                assertThat(row[PasswordResetChallenges.attempts]).isEqualTo(0)
            }
            assertThat(server.post("/auth/password-reset", reset(message.recipient, message.code)).statusCode()).isEqualTo(200)
        }
    }

    @Test fun absentRelayIs503AndCannotEnableOrConsumeAChallenge() {
        val unavailable = HttpRegistrationEmailDelivery.fromEnv { null }
        Server(unavailable).use { server ->
            server.seed("unavailable@pf.test")
            assertThat(server.post("/auth/password-reset/code", json("email" to "unavailable@pf.test")).statusCode()).isEqualTo(503)
            assertThat(server.post("/auth/password-reset", reset("unavailable@pf.test", "123456")).statusCode()).isEqualTo(400)
            transaction(server.database) {
                assertThat(PasswordResetChallenges.selectAll().single()[PasswordResetChallenges.ready]).isFalse()
                assertThat(PasswordResetChallenges.selectAll().single()[PasswordResetChallenges.consumed]).isFalse()
            }
            assertThat(server.post("/auth/login", json("email" to "unavailable@pf.test", "password" to OLD)).statusCode()).isEqualTo(200)
        }
    }

    @Test fun concurrentSendReservesOnlyOneRelayRequest() {
        val mail = ConcurrentLinkedQueue<RegistrationEmail>()
        Server(RegistrationEmailDelivery { mail += it; true }).use { server ->
            server.seed("send-race@pf.test")
            val pool = Executors.newFixedThreadPool(2)
            val start = CountDownLatch(1)
            try {
                val jobs = (1..2).map { pool.submit<Int> {
                    check(start.await(5, TimeUnit.SECONDS))
                    server.post("/auth/password-reset/code", json("email" to "send-race@pf.test")).statusCode()
                } }
                start.countDown()
                assertThat(jobs.map { it.get(15, TimeUnit.SECONDS) }).containsExactly(200, 429)
                assertThat(mail).hasSize(1)
            } finally { start.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun lateRelayAcknowledgementCannotEnableItsSupersededGeneration() {
        val mail = ConcurrentLinkedQueue<RegistrationEmail>()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val clock = MutableClock()
        Server(RegistrationEmailDelivery {
            mail += it
            if (mail.size == 1) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            true
        }, clock).use { server ->
            server.seed("late@pf.test")
            val pool = Executors.newSingleThreadExecutor()
            try {
                val first = pool.submit<Int> { server.post("/auth/password-reset/code", json("email" to "late@pf.test")).statusCode() }
                check(entered.await(5, TimeUnit.SECONDS))
                clock.advance(30_000)
                assertThat(server.post("/auth/password-reset/code", json("email" to "late@pf.test")).statusCode()).isEqualTo(200)
                val generation = transaction(server.database) { PasswordResetChallenges.selectAll().single()[PasswordResetChallenges.generation] }
                release.countDown()
                assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(503)
                transaction(server.database) {
                    val row = PasswordResetChallenges.selectAll().single()
                    assertThat(row[PasswordResetChallenges.generation]).isEqualTo(generation)
                    assertThat(row[PasswordResetChallenges.ready]).isTrue()
                }
                val newest = mail.last()
                assertThat(server.post("/auth/password-reset", reset(newest.recipient, newest.code)).statusCode()).isEqualTo(200)
            } finally { release.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun oneValidCodeHasExactlyOneWinnerUnderConcurrentSqlConsumption() {
        val mail = ConcurrentLinkedQueue<RegistrationEmail>()
        Server(RegistrationEmailDelivery { mail += it; true }).use { server ->
            val owner = server.seed("consume@pf.test")
            server.post("/auth/password-reset/code", json("email" to "consume@pf.test"))
            val message = mail.single()
            val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
            try {
                val jobs = listOf("winner-one123", "winner-two123").map { password -> pool.submit<Pair<String, Int>> {
                    check(start.await(5, TimeUnit.SECONDS))
                    password to server.post("/auth/password-reset", reset(message.recipient, message.code, password)).statusCode()
                } }
                start.countDown()
                val replies = jobs.map { it.get(15, TimeUnit.SECONDS) }
                assertThat(replies.map { it.second }).containsExactly(200, 400)
                val password = replies.single { it.second == 200 }.first
                transaction(server.database) {
                    val hash = Tables.Users.selectAll().where { Tables.Users.id eq owner }.single()[Tables.Users.passwordHash]
                    assertThat(BCrypt.verifyer().verify(password.toCharArray(), hash.toCharArray()).verified).isTrue()
                    assertThat(PasswordResetChallenges.selectAll().single()[PasswordResetChallenges.consumed]).isTrue()
                }
            } finally { start.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun sqlFailureRollsBackPasswordAndConsumptionThenAllowsTheSameCodeToRetry() {
        val mail = ConcurrentLinkedQueue<RegistrationEmail>()
        Server(RegistrationEmailDelivery { mail += it; true }).use { server ->
            val owner = server.seed("rollback@pf.test")
            val refresh = server.refreshFixture(owner)
            transaction(server.database) {
                exec("CREATE TABLE reset_failure_guard (token_hash VARCHAR(64) PRIMARY KEY REFERENCES refresh_tokens(token_hash))")
                exec("INSERT INTO reset_failure_guard(token_hash) VALUES ('${TokenMath.hash(refresh)}')")
            }
            server.post("/auth/password-reset/code", json("email" to "rollback@pf.test"))
            val message = mail.single()
            // Existing Monitoring policy maps actual SQL failures to503, not500.
            assertThat(server.post("/auth/password-reset", reset(message.recipient, message.code)).statusCode()).isEqualTo(503)
            transaction(server.database) {
                val row = PasswordResetChallenges.selectAll().single()
                assertThat(row[PasswordResetChallenges.consumed]).isFalse()
                assertThat(row[PasswordResetChallenges.ready]).isTrue()
                assertThat(Tables.RefreshTokens.selectAll().where { Tables.RefreshTokens.userId eq owner }.count()).isEqualTo(1L)
                val hash = Tables.Users.selectAll().where { Tables.Users.id eq owner }.single()[Tables.Users.passwordHash]
                assertThat(BCrypt.verifyer().verify(OLD.toCharArray(), hash.toCharArray()).verified).isTrue()
                exec("DROP TABLE reset_failure_guard")
            }
            assertThat(server.post("/auth/password-reset", reset(message.recipient, message.code)).statusCode()).isEqualTo(200)
        }
    }

    @Test fun challengeAndBcryptUpdateSurviveActualDatabaseCloseAndDiskReopen() {
        val mail = ConcurrentLinkedQueue<RegistrationEmail>()
        val databaseUrl = "jdbc:h2:file:${temporary.resolve("password-reset").toAbsolutePath().toString().replace('\\', '/')};MODE=PostgreSQL;DB_CLOSE_ON_EXIT=FALSE"
        val clock = MutableClock()
        val delivery = RegistrationEmailDelivery { mail += it; true }
        val owner = Server(delivery, clock, databaseUrl).use { server ->
            val id = server.seed("disk@pf.test")
            assertThat(server.post("/auth/password-reset/code", json("email" to "disk@pf.test")).statusCode()).isEqualTo(200)
            id
        }
        val message = mail.single()
        Server(delivery, clock, databaseUrl).use { server ->
            assertThat(server.post("/auth/password-reset", reset(message.recipient, message.code)).statusCode()).isEqualTo(200)
            transaction(server.database) {
                assertThat(Tables.Users.selectAll().single()[Tables.Users.id]).isEqualTo(owner)
                assertThat(PasswordResetChallenges.selectAll().single()[PasswordResetChallenges.consumed]).isTrue()
            }
        }
        Server(delivery, clock, databaseUrl).use { server ->
            assertThat(server.post("/auth/password-reset", reset(message.recipient, message.code)).statusCode()).isEqualTo(400)
            assertThat(server.post("/auth/login", json("email" to message.recipient, "password" to NEW)).statusCode()).isEqualTo(200)
        }
    }

    private fun reset(email: String, code: String, password: String = NEW) = json("email" to email, "code" to code, "new_password" to password)
    private fun json(vararg pairs: Pair<String, String>) = buildJsonObject { pairs.forEach { (key, value) -> put(key, JsonPrimitive(value)) } }.toString()
    private fun token(response: HttpResponse<String>): String {
        assertThat(response.statusCode()).isEqualTo(200)
        return Json.parseToJsonElement(response.body()).jsonObject["data"]!!.jsonObject["token"]!!.jsonPrimitive.content
    }

    private class MutableClock : Clock() {
        private val time = AtomicLong(Instant.parse("2026-10-31T12:00:00Z").toEpochMilli())
        fun advance(millis: Long) { time.addAndGet(millis) }
        override fun millis() = time.get()
        override fun instant(): Instant = Instant.ofEpochMilli(millis())
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }

    private class Server(delivery: RegistrationEmailDelivery, clock: Clock = MutableClock(), databaseUrl: String? = null) : AutoCloseable {
        private val port = ServerSocket(0).use { it.localPort }
        private val config = ServerConfig.fromEnv { key -> when (key) {
            "PF_ENV" -> "dev"
            "PF_DB_URL" -> databaseUrl ?: "jdbc:h2:mem:reset_${UUID.randomUUID().toString().replace("-", "")};DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
            "PF_DB_USER" -> "sa"; "PF_DB_PASSWORD" -> ""
            "PF_JWT_SECRET" -> "password-reset-fixture-secret-at-least-32-bytes"
            "PF_RL_GENERAL", "PF_RL_JOIN", "PF_RL_CHAT" -> "100000"
            else -> null
        } }
        private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        private val engine = embeddedServer(Netty, port = port, host = "127.0.0.1") {
            module(config, registrationEmailDelivery = delivery, registrationClock = clock)
        }.start(wait = false)
        val database = Database.connect(config.db.url, driver = "org.h2.Driver", user = "sa", password = "")
        fun seed(email: String): String {
            AuthService(JwtService(config.jwt), database).createRegisteredAccount(email, OLD) // SQL fixture; no new public bypass.
            return transaction(database) { Tables.Users.selectAll().where { Tables.Users.email eq email }.single()[Tables.Users.id] }
        }
        fun refreshFixture(owner: String): String {
            val raw = TokenMath.generate()
            transaction(database) {
                Tables.RefreshTokens.insert {
                    it[tokenHash] = TokenMath.hash(raw); it[userId] = owner
                    it[issuedAt] = System.currentTimeMillis(); it[expiresAt] = TokenMath.expiresAt(System.currentTimeMillis()); it[userAgent] = "reset-fixture"
                }
            }
            return raw
        }
        fun post(path: String, body: String): HttpResponse<String> = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
        fun get(path: String, token: String): HttpResponse<String> = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer $token").GET().build(), HttpResponse.BodyHandlers.ofString())
        override fun close() { engine.stop(0, 5_000) }
    }

    companion object {
        private const val OLD = "original-password123"
        private const val NEW = "updated-password123"
    }
}
