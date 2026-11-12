package com.projectfocus.server

import com.google.common.truth.Truth.assertThat
import com.projectfocus.server.config.ServerConfig
import com.projectfocus.server.db.RegistrationChallenges
import com.projectfocus.server.db.Tables
import com.projectfocus.server.domain.EmailRegistrationService
import com.projectfocus.server.domain.RegistrationEmail
import com.projectfocus.server.domain.RegistrationEmailDelivery
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test

/** Real module/Netty/JDK HTTP/SQL; delivery is an owned per-test port, never real email. */
class EmailRegistrationRoutesTest {
    @Test fun verifiedRegistrationReturns201WithoutTokensAndRequiresAnExplicitPasswordLogin() {
        val messages = ConcurrentLinkedQueue<RegistrationEmail>()
        Server(RegistrationEmailDelivery { messages += it; true }).use { server ->
            val codeReply = server.post("/auth/register/code", json("email" to "  Captain@PF.test "))
            assertThat(codeReply.statusCode()).isEqualTo(200)
            val message = messages.single()
            assertThat(message.recipient).isEqualTo("captain@pf.test")
            assertThat(message.code).matches("[0-9]{6}")
            assertThat(message.validForSeconds).isEqualTo(600L)
            assertThat(codeReply.body()).doesNotContain(message.code)
            transaction(server.database) {
                val challenge = RegistrationChallenges.selectAll().single()
                assertThat(challenge[RegistrationChallenges.digest]).hasLength(64)
                assertThat(challenge[RegistrationChallenges.digest]).isNotEqualTo(message.code)
                assertThat(challenge[RegistrationChallenges.ready]).isTrue()
                assertThat(Tables.Users.selectAll().count()).isEqualTo(0L)
            }
            val password = "  password123  "
            val created = server.post("/auth/register", json("email" to " Captain@PF.test ", "code" to " ${message.code} ",
                "password" to password, "username" to "forged-name", "isPremium" to true))
            assertThat(created.statusCode()).isEqualTo(201)
            assertThat(created.body()).doesNotContain("token")
            assertThat(created.body()).doesNotContain("refresh")
            assertThat(server.get("/user/me").statusCode()).isEqualTo(401)
            transaction(server.database) {
                assertThat(Tables.Users.selectAll().count()).isEqualTo(1L)
                assertThat(Tables.RefreshTokens.selectAll().count()).isEqualTo(0L)
                assertThat(Tables.Users.selectAll().single()[Tables.Users.isPremium]).isFalse()
                assertThat(RegistrationChallenges.selectAll().single()[RegistrationChallenges.consumed]).isTrue()
            }
            assertThat(server.post("/auth/login", json("email" to message.recipient, "password" to password.trim())).statusCode()).isEqualTo(401)
            val login = server.post("/auth/login", json("email" to "CAPTAIN@pf.test", "password" to password))
            assertThat(login.statusCode()).isEqualTo(200)
            val token = Json.parseToJsonElement(login.body()).jsonObject["data"]!!.jsonObject["token"]!!.jsonPrimitive.content
            assertThat(server.get("/user/me", token).statusCode()).isEqualTo(200)
        }
    }

    @Test fun missingCodeAndUnavailableDeliveryNeverCreateAnAccountOrFakeDeliverySuccess() {
        val messages = ConcurrentLinkedQueue<RegistrationEmail>()
        Server(RegistrationEmailDelivery { messages += it; false }).use { server ->
            assertThat(server.post("/auth/register", credentials("missing@pf.test", "")).statusCode()).isEqualTo(400)
            assertThat(server.post("/auth/register/code", json("email" to "failed@pf.test")).statusCode()).isEqualTo(503)
            val failed = messages.single()
            assertThat(server.post("/auth/register", credentials(failed.recipient, failed.code)).statusCode()).isEqualTo(400)
            transaction(server.database) {
                assertThat(Tables.Users.selectAll().count()).isEqualTo(0L)
                assertThat(Tables.RefreshTokens.selectAll().count()).isEqualTo(0L)
                assertThat(RegistrationChallenges.selectAll().single()[RegistrationChallenges.ready]).isFalse()
            }
        }
    }

