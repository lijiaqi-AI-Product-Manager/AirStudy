package com.projectfocus.app.admission.freeflight

import com.projectfocus.core.model.flight.freeflight.FreeFlightStartRequest
import com.projectfocus.core.network.dto.*
import com.projectfocus.core.network.freeflight.FreeFlightAdmissionGateway
import com.projectfocus.service.focus.freeflight.*
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class FreeFlightAdmissionStage { PREPARING, PREPARED, REJECTED, REQUESTING, PENDING, UNCERTAIN, APPROVED, LOCAL_FAILURE_QUEUED, LOCAL_FAILURE_ACKNOWLEDGED, LOCAL_START_ACK_REQUIRED, LOCAL_START_ACKNOWLEDGED }
data class FreeFlightAdmissionObservation(
    val owner: FreeFlightOwnerToken, val sessionId: String?, val operationId: String?,
    val stage: FreeFlightAdmissionStage, val source: FreeFlightGrantSource? = null,
    val remainingUses: Long? = null, val receiptId: String? = null, val reason: String? = null,
)

/**
 * Actual Own backend adapter for the engine v2 ports. The supplied scope belongs
 * to Application, never a screen. Eight seconds creates a durable Pending receipt;
 * it does not cancel the actual formal call or manufacture an approval/refund.
 */
