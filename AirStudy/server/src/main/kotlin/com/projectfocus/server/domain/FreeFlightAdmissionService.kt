package com.projectfocus.server.domain

import com.projectfocus.core.network.dto.*
import com.projectfocus.server.db.FreeFlightDevices as Devices
import com.projectfocus.server.db.FreeFlightDeviceGrants as Grants
import com.projectfocus.server.db.FreeFlightAdmissionOperations as Operations
import com.projectfocus.server.db.Tables.Users
import io.ktor.http.HttpStatusCode
import java.nio.charset.StandardCharsets.UTF_8
import java.sql.Connection
import java.security.MessageDigest
import java.time.Clock
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

/** Own explicit-grant policy. Membership is resolved before any device validation. */
class FreeFlightAdmissionService(
    private val database: Database, private val policy: FreeFlightDevicePolicy,
    secret: String, private val clock: Clock = Clock.systemUTC(),
) {
    private val secretBytes = secret.toByteArray(UTF_8).also { require(it.size >= 32) }
    private data class DeviceProof(val hwid: String, val credential: String)

    fun prepare(ownerId: String?, request: FreeFlightDeviceRequest): FreeFlightPrepareReply = transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED, db = database) {
        val user = userPrincipal(ownerId)
        if (user != null && member(user)) return@transaction FreeFlightPrepareReply(true, FreeFlightGrantSource.MEMBER, FreeFlightAdmissionReason.ALLOWED)
        if (request.hwid == null && request.credential == null) return@transaction FreeFlightPrepareReply(false, FreeFlightGrantSource.NONE, FreeFlightAdmissionReason.DEVICE_IDENTITY_REQUIRED)
        val proof = validateDevice(request.hwid, request.credential)
        var row = Devices.selectAll().where { Devices.hwid eq proof.hwid }.forUpdate().singleOrNull()
        if (row == null) {
            val initial = policy.initialUses ?: return@transaction FreeFlightPrepareReply(false, FreeFlightGrantSource.NONE, FreeFlightAdmissionReason.POLICY_UNCONFIGURED)
            val created = Devices.insertIgnore {
                it[hwid] = proof.hwid; it[credentialDigest] = digest(proof.hwid, proof.credential)
                it[active] = true; it[remainingUses] = initial; it[totalUses] = initial; it[createdAtMillis] = clock.millis()
            }.insertedCount
            if (created == 1) Grants.insertIgnore {
                it[grantId] = hash("initial\u0000" + policy.policyId + "\u0000" + proof.hwid)
                it[hwid] = proof.hwid; it[amount] = initial; it[policyId] = requireNotNull(policy.policyId); it[createdAtMillis] = clock.millis()
            }
            row = Devices.selectAll().where { Devices.hwid eq proof.hwid }.forUpdate().single()
        }
        if (row[Devices.credentialDigest] == null) {
            Devices.update({ (Devices.hwid eq proof.hwid) and Devices.credentialDigest.isNull() }) { it[credentialDigest] = digest(proof.hwid, proof.credential) }
            row = Devices.selectAll().where { Devices.hwid eq proof.hwid }.forUpdate().single()
        }
        authorizeDevice(row, proof)
        val reason = deviceReason(row)
        FreeFlightPrepareReply(reason == FreeFlightAdmissionReason.ALLOWED, FreeFlightGrantSource.DEVICE, reason, row[Devices.remainingUses], row[Devices.totalUses])
    }

    fun formal(ownerId: String?, request: FreeFlightFormalRequest): FreeFlightFormalReply {
        operationId(request.operationId)
        if (!request.sessionId.matches(SESSION) || !request.requestFingerprint.matches(HEX)) throw BadRequestException("飞行授权资料有误")
        // A concurrent insertIgnore may wait for the winning operation to commit.
        // Its following read must see that actual row, rather than the connection's
        // earlier REPEATABLE_READ snapshot. Row locks, unique claims and balance
        // updates still share this same transaction; no consume is retried outside it.
        return transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED, db = database) {
            val user = userPrincipal(ownerId)
            var proof: DeviceProof? = null
            var actor = user
            if (actor == null && (request.hwid != null || request.credential != null)) {
                val actual = validateDevice(request.hwid, request.credential); proof = actual; actor = "guest:${actual.hwid}"
            }
            actor?.let { known ->
                Operations.selectAll().where { Operations.key eq operationKey(known, request.operationId) }.forUpdate().singleOrNull()?.let { old ->
                    verifyBinding(old, known, request.sessionId, request.requestFingerprint)
                    if (old[Operations.grantSource] != FreeFlightGrantSource.MEMBER.name && request.hwid == null && request.credential == null)
                        return@transaction deviceRequired(request)
                    authorizeOperation(old, known, request.hwid, request.credential)
                    check(old[Operations.decision] != "CHECKING")
                    return@transaction reply(old)
                }
            }
            val membership = user != null && member(user)
            if (!membership && request.hwid == null && request.credential == null) return@transaction deviceRequired(request)
            if (!membership && proof == null) proof = validateDevice(request.hwid, request.credential)
            val principal = user ?: "guest:${requireNotNull(proof).hwid}"
            val key = operationKey(principal, request.operationId)
            val now = clock.millis()
            Operations.insertIgnore {
                it[Operations.key] = key; it[hwid] = if (membership) null else requireNotNull(proof).hwid
                it[operationId] = request.operationId; it[sessionId] = request.sessionId; it[principalKey] = principal
                it[requestFingerprint] = request.requestFingerprint; it[decision] = "CHECKING"
                it[grantSource] = FreeFlightGrantSource.NONE.name; it[reason] = FreeFlightAdmissionReason.POLICY_UNCONFIGURED.name
                it[remainingUses] = null; it[decidedAtMillis] = now; it[localFailureAtMillis] = null
            }
            val op = Operations.selectAll().where { Operations.key eq key }.forUpdate().singleOrNull() ?: throw conflict()
            verifyBinding(op, principal, request.sessionId, request.requestFingerprint)
            if (op[Operations.decision] != "CHECKING") {
                authorizeOperation(op, principal, request.hwid, request.credential)
                return@transaction reply(op)
            }
            var source = FreeFlightGrantSource.MEMBER
            var reason = FreeFlightAdmissionReason.ALLOWED
            var remaining: Long? = null
            if (!membership) {
                val actual = requireNotNull(proof)
                source = FreeFlightGrantSource.DEVICE
                val device = Devices.selectAll().where { Devices.hwid eq actual.hwid }.forUpdate().singleOrNull()
                if (device == null) {
                    source = FreeFlightGrantSource.NONE
                    reason = if (policy.initialUses == null) FreeFlightAdmissionReason.POLICY_UNCONFIGURED else FreeFlightAdmissionReason.DEVICE_NOT_REGISTERED
                } else {
                    authorizeDevice(device, actual)
                    reason = deviceReason(device); remaining = device[Devices.remainingUses]
                    if (reason == FreeFlightAdmissionReason.ALLOWED) {
                        val old = requireNotNull(remaining); check(old > 0)
                        check(Devices.update({ (Devices.hwid eq actual.hwid) and (Devices.remainingUses eq old) and (Devices.active eq true) }) { it[remainingUses] = old - 1 } == 1)
                        remaining = old - 1
                    }
                }
            }
            Operations.update({ Operations.key eq key }) {
                it[decision] = if (reason == FreeFlightAdmissionReason.ALLOWED) "APPROVED" else "REJECTED"
                it[Operations.grantSource] = source.name; it[Operations.reason] = reason.name; it[remainingUses] = remaining; it[decidedAtMillis] = now
            }
            reply(Operations.selectAll().where { Operations.key eq key }.single())
        }
    }

    fun operation(ownerId: String?, request: FreeFlightOperationRequest): FreeFlightOperationReply {
        operationId(request.operationId)
        return transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED, db = database) {
            val actor = principalForLookup(ownerId, request.hwid, request.credential) ?: return@transaction FreeFlightOperationReply(false)
            val row = Operations.selectAll().where { Operations.key eq operationKey(actor, request.operationId) }.singleOrNull() ?: return@transaction FreeFlightOperationReply(false)
            authorizeOperation(row, actor, request.hwid, request.credential)
            check(row[Operations.decision] != "CHECKING")
            FreeFlightOperationReply(true, reply(row), row[Operations.localFailureAtMillis] != null)
        }
    }

    fun localStartFailed(ownerId: String?, request: FreeFlightLocalFailureRequest): FreeFlightLocalFailureReply {
        operationId(request.operationId)
        return transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED, db = database) {
            val actor = principalForLookup(ownerId, request.hwid, request.credential) ?: throw conflict()
            val key = operationKey(actor, request.operationId)
            val row = Operations.selectAll().where { Operations.key eq key }.forUpdate().singleOrNull() ?: throw conflict()
            verifyBinding(row, actor, request.sessionId, request.requestFingerprint)
            authorizeOperation(row, actor, request.hwid, request.credential)
            if (row[Operations.decision] != "APPROVED") throw conflict()
            val first = row[Operations.localFailureAtMillis] ?: clock.millis().also { time -> Operations.update({ Operations.key eq key }) { it[localFailureAtMillis] = time } }
            FreeFlightLocalFailureReply(request.operationId, first, refunded = false)
        }
    }

    /** Internal operator/import port only; no public grant endpoint exists. */
    fun provisionDevice(hwid: String, grantId: String, amount: Long, policyId: String, active: Boolean = true) {
        if (!hwid.matches(HEX) || grantId.isBlank() || grantId.length > 64 || amount !in 0..1_000_000L || policyId.isBlank() || policyId.length > 64) throw BadRequestException("设备授权资料有误")
        transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED, db = database) {
            Devices.insertIgnore { it[Devices.hwid] = hwid; it[credentialDigest] = null; it[Devices.active] = active; it[remainingUses] = 0; it[totalUses] = 0; it[createdAtMillis] = clock.millis() }
            val device = Devices.selectAll().where { Devices.hwid eq hwid }.forUpdate().single()
            val old = Grants.selectAll().where { Grants.grantId eq grantId }.singleOrNull()
            if (old != null) { if (old[Grants.hwid] != hwid || old[Grants.amount] != amount || old[Grants.policyId] != policyId) throw conflict(); return@transaction }
            Grants.insertIgnore { it[Grants.grantId] = grantId; it[Grants.hwid] = hwid; it[Grants.amount] = amount; it[Grants.policyId] = policyId; it[createdAtMillis] = clock.millis() }.also { check(it.insertedCount == 1) }
            Devices.update({ Devices.hwid eq hwid }) { it[remainingUses] = Math.addExact(device[Devices.remainingUses], amount); it[totalUses] = Math.addExact(device[Devices.totalUses], amount); it[Devices.active] = active }
        }
    }

    private fun userPrincipal(ownerId: String?): String? = ownerId?.takeIf { candidate -> Users.selectAll().where { Users.id eq candidate }.limit(1).firstOrNull() != null }?.let { "user:$it" }
    private fun principalForLookup(ownerId: String?, hwid: String?, credential: String?): String? = userPrincipal(ownerId) ?: if (hwid == null && credential == null) null else "guest:${validateDevice(hwid, credential).hwid}"
    private fun member(actor: String): Boolean = SubscriptionStatusService(database, clock).status(actor.removePrefix("user:")).let { it.isActive && it.isPremium && it.remainingDays > 0 }
    private fun deviceRequired(request: FreeFlightFormalRequest) = FreeFlightFormalReply(request.operationId, request.sessionId, request.requestFingerprint,
        approved = false, source = FreeFlightGrantSource.NONE, reason = FreeFlightAdmissionReason.DEVICE_IDENTITY_REQUIRED, remainingUses = null, decidedAtMillis = clock.millis(), deviceRequired = true)
    private fun deviceReason(row: ResultRow): FreeFlightAdmissionReason = when {
        !row[Devices.active] -> FreeFlightAdmissionReason.DEVICE_DISABLED
        row[Devices.remainingUses] <= 0 -> FreeFlightAdmissionReason.EXHAUSTED
        else -> FreeFlightAdmissionReason.ALLOWED
    }
    private fun authorizeDevice(row: ResultRow, proof: DeviceProof) {
        val expected = row[Devices.credentialDigest] ?: throw UnauthorizedException("设备授权尚未完成")
        if (!MessageDigest.isEqual(expected.toByteArray(UTF_8), digest(proof.hwid, proof.credential).toByteArray(UTF_8))) throw UnauthorizedException("设备授权不匹配")
    }
    private fun authorizeOperation(row: ResultRow, actor: String, hwid: String?, credential: String?) {
        if (row[Operations.principalKey] != actor) throw UnauthorizedException("无法读取本次飞行授权")
        if (row[Operations.grantSource] != FreeFlightGrantSource.MEMBER.name) {
            val proof = validateDevice(hwid, credential)
            if (proof.hwid != row[Operations.hwid]) throw UnauthorizedException("本次飞行属于另一设备")
            Devices.selectAll().where { Devices.hwid eq proof.hwid }.singleOrNull()?.let { authorizeDevice(it, proof) }
        }
    }
    private fun verifyBinding(row: ResultRow, actor: String, sessionId: String, fingerprint: String) { if (row[Operations.principalKey] != actor || row[Operations.sessionId] != sessionId || row[Operations.requestFingerprint] != fingerprint) throw conflict() }
    private fun reply(row: ResultRow) = FreeFlightFormalReply(row[Operations.operationId], row[Operations.sessionId], row[Operations.requestFingerprint], row[Operations.decision] == "APPROVED", FreeFlightGrantSource.valueOf(row[Operations.grantSource]), FreeFlightAdmissionReason.valueOf(row[Operations.reason]), row[Operations.remainingUses], row[Operations.decidedAtMillis])
    private fun validateDevice(hwid: String?, credential: String?): DeviceProof {
        if (hwid == null || credential == null || !hwid.matches(HEX) || !credential.matches(Regex("[A-Za-z0-9_-]{43}"))) throw BadRequestException("设备授权资料有误")
        if (runCatching { Base64.getUrlDecoder().decode(credential).size }.getOrNull() != 32) throw BadRequestException("设备授权资料有误")
        return DeviceProof(hwid, credential)
    }
    private fun operationId(value: String) { if (runCatching { UUID.fromString(value).toString() }.getOrNull() != value) throw BadRequestException("飞行授权资料有误") }
    private fun operationKey(actor: String, operation: String) = hash("operation\u0000$actor\u0000$operation")
    private fun digest(hwid: String, credential: String): String = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(secretBytes, "HmacSHA256")); doFinal("freeflight-device-v1\u0000$hwid\u0000$credential".toByteArray(UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) } }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun conflict() = DomainException(3010, "本次飞行授权资料不一致", HttpStatusCode.Conflict)
    companion object { private val HEX = Regex("[0-9a-f]{64}"); private val SESSION = Regex("[A-Za-z0-9._:-]{1,128}") }
}
