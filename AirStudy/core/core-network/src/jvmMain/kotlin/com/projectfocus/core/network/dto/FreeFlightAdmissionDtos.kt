package com.projectfocus.core.network.dto

import kotlinx.serialization.Serializable

/** Own protocol and explicit grant policy; no original address/credential is copied. */
@Serializable enum class FreeFlightGrantSource { MEMBER, DEVICE, NONE }
@Serializable enum class FreeFlightAdmissionReason {
    ALLOWED, POLICY_UNCONFIGURED, DEVICE_NOT_REGISTERED, DEVICE_DISABLED, EXHAUSTED, DEVICE_IDENTITY_REQUIRED,
}
@Serializable data class FreeFlightDeviceRequest(val hwid: String? = null, val credential: String? = null) {
    override fun toString() = "FreeFlightDeviceRequest(hwid=$hwid, credential=<redacted>)"
}
@Serializable data class FreeFlightPrepareReply(
    val allowed: Boolean,
    val source: FreeFlightGrantSource,
    val reason: FreeFlightAdmissionReason,
    val remainingUses: Long? = null,
    val totalUses: Long? = null,
)
@Serializable data class FreeFlightFormalRequest(
    val hwid: String? = null,
    val credential: String? = null,
    val operationId: String,
    val sessionId: String,
    val requestFingerprint: String,
) { override fun toString() = "FreeFlightFormalRequest(hwid=$hwid, credential=<redacted>, operationId=$operationId, sessionId=$sessionId, requestFingerprint=$requestFingerprint)" }
@Serializable data class FreeFlightFormalReply(
    val operationId: String,
    val sessionId: String,
    val requestFingerprint: String,
    val approved: Boolean,
    val source: FreeFlightGrantSource,
    val reason: FreeFlightAdmissionReason,
    val remainingUses: Long? = null,
    /** This is an admission receipt time, never a remote flight-record ID. */
    val decidedAtMillis: Long,
    /** Non-final proof negotiation. Never emitted as Decided and never a consumption result. */
    val deviceRequired: Boolean = false,
)
@Serializable data class FreeFlightOperationRequest(
    val hwid: String? = null, val credential: String? = null, val operationId: String,
) { override fun toString() = "FreeFlightOperationRequest(hwid=$hwid, credential=<redacted>, operationId=$operationId)" }
@Serializable data class FreeFlightOperationReply(
    val found: Boolean,
    val decision: FreeFlightFormalReply? = null,
    val localStartFailureRecorded: Boolean = false,
)
@Serializable data class FreeFlightLocalFailureRequest(
    val hwid: String? = null, val credential: String? = null, val operationId: String,
    val sessionId: String, val requestFingerprint: String,
) { override fun toString() = "FreeFlightLocalFailureRequest(hwid=$hwid, credential=<redacted>, operationId=$operationId, sessionId=$sessionId, requestFingerprint=$requestFingerprint)" }
@Serializable data class FreeFlightLocalFailureReply(
    val operationId: String,
    val recordedAtMillis: Long,
    /** Own policy records a failure for review; it never silently refunds. */
    val refunded: Boolean = false,
)
