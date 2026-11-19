package com.projectfocus.server

import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.network.dto.*
import com.projectfocus.server.config.DbConfig
import com.projectfocus.server.db.DatabaseFactory
import com.projectfocus.server.db.FreeFlightAdmissionOperations as Operations
import com.projectfocus.server.db.FreeFlightDeviceGrants as Grants
import com.projectfocus.server.db.FreeFlightDevices as Devices
import com.projectfocus.server.db.SubscriptionEntitlements
import com.projectfocus.server.db.Tables.Users
import com.projectfocus.server.domain.FreeFlightAdmissionService
import com.projectfocus.server.domain.FreeFlightDevicePolicy
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Candidate only: real H2 transactions/disk/parallel callers; not executed in the worker scope. */
class FreeFlightAdmissionServiceTest {
    @Test fun missingPolicyIsUnavailableAndAnExplicitZeroGrantIsExhausted() {
        Fixture().use { f ->
            assertThat(f.service.prepare(null, device()).reason).isEqualTo(FreeFlightAdmissionReason.POLICY_UNCONFIGURED)
            assertThat(f.deviceCount()).isEqualTo(0)
            val decision = f.service.formal(null, formal())
            assertThat(decision.approved).isFalse()
            assertThat(decision.reason).isEqualTo(FreeFlightAdmissionReason.POLICY_UNCONFIGURED)
        }
        Fixture(FreeFlightDevicePolicy(0, "explicit-zero")).use { f ->
            assertThat(f.service.prepare(null, device()).reason).isEqualTo(FreeFlightAdmissionReason.EXHAUSTED)
            assertThat(f.balance()).isEqualTo(0)
            assertThat(f.grantCount()).isEqualTo(1)
        }
    }

    @Test fun registrationAndRepeatedPreparationDoNotConsumeOrRegrant() {
        Fixture(FreeFlightDevicePolicy(3, "fixture-three")).use { f ->
            repeat(5) {
                val prepared = f.service.prepare(null, device())
                assertThat(prepared.allowed).isTrue()
                assertThat(prepared.source).isEqualTo(FreeFlightGrantSource.DEVICE)
                assertThat(prepared.remainingUses).isEqualTo(3)
            }
            assertThat(f.balance()).isEqualTo(3)
            assertThat(f.grantCount()).isEqualTo(1)
            assertThat(f.operationCount()).isEqualTo(0)
        }
    }

    @Test fun eachPhaseChecksFreshMembershipAndTrueMemberBypassesDeviceRegistration() {
        Fixture().use { f ->
            val user = f.user()
            f.entitlement(user, premium = true)
            assertThat(f.service.prepare(user, device()).source).isEqualTo(FreeFlightGrantSource.MEMBER)
            assertThat(f.deviceCount()).isEqualTo(0)
            assertThat(f.service.formal(user, formal()).source).isEqualTo(FreeFlightGrantSource.MEMBER)
            assertThat(f.deviceCount()).isEqualTo(0)
            f.clock.set(start.plusSeconds(60)) // Exclusive expiry: prepare's true is not a formal authorization cache.
            val next = f.service.formal(user, formal())
            assertThat(next.approved).isFalse()
            assertThat(next.reason).isEqualTo(FreeFlightAdmissionReason.POLICY_UNCONFIGURED)
            f.entitlement(user, premium = false)
            assertThat(f.service.prepare(user, device()).allowed).isFalse()
        }
    }

    @Test fun lastUseApprovesAtZeroAndReplaysTheOriginalDecisionAfterClockOrPolicyChanges() {
        Fixture(FreeFlightDevicePolicy(1, "fixture-one")).use { f ->
            f.service.prepare(null, device())
            val request = formal()
            val accepted = f.service.formal(null, request)
            assertThat(accepted.approved).isTrue()
            assertThat(accepted.remainingUses).isEqualTo(0)
            f.clock.set(start.plusSeconds(500))
            transaction(f.db) { Devices.update({ Devices.hwid eq hwid }) { it[active] = false } }
            assertThat(f.service.formal(null, request)).isEqualTo(accepted)
            assertThat(f.service.operation(null, operation(request)).decision).isEqualTo(accepted)
            assertThat(f.service.formal(null, formal()).reason).isEqualTo(FreeFlightAdmissionReason.DEVICE_DISABLED)
        }
    }

