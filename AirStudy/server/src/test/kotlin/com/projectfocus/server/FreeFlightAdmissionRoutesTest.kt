package com.projectfocus.server

import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.network.dto.*
import com.projectfocus.server.config.ServerConfig
import com.projectfocus.server.db.SubscriptionEntitlements
import com.projectfocus.server.db.Tables.Users
import com.projectfocus.server.domain.FreeFlightDevicePolicy
import com.projectfocus.server.security.JwtService
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
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test

/** Candidate only: actual own Netty/JWT/SQL over loopback; no registration/email/original service. */
class FreeFlightAdmissionRoutesTest {
    @Test fun guestRegistrationPrepareAndFormalReplayUseActualOwnRoutes() {
        Server(FreeFlightDevicePolicy(1, "fixture-one")).use { server ->
            val prepared = server.post("prepare", json.encodeToString(FreeFlightDeviceRequest.serializer(), device()))
            assertThat(prepared.statusCode()).isEqualTo(200)
            assertThat(prepared.headers().firstValue("Cache-Control").orElse("")).contains("no-store")
            assertThat(data(prepared)["allowed"]!!.jsonPrimitive.booleanOrNull).isTrue()
            val request = FreeFlightFormalRequest(hwid, credential, UUID.randomUUID().toString(), "session-" + UUID.randomUUID(), "c".repeat(64))
            val body = json.encodeToString(FreeFlightFormalRequest.serializer(), request)
            val first = data(server.post("formal", body))
            assertThat(first["approved"]!!.jsonPrimitive.booleanOrNull).isTrue()
            assertThat(first["remainingUses"]!!.jsonPrimitive.longOrNull).isEqualTo(0)
            assertThat(data(server.post("formal", body))).isEqualTo(first)
            assertThat(data(server.post("prepare", json.encodeToString(FreeFlightDeviceRequest.serializer(), device())))["allowed"]!!.jsonPrimitive.booleanOrNull).isFalse()
            val query = FreeFlightOperationRequest(hwid, credential, request.operationId)
            assertThat(data(server.post("operation", json.encodeToString(FreeFlightOperationRequest.serializer(), query)))["found"]!!.jsonPrimitive.booleanOrNull).isTrue()
            val failure = FreeFlightLocalFailureRequest(hwid, credential, request.operationId, request.sessionId, request.requestFingerprint)
            val receipt = data(server.post("local-start-failed", json.encodeToString(FreeFlightLocalFailureRequest.serializer(), failure)))
            assertThat(receipt["refunded"]!!.jsonPrimitive.booleanOrNull).isFalse()
            assertThat(data(server.post("local-start-failed", json.encodeToString(FreeFlightLocalFailureRequest.serializer(), failure)))).isEqualTo(receipt)
        }
    }

    @Test fun validFreshMembershipHasPriorityWhileInvalidJwtOnlyUsesConfiguredDevicePolicy() {
        Server(FreeFlightDevicePolicy()).use { server ->
            val token = server.member()
            val body = json.encodeToString(FreeFlightDeviceRequest.serializer(), device())
            assertThat(data(server.post("prepare", body, token))["source"]!!.jsonPrimitive.content).isEqualTo("MEMBER")
            val rejected = data(server.post("prepare", body, "invalid-fixture-token"))
            assertThat(rejected["allowed"]!!.jsonPrimitive.booleanOrNull).isFalse()
            assertThat(rejected["reason"]!!.jsonPrimitive.content).isEqualTo("POLICY_UNCONFIGURED")
            // Caller body cannot grant membership or quota; no public grant route exists.
            assertThat(server.post("grant", "{}", token).statusCode()).isAtLeast(400)
        }
    }

    @Test fun realHttpMemberCanPrepareAuthorizeQueryAndReconcileWithNoDeviceFields() {
        Server(FreeFlightDevicePolicy()).use { server ->
            val token = server.member()
            assertThat(data(server.post("prepare", "{}", token))["source"]!!.jsonPrimitive.content).isEqualTo("MEMBER")
            val request = FreeFlightFormalRequest(operationId = UUID.randomUUID().toString(), sessionId = "member-no-device", requestFingerprint = "c".repeat(64))
            val decision = data(server.post("formal", json.encodeToString(FreeFlightFormalRequest.serializer(), request), token))
            assertThat(decision["approved"]!!.jsonPrimitive.booleanOrNull).isTrue()
            assertThat(decision["source"]!!.jsonPrimitive.content).isEqualTo("MEMBER")
            val query = FreeFlightOperationRequest(operationId = request.operationId)
            assertThat(data(server.post("operation", json.encodeToString(FreeFlightOperationRequest.serializer(), query), token))["found"]!!.jsonPrimitive.booleanOrNull).isTrue()
            val failure = FreeFlightLocalFailureRequest(operationId = request.operationId, sessionId = request.sessionId, requestFingerprint = request.requestFingerprint)
            val recorded = data(server.post("local-start-failed", json.encodeToString(FreeFlightLocalFailureRequest.serializer(), failure), token))
            assertThat(recorded["refunded"]!!.jsonPrimitive.booleanOrNull).isFalse()
            assertThat(data(server.post("formal", json.encodeToString(FreeFlightFormalRequest.serializer(), request), token))).isEqualTo(decision)
        }
    }

    private class Server(policy: FreeFlightDevicePolicy) : AutoCloseable {
        val config = ServerConfig.fromEnv { key -> when (key) {
            "PF_ENV" -> "dev"; "PF_DB_URL" -> "jdbc:h2:mem:ff_route_${UUID.randomUUID()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
            "PF_JWT_SECRET" -> "freeflight-test-secret-only-at-least-32-bytes"
            "PF_RL_GENERAL", "PF_RL_JOIN", "PF_RL_CHAT" -> "100000"
            else -> null
        } }
        private val port = ServerSocket(0).use { it.localPort }
        private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        private val engine = embeddedServer(Netty, host = "127.0.0.1", port = port) {
            module(config, freeFlightDevicePolicy = policy, freeFlightAdmissionClock = Clock.fixed(start, ZoneOffset.UTC))
        }.start(wait = false)
        private val db = Database.connect(config.db.url, driver = "org.h2.Driver", user = config.db.user, password = config.db.password)
        fun post(path: String, body: String, token: String? = null): HttpResponse<String> {
            val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/freeflight/admission/$path"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
            token?.let { builder.header("Authorization", "Bearer $it") }
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        }
        fun member(): String {
            val owner = UUID.randomUUID().toString()
            transaction(db) {
                Users.insert { it[id] = owner; it[email] = "$owner@fixture.invalid"; it[passwordHash] = "fixture-only"; it[username] = "Fixture" }
                SubscriptionEntitlements.insert { it[ownerId] = owner; it[startsAt] = start.minusSeconds(1); it[expiresAt] = start.plusSeconds(60); it[isPremium] = true; it[revoked] = false }
            }
            return JwtService(config.jwt).issueAccessToken(owner, "$owner@fixture.invalid")
        }
        override fun close() { engine.stop(100, 1_000) }
    }
    private fun data(reply: HttpResponse<String>) = Json.parseToJsonElement(reply.body()).jsonObject.let { root ->
        assertThat(reply.statusCode()).isEqualTo(200); assertThat(root["success"]!!.jsonPrimitive.booleanOrNull).isTrue(); root["data"]!!.jsonObject
    }
    companion object {
        private val json = Json { encodeDefaults = true }
        private val start = Instant.parse("2026-11-01T00:00:00Z")
        private val hwid = "a".repeat(64)
        private val credential = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 })
        private fun device() = FreeFlightDeviceRequest(hwid, credential)
    }
}