    @Test fun failedGuessesPersistAndLockTheChallengeInsteadOfRollingBackTheCounter() {
        val messages = ConcurrentLinkedQueue<RegistrationEmail>()
        Server(RegistrationEmailDelivery { messages += it; true }).use { server ->
            server.post("/auth/register/code", json("email" to "guesses@pf.test"))
            val message = messages.single()
            val wrong = if (message.code == "000000") "999999" else "000000"
            repeat(EmailRegistrationService.MAX_ATTEMPTS) {
                assertThat(server.post("/auth/register", credentials(message.recipient, wrong)).statusCode()).isEqualTo(400)
            }
            assertThat(server.post("/auth/register", credentials(message.recipient, message.code)).statusCode()).isEqualTo(400)
            transaction(server.database) {
                val row = RegistrationChallenges.selectAll().single()
                assertThat(row[RegistrationChallenges.attempts]).isEqualTo(5)
                assertThat(row[RegistrationChallenges.ready]).isFalse()
                assertThat(Tables.Users.selectAll().count()).isEqualTo(0L)
            }
        }
    }

    @Test fun expiryAndTheExactThirtySecondResendBoundaryUsePersistentServerTime() {
        val messages = ConcurrentLinkedQueue<RegistrationEmail>()
        val clock = MutableClock()
        Server(RegistrationEmailDelivery { messages += it; true }, clock).use { server ->
            val body = json("email" to "clock@pf.test")
            assertThat(server.post("/auth/register/code", body).statusCode()).isEqualTo(200)
            val firstGeneration = transaction(server.database) { RegistrationChallenges.selectAll().single()[RegistrationChallenges.generation] }
            clock.advance(29_999)
            assertThat(server.post("/auth/register/code", body).statusCode()).isEqualTo(429)
            assertThat(messages).hasSize(1)
            clock.advance(1)
            assertThat(server.post("/auth/register/code", body).statusCode()).isEqualTo(200)
            val second = messages.last()
            transaction(server.database) {
                assertThat(RegistrationChallenges.selectAll().single()[RegistrationChallenges.generation]).isNotEqualTo(firstGeneration)
            }
            clock.advance(EmailRegistrationService.VALID_MILLIS)
            assertThat(server.post("/auth/register", credentials(second.recipient, second.code)).statusCode()).isEqualTo(400)
            assertThat(server.post("/auth/login", json("email" to second.recipient, "password" to "password123")).statusCode()).isEqualTo(401)
        }
    }

    @Test fun shortPasswordDoesNotConsumeTheCodeAndTheCodeCannotBeMovedToAnotherEmail() {
        val messages = ConcurrentLinkedQueue<RegistrationEmail>()
        Server(RegistrationEmailDelivery { messages += it; true }).use { server ->
            server.post("/auth/register/code", json("email" to "owner@pf.test"))
            val message = messages.single()
            assertThat(server.post("/auth/register", json("email" to message.recipient, "code" to message.code, "password" to "short")).statusCode()).isEqualTo(400)
            assertThat(server.post("/auth/register", credentials("other@pf.test", message.code)).statusCode()).isEqualTo(400)
            assertThat(server.post("/auth/register", credentials(message.recipient, message.code)).statusCode()).isEqualTo(201)
            assertThat(server.post("/auth/register", credentials(message.recipient, message.code)).statusCode()).isEqualTo(409)
            transaction(server.database) { assertThat(Tables.Users.selectAll().count()).isEqualTo(1L) }
        }
    }

    @Test fun concurrentSendRequestsReserveOnlyOneDeliveryAcrossActualSqlTransactions() {
        val messages = ConcurrentLinkedQueue<RegistrationEmail>()
        Server(RegistrationEmailDelivery { messages += it; true }).use { server ->
            val pool = Executors.newFixedThreadPool(2)
            val start = CountDownLatch(1)
            try {
                val jobs = (1..2).map { pool.submit<Int> { start.await(); server.post("/auth/register/code", json("email" to "race@pf.test")).statusCode() } }
                start.countDown()
                assertThat(jobs.map { it.get(10, TimeUnit.SECONDS) }).containsExactly(200, 429)
                assertThat(messages).hasSize(1)
            } finally { pool.shutdownNow() }
        }
    }