    @Test fun concurrentSameOperationAndDistinctSessionsNeverSpendMoreThanTheGrant() {
        Fixture(FreeFlightDevicePolicy(1, "fixture-one")).use { f ->
            f.service.prepare(null, device())
            val request = formal()
            val same = parallel(8) { f.service.formal(null, request) }
            assertThat(same.distinct()).hasSize(1)
            assertThat(same.singleOrNull()?.approved ?: same.first().approved).isTrue()
            assertThat(f.balance()).isEqualTo(0)
            assertThat(f.operationCount()).isEqualTo(1)
        }
        Fixture(FreeFlightDevicePolicy(2, "fixture-two")).use { f ->
            f.service.prepare(null, device())
            val distinct = parallel(6) { f.service.formal(null, formal()) }
            assertThat(distinct.count { it.approved }).isEqualTo(2)
            assertThat(distinct.count { it.reason == FreeFlightAdmissionReason.EXHAUSTED }).isEqualTo(4)
            assertThat(f.balance()).isEqualTo(0)
        }
    }

    @Test fun operationIsBoundToActorDeviceCredentialSessionAndFrozenPayload() {
        Fixture(FreeFlightDevicePolicy(2, "fixture-two")).use { f ->
            val user = f.user(); val other = f.user()
            f.service.prepare(user, device())
            val request = formal()
            val decision = f.service.formal(user, request)
            assertThat(decision.approved).isTrue()
            assertThat(f.service.operation(other, operation(request)).found).isFalse()
            assertThrows(Exception::class.java) { f.service.formal(other, request) }
            assertThrows(Exception::class.java) { f.service.localStartFailed(other, FreeFlightLocalFailureRequest(hwid, credential, request.operationId, request.sessionId, request.requestFingerprint)) }
            assertThrows(Exception::class.java) { f.service.formal(user, request.copy(requestFingerprint = "d".repeat(64))) }
            assertThrows(Exception::class.java) { f.service.formal(user, request.copy(operationId = UUID.randomUUID().toString())) }
            assertThat(f.service.operation(null, operation(request)).found).isFalse()
            assertThrows(Exception::class.java) { f.service.formal(null, request) } // No expired-user -> guest re-key and second consume.
            assertThrows(Exception::class.java) { f.service.operation(user, operation(request).copy(credential = otherCredential)) }
            assertThat(f.service.operation(user, operation(request)).decision).isEqualTo(decision)
            assertThrows(Exception::class.java) { f.service.operation(user, operation(request).copy(hwid = "b".repeat(64))) }
            assertThat(f.balance()).isEqualTo(1)
        }
    }

    @Test fun acceptedButLocalFailureIsRecordedIdempotentlyWithoutRefundOrNewFlightId() {
        Fixture(FreeFlightDevicePolicy(1, "fixture-one")).use { f ->
            f.service.prepare(null, device())
            val request = formal(); val decision = f.service.formal(null, request)
            val failure = FreeFlightLocalFailureRequest(hwid, credential, request.operationId, request.sessionId, request.requestFingerprint)
            val recorded = f.service.localStartFailed(null, failure)
            f.clock.set(start.plusSeconds(20))
            assertThat(f.service.localStartFailed(null, failure)).isEqualTo(recorded)
            assertThat(recorded.refunded).isFalse()
            assertThat(f.balance()).isEqualTo(0)
            assertThat(f.service.operation(null, operation(request)).localStartFailureRecorded).isTrue()
            assertThat(f.service.operation(null, operation(request)).decision).isEqualTo(decision)
            assertThrows(Exception::class.java) { f.service.localStartFailed(null, failure.copy(sessionId = "another-session")) }
        }
    }

    @Test fun decisionWriteFailureRollsBackConsumptionAndClaimTogether() {
        Fixture(FreeFlightDevicePolicy(1, "fixture-one")).use { f ->
            f.service.prepare(null, device())
            f.sql("ALTER TABLE freeflight_admission_operations ADD CONSTRAINT reject_fixture_approval CHECK (decision <> 'APPROVED')")
            val request = formal()
            assertThrows(Exception::class.java) { f.service.formal(null, request) }
            assertThat(f.balance()).isEqualTo(1)
            assertThat(f.operationCount()).isEqualTo(0)
            f.sql("ALTER TABLE freeflight_admission_operations DROP CONSTRAINT reject_fixture_approval")
            assertThat(f.service.formal(null, request).approved).isTrue()
            assertThat(f.balance()).isEqualTo(0)
        }
    }

