package com.projectfocus.server.db

import org.jetbrains.exposed.sql.Table

/** Own persistent device allowance. No implicit free grant or periodic reset. */
object FreeFlightDevices : Table("freeflight_devices") {
    val hwid = varchar("hwid", 64)
    val credentialDigest = varchar("credential_digest", 64).nullable()
    val active = bool("active")
    val remainingUses = long("remaining_uses")
    val totalUses = long("total_uses")
    val createdAtMillis = long("created_at_ms")
    override val primaryKey = PrimaryKey(hwid)
}

/** Operator/config grant IDs are idempotent; no public grant endpoint exists. */
object FreeFlightDeviceGrants : Table("freeflight_device_grants") {
    val grantId = varchar("grant_id", 64)
    val hwid = varchar("hwid", 64).references(FreeFlightDevices.hwid)
    val amount = long("amount")
    val policyId = varchar("policy_id", 64)
    val createdAtMillis = long("created_at_ms")
    override val primaryKey = PrimaryKey(grantId)
}

/** Operation claim, decision and balance change share one SQL transaction. */
object FreeFlightAdmissionOperations : Table("freeflight_admission_operations") {
    val key = varchar("operation_key", 64)
    val hwid = varchar("hwid", 64).nullable()
    val operationId = varchar("operation_id", 36)
    val sessionId = varchar("session_id", 128)
    val principalKey = varchar("principal_key", 80)
    val requestFingerprint = varchar("request_fingerprint", 64)
    val decision = varchar("decision", 16)
    val grantSource = varchar("source", 16)
    val reason = varchar("reason", 32)
    val remainingUses = long("remaining_uses").nullable()
    val decidedAtMillis = long("decided_at_ms")
    val localFailureAtMillis = long("local_failure_at_ms").nullable()
    override val primaryKey = PrimaryKey(key)
    init {
        uniqueIndex(principalKey, sessionId)
        // A captured bearer expiring must not re-key the same operation into a new guest consume.
        uniqueIndex(operationId)
    }
}