    @Test fun aLateDeliveryCannotEnableItsSupersededGeneration() {
        val messages = ConcurrentLinkedQueue<RegistrationEmail>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val clock = MutableClock()
        Server(RegistrationEmailDelivery {
            messages += it
            if (messages.size == 1) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            true
        }, clock).use { server ->
            val pool = Executors.newSingleThreadExecutor()
            try {
                val first = pool.submit<Int> { server.post("/auth/register/code", json("email" to "late@pf.test")).statusCode() }
                check(entered.await(5, TimeUnit.SECONDS))
                clock.advance(30_000)
                val second = server.post("/auth/register/code", json("email" to "late@pf.test"))
                assertThat(second.statusCode()).isEqualTo(200)
                val newestGeneration = transaction(server.database) { RegistrationChallenges.selectAll().single()[RegistrationChallenges.generation] }
                release.countDown()
                assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(503)
                transaction(server.database) {
                    val row = RegistrationChallenges.selectAll().single()
                    assertThat(row[RegistrationChallenges.generation]).isEqualTo(newestGeneration)
                    assertThat(row[RegistrationChallenges.ready]).isTrue()
                }
                val newest = messages.last()
                assertThat(server.post("/auth/register", credentials(newest.recipient, newest.code)).statusCode()).isEqualTo(201)
            } finally { release.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun theSameValidChallengeCannotCreateTwoAccountsUnderConcurrentRegistration() {
        val messages = ConcurrentLinkedQueue<RegistrationEmail>()
        Server(RegistrationEmailDelivery { messages += it; true }).use { server ->
            server.post("/auth/register/code", json("email" to "once@pf.test"))
            val message = messages.single()
            val pool = Executors.newFixedThreadPool(2)
            val start = CountDownLatch(1)
            try {
                val jobs = (1..2).map { pool.submit<Int> { start.await(); server.post("/auth/register", credentials(message.recipient, message.code)).statusCode() } }
                start.countDown()
                val statuses = jobs.map { it.get(10, TimeUnit.SECONDS) }
                assertThat(statuses.count { it == 201 }).isEqualTo(1)
                assertThat(statuses.count { it in setOf(400, 409) }).isEqualTo(1)
                transaction(server.database) {
                    assertThat(Tables.Users.selectAll().count()).isEqualTo(1L)
                    assertThat(Tables.RefreshTokens.selectAll().count()).isEqualTo(0L)
                }
            } finally { pool.shutdownNow() }
        }
    }

    private fun credentials(email: String, code: String) = json("email" to email, "code" to code, "password" to "password123")

    private class MutableClock : Clock() {
        private val current = AtomicLong(Instant.parse("2026-10-31T12:00:00Z").toEpochMilli())
        fun advance(millis: Long) { current.addAndGet(millis) }
        override fun millis(): Long = current.get()
        override fun instant(): Instant = Instant.ofEpochMilli(millis())
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }

    private class Server(delivery: RegistrationEmailDelivery, clock: Clock = MutableClock()) : AutoCloseable {
        private val port = ServerSocket(0).use { it.localPort }
        private val config = ServerConfig.fromEnv { key -> when (key) {
            "PF_ENV" -> "dev"
            "PF_DB_URL" -> "jdbc:h2:mem:register_${UUID.randomUUID().toString().replace("-", "")};DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
            "PF_DB_USER" -> "sa"; "PF_DB_PASSWORD" -> ""
            "PF_JWT_SECRET" -> "registration-test-only-secret-at-least-32-bytes"
            "PF_RL_GENERAL", "PF_RL_JOIN", "PF_RL_CHAT" -> "100000"
            else -> null
        } }
        private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        private val engine = embeddedServer(Netty, port = port, host = "127.0.0.1") {
            module(config, registrationEmailDelivery = delivery, registrationClock = clock)
        }.start(wait = false)
        val database: Database = Database.connect(config.db.url, driver = "org.h2.Driver", user = "sa", password = "")
        fun post(path: String, body: String): HttpResponse<String> = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
        fun get(path: String, token: String? = null): HttpResponse<String> = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .timeout(Duration.ofSeconds(5)).apply { token?.let { header("Authorization", "Bearer $it") } }
            .GET().build(), HttpResponse.BodyHandlers.ofString())
        override fun close() { engine.stop(0, 5_000) }
    }
}