class OwnFreeFlightAdmission(
    private val gateway: FreeFlightAdmissionGateway,
    private val identities: FreeFlightAdmissionIdentityProvider,
    private val devices: FreeFlightDeviceIdentityProvider,
    private val journal: FreeFlightAdmissionJournal,
    private val applicationScope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : FreeFlightPreparationAdmission, FreeFlightAdmission {
    private class Running(
        identity: CapturedFreeFlightAdmissionIdentity,
        device: FreeFlightDeviceIdentity?,
        val operationId: String,
    ) {
        val owner = identity.owner
        var identity: CapturedFreeFlightAdmissionIdentity? = identity
        var device: FreeFlightDeviceIdentity? = device
        val listeners = mutableListOf<(FreeFlightAdmissionEvent) -> Unit>()
        var terminal: FreeFlightAdmissionEvent.Decided? = null
        var timer: Job? = null
        var queryRunning = false
        var deviceProofRequested = false
    }
    private val lock = Any()
    private val running = mutableMapOf<String, Running>()
    private val observed = MutableStateFlow<Map<String, FreeFlightAdmissionObservation>>(emptyMap())

    /** Account-scoped observation: callers cannot accidentally render another owner/generation. */
    fun observations(owner: FreeFlightOwnerToken): Flow<List<FreeFlightAdmissionObservation>> = observed.map { values ->
        values.values.filter { it.owner == owner }
    }

    override suspend fun check(owner: FreeFlightOwnerToken): FreeFlightPreparationResult {
        val identity = identities.capture(owner) ?: return FreeFlightPreparationResult.Rejected("当前账号已变化，请重新准备")
        publish(identity, null, null, FreeFlightAdmissionStage.PREPARING)
        return try {
            withTimeout(PREPARATION_TIMEOUT_MILLIS) {
                if (!identities.isCurrent(identity)) return@withTimeout rejectPreparation(identity, "当前账号已变化，请重新准备")
                var reply = gateway.prepare(FreeFlightDeviceRequest(), identity.bearer)
                if (!reply.allowed && reply.source == FreeFlightGrantSource.NONE && reply.reason == FreeFlightAdmissionReason.DEVICE_IDENTITY_REQUIRED) {
                    if (!identities.isCurrent(identity)) return@withTimeout rejectPreparation(identity, "当前账号已变化，请重新准备")
                    val device = withContext(io) { devices.capture() }
                    if (!identities.isCurrent(identity)) return@withTimeout rejectPreparation(identity, "当前账号已变化，请重新准备")
                    reply = gateway.prepare(FreeFlightDeviceRequest(device.hwid, device.credential), identity.bearer)
                }
                if (!identities.isCurrent(identity)) return@withTimeout rejectPreparation(identity, "当前账号已变化，请重新准备")
                if (!validPreparation(reply)) return@withTimeout rejectPreparation(identity, "准备授权返回资料不完整")
                publish(identity, null, null, if (reply.allowed) FreeFlightAdmissionStage.PREPARED else FreeFlightAdmissionStage.REJECTED,
                    source = reply.source, remaining = reply.remainingUses, reason = reply.reason.name)
                if (reply.allowed) FreeFlightPreparationResult.Allowed else FreeFlightPreparationResult.Rejected(message(reply.reason))
            }
        } catch (_: TimeoutCancellationException) {
            rejectPreparation(identity, "暂时无法确认飞行额度，请稍后重试")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { rejectPreparation(identity, "暂时无法确认飞行额度，请稍后重试") }
    }

    override fun request(owner: FreeFlightOwnerToken, request: FreeFlightStartRequest, emit: (FreeFlightAdmissionEvent) -> Unit) {
        applicationScope.launch(io) {
            val identity = identities.capture(owner)
            if (identity == null || request.ownerScope != owner.ownerScope) {
                emitSafely(emit, FreeFlightAdmissionEvent.Decided(FreeFlightAdmissionResult.Rejected("当前账号已变化，请重新准备")))
                return@launch
            }
            val entry: FreeFlightAdmissionEntry
            val created: Boolean
            try {
                val saved = journal.createOrRead(identity, request, nowMillis())
                entry = saved.first; created = saved.second
            } catch (_: Exception) {
                // A previous formal call might exist. Never turn an unreadable outbox into a rejection/retry.
                emitSafely(emit, FreeFlightAdmissionEvent.Uncertain("本地授权记录暂时不可读，请保留本次准备并重试查询"))
                publish(identity, request.sessionId, null, FreeFlightAdmissionStage.UNCERTAIN, reason = "LOCAL_RECORD_UNAVAILABLE")
                return@launch
            }
            if (entry.localFailureQueued) {
                // This is a new local attempt after the prior accepted-but-unstarted attempt
                // was durably closed. Keep its original server approval/receipt unchanged.
                publishOwner(identity.owner, entry.sessionId, entry.operationId,
                    if (entry.failureAcknowledged != null) FreeFlightAdmissionStage.LOCAL_FAILURE_ACKNOWLEDGED else FreeFlightAdmissionStage.LOCAL_FAILURE_QUEUED,
                    source = entry.decision?.source, reason = "LOCAL_FAILED_ATTEMPT_IS_TERMINAL")
                emitSafely(emit, FreeFlightAdmissionEvent.Decided(FreeFlightAdmissionResult.Rejected("本次飞行已记录启动失败，请新建飞行准备")))
                return@launch
            }
            var replay: FreeFlightAdmissionEvent? = null
            var freshRuntime = false
            val state = synchronized(lock) {
                running[entry.operationId]?.also { existing ->
                    check(existing.identity == identity)
                    replay = existing.terminal
                    if (replay == null) existing.listeners += emit
                } ?: Running(identity, null, entry.operationId).also {
                    it.listeners += emit; running[entry.operationId] = it; freshRuntime = true
                }
            }
            if (replay != null) { emitSafely(emit, requireNotNull(replay)); return@launch }
            if (entry.decision != null) {
                if (entry.decision.source != FreeFlightGrantSource.MEMBER && !restoreDevice(state, entry)) return@launch
                decide(state, requireNotNull(entry.decision)); return@launch
            }
            if (entry.pendingActive) emitSafely(emit, FreeFlightAdmissionEvent.Pending(requireNotNull(entry.pendingReceiptId)))
            if (!freshRuntime) return@launch // One actual formal POST per durable operation in this process.
            if (!created) {
                publish(identity, entry.sessionId, entry.operationId, FreeFlightAdmissionStage.UNCERTAIN, reason = "RECOVERING_RECORDED_OPERATION")
                if (!entry.pendingActive) emitAll(state, FreeFlightAdmissionEvent.Uncertain("正在查询上次授权结果，请稍候"))
                if (entry.hwid == null || restoreDevice(state, entry)) query(state)
                return@launch
            }
            if (!identities.isCurrent(identity)) {
                // This record has never been submitted; preserve it and require a fresh product operation.
                emitAll(state, FreeFlightAdmissionEvent.Uncertain("当前账号已变化，本次授权尚未提交"))
                publish(identity, entry.sessionId, entry.operationId, FreeFlightAdmissionStage.UNCERTAIN, reason = "IDENTITY_CHANGED_BEFORE_SUBMIT")
                return@launch
            }
            publish(identity, entry.sessionId, entry.operationId, FreeFlightAdmissionStage.REQUESTING)
            state.timer = applicationScope.launch(io) {
                delay(FORMAL_PENDING_MILLIS)
                makePending(state)
            }
            submit(state, entry, null)
        }
    }

    /** Explicit query action; no formal POST retry, grant or refund is hidden in this method. */
    fun reconcile(owner: FreeFlightOwnerToken) {
        applicationScope.launch(io) {
            val identity = identities.capture(owner) ?: return@launch
            val entries = runCatching { journal.entries(identity) }.getOrNull() ?: return@launch
            entries.forEach { entry ->
                if (entry.localStartCommittedAtMillis != null) {
                    publishOwner(identity.owner, entry.sessionId, entry.operationId, FreeFlightAdmissionStage.LOCAL_START_ACKNOWLEDGED, source = entry.decision?.source)
                    return@forEach
                }
                if (entry.failureAcknowledged != null) {
                    publishOwner(identity.owner, entry.sessionId, entry.operationId, FreeFlightAdmissionStage.LOCAL_FAILURE_ACKNOWLEDGED, source = entry.decision?.source)
                    return@forEach
                }
                if (entry.decision != null && !entry.localFailureQueued) {
                    publishOwner(identity.owner, entry.sessionId, entry.operationId,
                        if (entry.decision.approved) FreeFlightAdmissionStage.LOCAL_START_ACK_REQUIRED else FreeFlightAdmissionStage.REJECTED,
                        source = entry.decision.source, reason = if (entry.decision.approved) "LOCAL_ARCHIVE_ACK_NOT_RECORDED" else entry.decision.reason.name)
                    return@forEach // No credentials retained merely to redisplay an already persisted decision.
                }
                val state = synchronized(lock) { running.getOrPut(entry.operationId) { Running(identity, null, entry.operationId) } }
                if (entry.hwid != null && entry.decision?.source != FreeFlightGrantSource.MEMBER && !restoreDevice(state, entry)) return@forEach
                if (entry.decision == null) query(state)
                if (entry.localFailureQueued && entry.failureAcknowledged == null) sendLocalFailure(state, entry)
            }
        }
    }

    override suspend fun localStartFailed(request: FreeFlightStartRequest, accepted: FreeFlightAdmissionResult.Accepted) {
        val entry = withContext(io) {
            val before = journal.find(request)
            require(accepted.remoteRecordId == null && before.decision?.approved == true && before.localStartCommittedAtMillis == null) { "A matching durable unstarted approval is required" }
            if (before.localFailureQueued) before else journal.update(before.operationId) { it.copy(localFailureQueued = true) }
        } // The engine may release ownership only after this durable queue write succeeds.
        if (entry.failureAcknowledged != null) {
            publishOwner(FreeFlightOwnerToken(entry.ownerScope, entry.ownerGeneration), entry.sessionId, entry.operationId,
                FreeFlightAdmissionStage.LOCAL_FAILURE_ACKNOWLEDGED, source = entry.decision?.source)
            return
        }
        val state = stateFor(entry)
        publishOwner(FreeFlightOwnerToken(entry.ownerScope, entry.ownerGeneration), entry.sessionId, entry.operationId,
            FreeFlightAdmissionStage.LOCAL_FAILURE_QUEUED, source = entry.decision?.source)
        if (state != null) {
            applicationScope.launch(io) { sendLocalFailure(state, entry) }
        }
    }

    /** Called only after the repository durably created the real archive and exited its Core lease. */
    override suspend fun localStartCommitted(request: FreeFlightStartRequest, accepted: FreeFlightAdmissionResult.Accepted) {
        val entry = withContext(io) {
            val before = journal.find(request)
            val recordedOwner = FreeFlightOwnerToken(before.ownerScope, before.ownerGeneration)
            require(accepted.remoteRecordId == null && before.decision?.approved == true && !before.localFailureQueued) { "A matching successful local start is required" }
            try {
                if (before.localStartCommittedAtMillis != null) before
                else journal.update(before.operationId) { old -> old.copy(localStartCommittedAtMillis = old.localStartCommittedAtMillis ?: nowMillis()) }
            } catch (error: Exception) {
                publishOwner(recordedOwner, before.sessionId, before.operationId, FreeFlightAdmissionStage.LOCAL_START_ACK_REQUIRED, source = before.decision?.source, reason = "LOCAL_START_ACK_WRITE_FAILED")
                throw error
            }
        }
        clearCredentials(entry.operationId)
        publishOwner(FreeFlightOwnerToken(entry.ownerScope, entry.ownerGeneration), entry.sessionId, entry.operationId, FreeFlightAdmissionStage.LOCAL_START_ACKNOWLEDGED, source = entry.decision?.source)
    }

    /** Diagnostics expose a count only, never a captured credential. */
    internal fun retainedCredentialCount(): Int = synchronized(lock) { running.values.count { it.identity != null || it.device != null } }

    private fun clearCredentials(operationId: String) = synchronized(lock) {
        running.remove(operationId)?.let { state ->
            state.identity = null; state.device = null; state.listeners.clear(); state.timer?.cancel()
        }
    }

    private suspend fun restoreDevice(state: Running, entry: FreeFlightAdmissionEntry): Boolean = try {
        val actual = withContext(io) { devices.capture() }
        journal.bindDevice(entry.operationId, actual)
        synchronized(lock) { state.device = actual }
        true
    } catch (_: Exception) { uncertain(state, "无法核对本次授权的原设备凭据"); false }

    private suspend fun submit(state: Running, entry: FreeFlightAdmissionEntry, device: FreeFlightDeviceIdentity?) {
        val identity = synchronized(lock) { if (state.terminal != null) null else state.identity } ?: return
        try {
            gateway.submit(FreeFlightFormalRequest(device?.hwid, device?.credential, entry.operationId, entry.sessionId, entry.requestFingerprint), identity.bearer) { result ->
                applicationScope.launch(io) { result.fold({ reply ->
                    if (reply.deviceRequired) provideDevice(state, reply) else decide(state, reply)
                }, { uncertain(state, "网络暂未确认本次授权结果"); query(state) }) }
            }
        } catch (_: Exception) { uncertain(state, "网络暂未确认本次授权结果"); query(state) }
    }

    /** Explicit proof negotiation, not a transport retry or a fabricated consume decision. */
    private suspend fun provideDevice(state: Running, reply: FreeFlightFormalReply) {
        try {
            val entry = journal.read(state.operationId)
            require(reply.operationId == entry.operationId && reply.sessionId == entry.sessionId && reply.requestFingerprint == entry.requestFingerprint &&
                reply.deviceRequired && !reply.approved && reply.source == FreeFlightGrantSource.NONE && reply.reason == FreeFlightAdmissionReason.DEVICE_IDENTITY_REQUIRED && reply.remainingUses == null)
            synchronized(lock) {
                if (state.terminal != null) return
                if (state.deviceProofRequested) throw IOException("Device proof was requested twice")
                state.deviceProofRequested = true
            }
            val actual = withContext(io) { devices.capture() }
            val bound = journal.bindDevice(entry.operationId, actual)
            synchronized(lock) { if (state.terminal != null) return; state.device = actual }
            submit(state, bound, actual)
        } catch (_: Exception) { uncertain(state, "需要真实设备额度，但设备身份或授权回复暂时不可用") }
    }

    private suspend fun makePending(state: Running) {
        try {
            val entry = synchronized(lock) {
                if (state.terminal != null) return
                journal.update(state.operationId) { before ->
                    if (before.decision != null) before else before.copy(pendingReceiptId = before.pendingReceiptId ?: UUID.randomUUID().toString(), pendingActive = true)
                }.also {
                    if (it.decision == null) publishOwner(state.owner, it.sessionId, it.operationId, FreeFlightAdmissionStage.PENDING, receipt = it.pendingReceiptId)
                }
            }
            if (entry.decision != null) return
            emitAll(state, FreeFlightAdmissionEvent.Pending(requireNotNull(entry.pendingReceiptId)))
        } catch (_: Exception) { uncertain(state, "待确认记录保存失败，请查询本次授权结果") }
    }

    private fun decide(state: Running, reply: FreeFlightFormalReply) {
        try {
            val before = journal.read(state.operationId)
            require(validDecision(before, reply)) { "Admission binding or decision is invalid" }
            val entry = synchronized(lock) {
                journal.update(state.operationId) { old ->
                    if (old.decision != null && old.decision != reply) throw IOException("A durable decision cannot be replaced")
                    old.copy(decision = reply, pendingActive = false)
                }
            }
            deliverDecision(state, entry, reply)
        } catch (_: Exception) { uncertain(state, "授权回复与本次飞行不匹配或无法保存，请重试查询") }
    }

    private fun deliverDecision(state: Running, entry: FreeFlightAdmissionEntry, reply: FreeFlightFormalReply) {
        val event = FreeFlightAdmissionEvent.Decided(if (reply.approved) FreeFlightAdmissionResult.Accepted(remoteRecordId = null)
            else FreeFlightAdmissionResult.Rejected(message(reply.reason)))
        val listeners = synchronized(lock) {
            if (state.terminal != null) return
            state.terminal = event; state.timer?.cancel()
            state.listeners.toList().also { state.listeners.clear() }
        }
        publishOwner(state.owner, entry.sessionId, entry.operationId, if (reply.approved) FreeFlightAdmissionStage.APPROVED else FreeFlightAdmissionStage.REJECTED,
            source = reply.source, remaining = reply.remainingUses, receipt = entry.pendingReceiptId, reason = reply.reason.name)
        listeners.forEach { emitSafely(it, event) }
        if (!reply.approved || entry.localStartCommittedAtMillis != null || entry.failureAcknowledged != null) clearCredentials(entry.operationId)
    }

    private suspend fun query(state: Running) {
        val admitted = synchronized(lock) { if (state.queryRunning || state.terminal != null) false else { state.queryRunning = true; true } }
        if (!admitted) return
        try {
            val secrets = synchronized(lock) { state.identity?.let { identity -> identity to state.device } } ?: return
            val reply = gateway.operation(FreeFlightOperationRequest(secrets.second?.hwid, secrets.second?.credential, state.operationId), secrets.first.bearer)
            if (reply.found && reply.decision != null) decide(state, requireNotNull(reply.decision))
            else uncertain(state, "后台尚未确认本次授权，请稍后查询")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { uncertain(state, "暂时无法查询本次授权结果，请稍后重试") }
        finally { synchronized(lock) { state.queryRunning = false } }
    }

    private suspend fun sendLocalFailure(state: Running, entry: FreeFlightAdmissionEntry) {
        try {
            val secrets = synchronized(lock) { state.identity?.let { identity -> identity to state.device } } ?: return
            val reply = gateway.localStartFailed(FreeFlightLocalFailureRequest(secrets.second?.hwid, secrets.second?.credential,
                entry.operationId, entry.sessionId, entry.requestFingerprint), secrets.first.bearer)
            require(reply.operationId == entry.operationId && !reply.refunded && reply.recordedAtMillis >= 0)
            journal.update(entry.operationId) { old ->
                if (old.failureAcknowledged != null && old.failureAcknowledged != reply) throw IOException("Failure reconciliation receipt changed")
                old.copy(failureAcknowledged = reply)
            }
            publishOwner(state.owner, entry.sessionId, entry.operationId, FreeFlightAdmissionStage.LOCAL_FAILURE_ACKNOWLEDGED, source = entry.decision?.source)
            clearCredentials(entry.operationId)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Durable queued=true remains; explicit reconcile retries this idempotent non-consume route. */ }
    }

    private suspend fun stateFor(entry: FreeFlightAdmissionEntry): Running? {
        if (entry.localStartCommittedAtMillis != null || entry.failureAcknowledged != null) return null
        synchronized(lock) { running[entry.operationId]?.let { return it } }
        val identity = identities.current()?.takeIf { it.owner.ownerScope == entry.ownerScope && it.owner.generation == entry.ownerGeneration && it.bearerFingerprint == entry.bearerFingerprint } ?: return null
        val state = synchronized(lock) { running.getOrPut(entry.operationId) { Running(identity, null, entry.operationId) } }
        if (entry.hwid != null && entry.decision?.source != FreeFlightGrantSource.MEMBER && !restoreDevice(state, entry)) return null
        return state
    }
    private fun uncertain(state: Running, reason: String) {
        val entry = runCatching { journal.read(state.operationId) }.getOrNull()
        synchronized(lock) {
            if (state.terminal != null) return
            publishOwner(state.owner, entry?.sessionId, state.operationId, FreeFlightAdmissionStage.UNCERTAIN, receipt = entry?.pendingReceiptId, reason = reason)
        }
        emitAll(state, FreeFlightAdmissionEvent.Uncertain(reason))
    }
    private fun emitAll(state: Running, event: FreeFlightAdmissionEvent) {
        val listeners = synchronized(lock) { if (state.terminal != null) return else state.listeners.toList() }
        listeners.forEach { emitSafely(it, event) }
    }
    private fun emitSafely(emit: (FreeFlightAdmissionEvent) -> Unit, value: FreeFlightAdmissionEvent) { runCatching { emit(value) } }
    private fun publish(identity: CapturedFreeFlightAdmissionIdentity, session: String?, operation: String?, stage: FreeFlightAdmissionStage,
        source: FreeFlightGrantSource? = null, remaining: Long? = null, receipt: String? = null, reason: String? = null) {
        publishOwner(identity.owner, session, operation, stage, source, remaining, receipt, reason)
    }
    private fun publishOwner(owner: FreeFlightOwnerToken, session: String?, operation: String?, stage: FreeFlightAdmissionStage,
        source: FreeFlightGrantSource? = null, remaining: Long? = null, receipt: String? = null, reason: String? = null) {
        val key = operation ?: "prepare:" + owner.ownerScope + ":" + owner.generation
        synchronized(lock) { observed.value = observed.value + (key to FreeFlightAdmissionObservation(owner, session, operation, stage, source, remaining, receipt, reason)) }
    }
    private fun rejectPreparation(identity: CapturedFreeFlightAdmissionIdentity, reason: String): FreeFlightPreparationResult.Rejected {
        publish(identity, null, null, FreeFlightAdmissionStage.REJECTED, reason = reason)
        return FreeFlightPreparationResult.Rejected(reason)
    }
    private fun validPreparation(reply: FreeFlightPrepareReply): Boolean {
        val remaining = reply.remainingUses
        return when {
            reply.allowed && reply.reason != FreeFlightAdmissionReason.ALLOWED -> false
            !reply.allowed && reply.reason == FreeFlightAdmissionReason.ALLOWED -> false
            reply.source == FreeFlightGrantSource.MEMBER -> reply.allowed && remaining == null
            reply.source == FreeFlightGrantSource.DEVICE -> remaining != null && remaining >= 0 && (!reply.allowed || remaining > 0)
            else -> !reply.allowed
        }
    }
    private fun validDecision(entry: FreeFlightAdmissionEntry, reply: FreeFlightFormalReply): Boolean {
        if (reply.deviceRequired || reply.reason == FreeFlightAdmissionReason.DEVICE_IDENTITY_REQUIRED ||
            reply.operationId != entry.operationId || reply.sessionId != entry.sessionId ||
            reply.requestFingerprint != entry.requestFingerprint || reply.decidedAtMillis < 0) return false
        val remaining = reply.remainingUses
        return if (reply.approved) {
            reply.reason == FreeFlightAdmissionReason.ALLOWED &&
                (reply.source == FreeFlightGrantSource.MEMBER && remaining == null ||
                    reply.source == FreeFlightGrantSource.DEVICE && entry.hwid != null && remaining != null && remaining >= 0)
        } else {
            entry.hwid != null && reply.reason != FreeFlightAdmissionReason.ALLOWED &&
                reply.source != FreeFlightGrantSource.MEMBER && (remaining == null || remaining >= 0)
        }
    }
    private fun message(reason: FreeFlightAdmissionReason): String = when (reason) {
        FreeFlightAdmissionReason.POLICY_UNCONFIGURED -> "此设备尚未配置试飞额度"
        FreeFlightAdmissionReason.DEVICE_NOT_REGISTERED -> "请重新准备以登记设备额度"
        FreeFlightAdmissionReason.DEVICE_DISABLED -> "此设备暂时无法使用试飞额度"
        FreeFlightAdmissionReason.EXHAUSTED -> "此设备的试飞额度已用完"
        FreeFlightAdmissionReason.ALLOWED -> "可以开始飞行"
        FreeFlightAdmissionReason.DEVICE_IDENTITY_REQUIRED -> "需要实际设备的试飞额度"
    }
    companion object { const val PREPARATION_TIMEOUT_MILLIS = 8_000L; const val FORMAL_PENDING_MILLIS = 8_000L }
}