    @Test fun explicitOperatorGrantIsDurableAndCannotBeReusedWithDifferentTerms() {
        Fixture().use { f ->
            f.service.provisionDevice(hwid, "signed-import-1", 2, "operator-approved-fixture")
            f.service.provisionDevice(hwid, "signed-import-1", 2, "operator-approved-fixture")
            assertThat(f.service.prepare(null, device()).remainingUses).isEqualTo(2)
            assertThat(f.grantCount()).isEqualTo(1)
            assertThrows(Exception::class.java) { f.service.provisionDevice(hwid, "signed-import-1", 9, "operator-approved-fixture") }
            assertThat(f.balance()).isEqualTo(2)
            assertThrows(Exception::class.java) { f.service.prepare(null, device().copy(credential = otherCredential)) }
        }
    }

    @Test fun diskRestartPreservesDecisionGrantAndFailureReceipt(@TempDir directory: Path) {
        val url = "jdbc:h2:file:" + directory.resolve("admission").toAbsolutePath().toString().replace('\\', '/') + ";MODE=PostgreSQL"
        val request = formal()
        val accepted = Fixture(FreeFlightDevicePolicy(1, "fixture-one"), url).use { f ->
            f.service.prepare(null, device())
            f.service.formal(null, request).also {
                f.service.localStartFailed(null, FreeFlightLocalFailureRequest(hwid, credential, request.operationId, request.sessionId, request.requestFingerprint))
            }
        }
        Fixture(FreeFlightDevicePolicy(99, "changed-policy"), url).use { f ->
            assertThat(f.service.prepare(null, device()).allowed).isFalse()
            assertThat(f.service.formal(null, request)).isEqualTo(accepted)
            assertThat(f.service.operation(null, operation(request)).localStartFailureRecorded).isTrue()
            assertThat(f.balance()).isEqualTo(0)
            assertThat(f.grantCount()).isEqualTo(1)
        }
    }

    @Test fun actualMemberNeverNeedsDeviceRegistrationProofOrEvenAValidDevicePayload() {
        Fixture().use { f ->
            val user = f.user(); f.entitlement(user, premium = true)
            assertThat(f.service.prepare(user, FreeFlightDeviceRequest()).source).isEqualTo(FreeFlightGrantSource.MEMBER)
            assertThat(f.service.prepare(user, FreeFlightDeviceRequest("invalid", "invalid")).allowed).isTrue()
            val request = formal().copy(hwid = null, credential = null)
            val accepted = f.service.formal(user, request)
            assertThat(accepted.approved).isTrue()
            assertThat(accepted.source).isEqualTo(FreeFlightGrantSource.MEMBER)
            assertThat(accepted.deviceRequired).isFalse()
            assertThat(f.deviceCount()).isEqualTo(0)
            assertThat(f.grantCount()).isEqualTo(0)
            transaction(f.db) { assertThat(Operations.selectAll().single()[Operations.hwid]).isNull() }
            val query = FreeFlightOperationRequest(operationId = request.operationId)
            assertThat(f.service.operation(user, query).decision).isEqualTo(accepted)
            assertThat(f.service.operation(null, query).found).isFalse()
            val failed = FreeFlightLocalFailureRequest(operationId = request.operationId, sessionId = request.sessionId, requestFingerprint = request.requestFingerprint)
            val receipt = f.service.localStartFailed(user, failed)
            assertThat(f.service.localStartFailed(user, failed)).isEqualTo(receipt)
            assertThat(receipt.refunded).isFalse()
            f.clock.set(start.plusSeconds(90))
            assertThat(f.service.formal(user, request)).isEqualTo(accepted)
        }
    }

    @Test fun nonMemberProofNegotiationNeverGrantsOrSpendsAndKeepsTheSameOperationForRealDeviceFallback() {
        Fixture(FreeFlightDevicePolicy(1, "fixture-one")).use { f ->
            val user = f.user() // Legacy isPremium=true alone is insufficient.
            assertThat(f.service.prepare(user, FreeFlightDeviceRequest()).reason).isEqualTo(FreeFlightAdmissionReason.DEVICE_IDENTITY_REQUIRED)
            val request = formal()
            val handshake = f.service.formal(user, request.copy(hwid = null, credential = null))
            assertThat(handshake.deviceRequired).isTrue()
            assertThat(handshake.approved).isFalse()
            assertThat(f.operationCount()).isEqualTo(0)
            assertThat(f.deviceCount()).isEqualTo(0)
            f.service.prepare(user, device())
            val accepted = f.service.formal(user, request)
            assertThat(accepted.approved).isTrue()
            assertThat(accepted.remainingUses).isEqualTo(0)
            assertThat(f.service.formal(user, request)).isEqualTo(accepted)
            // A duplicate without proof asks for that original proof; it does not change its decision or spend again.
            assertThat(f.service.formal(user, request.copy(hwid = null, credential = null)).deviceRequired).isTrue()
            assertThat(f.balance()).isEqualTo(0)
            assertThat(f.operationCount()).isEqualTo(1)
        }
    }

