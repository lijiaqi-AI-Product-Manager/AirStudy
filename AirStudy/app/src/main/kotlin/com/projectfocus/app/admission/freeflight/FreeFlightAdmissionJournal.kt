package com.projectfocus.app.admission.freeflight

import android.content.SharedPreferences
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.freeflight.FreeFlightScene
import com.projectfocus.core.model.flight.freeflight.FreeFlightStartRequest
import com.projectfocus.core.network.dto.FreeFlightFormalReply
import com.projectfocus.core.network.dto.FreeFlightLocalFailureReply
import java.io.IOException
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable internal data class FreeFlightAdmissionEntry(
    val ownerScope: String, val ownerGeneration: Long, val bearerFingerprint: String,
    val hwid: String?, val deviceCredentialFingerprint: String?, val operationId: String, val sessionId: String, val requestFingerprint: String,
    val createdAtMillis: Long,
    val pendingReceiptId: String? = null,
    val pendingActive: Boolean = false,
    val decision: FreeFlightFormalReply? = null,
    val localFailureQueued: Boolean = false,
    val failureAcknowledged: FreeFlightLocalFailureReply? = null,
    val localStartCommittedAtMillis: Long? = null,
)

/** App-owned durable outbox. No raw bearer/device secret is stored in this journal. */
class FreeFlightAdmissionJournal(private val preferences: SharedPreferences) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    internal fun createOrRead(identity: CapturedFreeFlightAdmissionIdentity,
        request: FreeFlightStartRequest, now: Long): Pair<FreeFlightAdmissionEntry, Boolean> = synchronized(lock) {
        require(request.ownerScope == identity.owner.ownerScope)
        val fingerprint = fingerprint(request)
        val matches = all().filter { it.ownerScope == request.ownerScope && it.sessionId == request.sessionId }
        if (matches.isNotEmpty()) {
            if (matches.size != 1) throw IOException("Ambiguous admission ownership")
            val old = matches.single()
            if (old.ownerGeneration != identity.owner.generation || old.bearerFingerprint != identity.bearerFingerprint ||
                old.requestFingerprint != fingerprint) throw IOException("Admission identity or frozen request differs")
            return@synchronized old to false
        }
        val value = FreeFlightAdmissionEntry(request.ownerScope, identity.owner.generation, identity.bearerFingerprint,
            null, null, UUID.randomUUID().toString(), request.sessionId, fingerprint, now)
        write(value); value to true
    }
    internal fun read(operationId: String): FreeFlightAdmissionEntry = synchronized(lock) {
        all().singleOrNull { it.operationId == operationId } ?: throw IOException("Admission operation is missing")
    }
    /** One-way binding only after the actual backend asks for device proof. */
    internal fun bindDevice(operationId: String, device: FreeFlightDeviceIdentity): FreeFlightAdmissionEntry = synchronized(lock) {
        val before = read(operationId)
        val fingerprint = admissionHash(device.credential)
        if (before.hwid != null || before.deviceCredentialFingerprint != null) {
            if (before.hwid != device.hwid || before.deviceCredentialFingerprint != fingerprint) throw IOException("Original device proof differs")
            return@synchronized before
        }
        if (before.decision != null) throw IOException("A decided member operation cannot become a device operation")
        before.copy(hwid = device.hwid, deviceCredentialFingerprint = fingerprint).also { write(it) }
    }
    internal fun find(request: FreeFlightStartRequest): FreeFlightAdmissionEntry = synchronized(lock) {
        all().singleOrNull { it.ownerScope == request.ownerScope && it.sessionId == request.sessionId && it.requestFingerprint == fingerprint(request) }
            ?: throw IOException("Matching admission operation is missing")
    }
    internal fun update(operationId: String, change: (FreeFlightAdmissionEntry) -> FreeFlightAdmissionEntry): FreeFlightAdmissionEntry = synchronized(lock) {
        val before = read(operationId); val after = change(before)
        if (after.operationId != before.operationId || after.ownerScope != before.ownerScope || after.ownerGeneration != before.ownerGeneration ||
            after.hwid != before.hwid || after.sessionId != before.sessionId || after.requestFingerprint != before.requestFingerprint ||
            after.bearerFingerprint != before.bearerFingerprint || after.deviceCredentialFingerprint != before.deviceCredentialFingerprint) throw IOException("Immutable admission binding changed")
        write(after); after
    }
    internal fun entries(identity: CapturedFreeFlightAdmissionIdentity): List<FreeFlightAdmissionEntry> = synchronized(lock) {
        all().filter { it.ownerScope == identity.owner.ownerScope && it.ownerGeneration == identity.owner.generation && it.bearerFingerprint == identity.bearerFingerprint }
    }
    private fun all(): List<FreeFlightAdmissionEntry> = preferences.all.filterKeys { it.startsWith(PREFIX) }.values.map {
        json.decodeFromString(FreeFlightAdmissionEntry.serializer(), it as? String ?: throw IOException("Invalid admission journal value")).also { value ->
            if (value.ownerScope.isBlank() || value.hwid != null && value.hwid.length != 64 || value.bearerFingerprint.length != 64 || value.requestFingerprint.length != 64 || value.deviceCredentialFingerprint != null && value.deviceCredentialFingerprint.length != 64 ||
                (value.hwid == null) != (value.deviceCredentialFingerprint == null) || value.decision?.deviceRequired == true ||
                value.sessionId.isBlank() || value.operationId.isBlank() || value.pendingActive && value.pendingReceiptId.isNullOrBlank() ||
                value.pendingActive && value.decision != null || value.localFailureQueued && value.decision?.approved != true ||
                value.failureAcknowledged != null && !value.localFailureQueued ||
                value.localStartCommittedAtMillis != null && (value.decision?.approved != true || value.localFailureQueued)) throw IOException("Admission journal binding is invalid")
        }
    }
    private fun write(value: FreeFlightAdmissionEntry) {
        val key = PREFIX + admissionHash(value.ownerScope + "\u0000" + value.sessionId)
        val before = preferences.getString(key, null)
        val raw = json.encodeToString(FreeFlightAdmissionEntry.serializer(), value)
        try {
            if (!preferences.edit().putString(key, raw).commit() || preferences.getString(key, null) != raw) throw IOException("Admission journal write failed")
        } catch (error: Exception) {

            if (preferences.getString(key, null) == raw) {
                val rollback = preferences.edit()
                if (before == null) rollback.remove(key) else rollback.putString(key, before)
                if (!rollback.commit()) error.addSuppressed(IOException("Admission journal rollback failed"))
            }
            throw error
        }
    }
    companion object {
        private const val PREFIX = "operation:"
        private val lock = Any() // One process: no multi-process SharedPreferences transaction claim.
        internal fun fingerprint(request: FreeFlightStartRequest): String {
            val json = Json { encodeDefaults = true; explicitNulls = true }
            val value = buildJsonObject {
                put("sessionId", request.sessionId); put("ownerScope", request.ownerScope)
                put("departure", json.encodeToJsonElement(PlannedAirport.serializer(), request.departure))
                put("headingDegrees", request.headingDegrees); put("seat", request.seat)
                put("scene", json.encodeToJsonElement(FreeFlightScene.serializer(), request.scene))
                put("flightNumber", request.flightNumber?.let(::JsonPrimitive) ?: JsonNull)
                put("shieldingCategoryId", request.shieldingCategoryId?.let(::JsonPrimitive) ?: JsonNull)
            }
            return admissionHash(value.toString())
        }
    }
}