    @Test fun userScopedMemberOperationsCannotAliasOneAnotherOrBecomePretendDeviceRows() {
        Fixture().use { f ->
            val one = f.user(); val two = f.user(); f.entitlement(one, true); f.entitlement(two, true)
            val request = formal().copy(hwid = null, credential = null)
            val first = f.service.formal(one, request)
            assertThrows(Exception::class.java) { f.service.formal(two, request.copy(requestFingerprint = "d".repeat(64))) }
            val secondRequest = request.copy(operationId = UUID.randomUUID().toString(), requestFingerprint = "d".repeat(64))
            val second = f.service.formal(two, secondRequest)
            assertThat(first.requestFingerprint).isEqualTo("c".repeat(64))
            assertThat(second.requestFingerprint).isEqualTo("d".repeat(64))
            assertThat(f.service.operation(one, operation(request).copy(hwid = null, credential = null)).decision).isEqualTo(first)
            assertThat(f.service.operation(two, operation(secondRequest).copy(hwid = null, credential = null)).decision).isEqualTo(second)
            assertThat(f.operationCount()).isEqualTo(2)
            assertThat(f.deviceCount()).isEqualTo(0)
            transaction(f.db) { assertThat(Operations.selectAll().map { it[Operations.hwid] }).containsExactly(null, null) }
        }
    }

    private class Fixture(policy: FreeFlightDevicePolicy = FreeFlightDevicePolicy(), val url: String = "jdbc:h2:mem:ff_${UUID.randomUUID()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL") : AutoCloseable {
        val clock = MutableClock(start)
        private val handle = DatabaseFactory.init(DbConfig(url, "sa", "", 8))
        val db get() = handle.database
        val service = FreeFlightAdmissionService(db, policy, "admission-test-secret-only-at-least-32-bytes", clock)
        fun user(): String = UUID.randomUUID().toString().also { owner -> transaction(db) {
            Users.insert { it[id] = owner; it[email] = "$owner@fixture.invalid"; it[passwordHash] = "not-a-real-password"; it[username] = "fixture"; it[isPremium] = true }
        } }
        fun entitlement(owner: String, premium: Boolean) { transaction(db) {
            SubscriptionEntitlements.deleteWhere { ownerId eq owner }
            SubscriptionEntitlements.insert { it[ownerId] = owner; it[startsAt] = start; it[expiresAt] = start.plusSeconds(60); it[isPremium] = premium; it[revoked] = false }
        } }
        fun balance(): Long = transaction(db) { Devices.selectAll().where { Devices.hwid eq hwid }.single()[Devices.remainingUses] }
        fun deviceCount(): Long = transaction(db) { Devices.selectAll().count() }
        fun grantCount(): Long = transaction(db) { Grants.selectAll().count() }
        fun operationCount(): Long = transaction(db) { Operations.selectAll().count() }
        fun sql(sql: String) { DriverManager.getConnection(url, "sa", "").use { connection -> connection.createStatement().use { it.execute(sql) } } }
        override fun close() { handle.close(); if (url.startsWith("jdbc:h2:file:")) sql("SHUTDOWN") }
    }
    private class MutableClock(private val value: AtomicReference<Instant>, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
        constructor(value: Instant) : this(AtomicReference(value))
        fun set(now: Instant) { value.set(now) }
        override fun instant() = value.get()
        override fun getZone() = zone
        override fun withZone(zone: ZoneId): Clock = MutableClock(value, zone)
    }
    private fun <T> parallel(count: Int, call: () -> T): List<T> {
        val pool = Executors.newFixedThreadPool(count); val gate = CountDownLatch(1)
        return try {
            val jobs = (0 until count).map { pool.submit<T> { check(gate.await(5, TimeUnit.SECONDS)); call() } }
            gate.countDown(); jobs.map { it.get(20, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS) }
    }
    companion object {
        private val start = Instant.parse("2026-11-01T00:00:00Z")
        private val hwid = "a".repeat(64)
        private val credential = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 })
        private val otherCredential = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 8 })
        private fun device() = FreeFlightDeviceRequest(hwid, credential)
        private fun formal() = FreeFlightFormalRequest(hwid, credential, UUID.randomUUID().toString(), "session-${UUID.randomUUID()}", "c".repeat(64))
        private fun operation(request: FreeFlightFormalRequest) = FreeFlightOperationRequest(hwid, credential, request.operationId)
    }
}
