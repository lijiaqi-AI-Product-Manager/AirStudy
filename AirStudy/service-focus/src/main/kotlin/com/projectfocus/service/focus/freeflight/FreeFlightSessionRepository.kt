package com.projectfocus.service.focus.freeflight

import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.freeflight.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max

data class FreeFlightOwnerToken(val ownerScope: String, val generation: Long) {
    init { require(ownerScope.isNotBlank() && generation >= 0L) }
}

sealed interface FreeFlightAdmissionResult {
    data class Accepted(val remoteRecordId: String?) : FreeFlightAdmissionResult
    data class Rejected(val reason: String) : FreeFlightAdmissionResult
}

/** Preparation may register/query device uses; it never acknowledges formal consumption. */
sealed interface FreeFlightPreparationResult {
    object Allowed : FreeFlightPreparationResult
    data class Rejected(val reason: String) : FreeFlightPreparationResult
}

interface FreeFlightPreparationAdmission {
    suspend fun check(owner: FreeFlightOwnerToken): FreeFlightPreparationResult
}

sealed interface FreeFlightAdmissionEvent {
    /** Actual local pending receipt from the real admission adapter, not a remote flight ID. */
    data class Pending(val receiptId: String) : FreeFlightAdmissionEvent {
        init { require(receiptId.isNotBlank()) }
    }
    /** An uncertain transport/local failure is not a definitive consume rejection. */
    data class Uncertain(val reason: String) : FreeFlightAdmissionEvent
    data class Decided(val result: FreeFlightAdmissionResult) : FreeFlightAdmissionEvent
}

data class FreeFlightStartIdentity(val owner: FreeFlightOwnerToken, val sessionId: String)

sealed interface FreeFlightStartState {
    object Idle : FreeFlightStartState
    data class Checking(val identity: FreeFlightStartIdentity) : FreeFlightStartState
    data class Pending(val identity: FreeFlightStartIdentity, val receiptId: String) : FreeFlightStartState
    data class Uncertain(val identity: FreeFlightStartIdentity, val reason: String) : FreeFlightStartState
    data class ReconciliationRequired(val identity: FreeFlightStartIdentity, val error: Throwable) : FreeFlightStartState
    data class Finished(
        val identity: FreeFlightStartIdentity,
        val result: FreeFlightCommandResult,
        /** A local failure report was durably recorded; this does not mean a refund. */
        val reconciliationRecorded: Boolean,
    ) : FreeFlightStartState
}

/** No accepting implementation is supplied. This port performs ONLY actual formal admission. */
interface FreeFlightAdmission {
    /**
     * Start one asynchronous real operation and retain its callback after this method returns.
     * Emit source-style Pending at the real eight-second pending boundary, with its recorded
     * receipt, and then the real definitive result. Pending must neither retry consume nor
     * become Rejected. Callbacks may run on any thread. The first definitive result closes
     * the Own operation. An exception alone is uncertain; a later real callback still works.
     * This method and its callback must be owned by the application, never a screen Job.
     */
    fun request(
        owner: FreeFlightOwnerToken,
        request: FreeFlightStartRequest,
        emit: (FreeFlightAdmissionEvent) -> Unit,
    )
    /**
     * Return only after the accepted-but-not-started failure has been durably recorded
     * for actual reconciliation. Idempotent by captured session/owner. No refund is implied.
     */
    suspend fun localStartFailed(request: FreeFlightStartRequest, accepted: FreeFlightAdmissionResult.Accepted)
    /** Durable local success ACK; called after archive persistence and after exiting the Core lease. */
    suspend fun localStartCommitted(request: FreeFlightStartRequest, accepted: FreeFlightAdmissionResult.Accepted)
}

sealed interface FreeFlightStartAcknowledgement {
    data class Required(val identity: FreeFlightStartIdentity, val error: Throwable? = null) : FreeFlightStartAcknowledgement
    data class Recorded(val identity: FreeFlightStartIdentity) : FreeFlightStartAcknowledgement
}

/** The countdown repository and the mode-1 host must use this same reservation owner. */
interface FlightSessionOwnership {
    suspend fun acquire(sessionId: String, owner: FreeFlightOwnerToken): Boolean
    suspend fun release(sessionId: String, owner: FreeFlightOwnerToken)
    /**
     * Run under the real shared reservation/current account+host generation lease, or return
     * null if obsolete. Hold that lease through action; a check followed by unlock is not enough.
     * Countdown and free-flight adapters must use the same owner for this exclusion.
     */
    suspend fun <T : Any> withCurrentOwner(
        sessionId: String, owner: FreeFlightOwnerToken, action: suspend () -> T,
    ): T?
}

/** Must call the real HMS/source distance path for the exact endpoints, in kilometres. */
fun interface FreeFlightLandingDistance {
    suspend fun kilometres(start: FreeFlightPoint, destination: FreeFlightPoint): Double
}

/** Real service/shielding/widget/arrival adapters, idempotent by session and revision. */
fun interface FreeFlightRuntimeEffects {
    suspend fun synchronize(archive: FreeFlightArchive)
}

data class FreeFlightLandingSearch(
    val sessionId: String,
    val generation: Long,
    val center: FreeFlightPoint,
    val rangeMinutes: Int,
    val queryRadiusKm: Int,
)

sealed interface FreeFlightCommandResult {
    data class Committed(
        val archive: FreeFlightArchive,
        val landingReached: Boolean,
        val runtimeSynchronized: Boolean,
        val runtimeFailure: Throwable? = null,
    ) : FreeFlightCommandResult
    data class Rejected(val reason: String) : FreeFlightCommandResult
    data class Failed(val error: Throwable) : FreeFlightCommandResult
}

/**
 * Candidate mode-1 service boundary. Nothing auto-starts from a lab preference.
 * Commands commit the real archive before publication. There is deliberately no
 * Hilt constructor that manufactures missing admission, reservation or service adapters.
 * Bind one application-owned instance; start-attempt state is not a screen object.
 */
class FreeFlightSessionRepository(
    private val store: FreeFlightArchiveStore,
    private val time: FreeFlightTimeSource,
    private val admission: FreeFlightAdmission,
    private val ownership: FlightSessionOwnership,
    private val distance: FreeFlightLandingDistance,
    private val effects: FreeFlightRuntimeEffects,
    private val mapPresence: FreeFlightMapPresence,
    /** Required application lifetime owner, not viewModelScope/lifecycleScope. */
    private val admissionScope: CoroutineScope,
    private val engine: FreeFlightEngine = FreeFlightEngine(),
) {
    private val mutex = Mutex()
    private val arrivalClock = FreeFlightArrivalClockGate(time)
    private val returnClock = FreeFlightReturnClockGate(time)
    private val mutableState = MutableStateFlow<FreeFlightArchive?>(null)
    val state: StateFlow<FreeFlightArchive?> = mutableState.asStateFlow()
    @Volatile private var ownerToken: FreeFlightOwnerToken? = null
    private val startLock = Any()
    private var activeStart: StartAttempt? = null
    private val mutableStartState = MutableStateFlow<FreeFlightStartState>(FreeFlightStartState.Idle)
    val startState: StateFlow<FreeFlightStartState> = mutableStartState.asStateFlow()
    private val acknowledgementMutex = Mutex()
    private class StartAcknowledgement(val identity: FreeFlightStartIdentity, val request: FreeFlightStartRequest, val accepted: FreeFlightAdmissionResult.Accepted)
    private val pendingAcknowledgements = mutableMapOf<FreeFlightStartIdentity, StartAcknowledgement>()
    private val mutableAcknowledgements = MutableStateFlow<Map<FreeFlightStartIdentity, FreeFlightStartAcknowledgement>>(emptyMap())
    val startAcknowledgements: StateFlow<Map<FreeFlightStartIdentity, FreeFlightStartAcknowledgement>> = mutableAcknowledgements.asStateFlow()

    private class StartAttempt(val owner: FreeFlightOwnerToken, val request: FreeFlightStartRequest) {
        val identity = FreeFlightStartIdentity(owner, request.sessionId)
        val events = Channel<FreeFlightAdmissionEvent>(Channel.UNLIMITED)
        // No caller Job parent: cancelling a screen await does not cancel the owned operation.
        val completion = CompletableDeferred<FreeFlightCommandResult>()
        var accepted: FreeFlightAdmissionResult.Accepted? = null
        var reserved = false
        var failureRecorded = false
        var reconciliationRequired = false
        var failure: Throwable? = null
    }

    /**
     * Caller cancellation detaches only its wait. The owned operation retains callbacks,
     * pending receipt and shared runtime reservation until a definitive result/recorded failure.
     * startState exposes Checking/Pending independently of any screen awaiting this method.
     */
    suspend fun start(owner: FreeFlightOwnerToken, request: FreeFlightStartRequest): FreeFlightCommandResult {
        if (request.ownerScope != owner.ownerScope)
            return FreeFlightCommandResult.Rejected("The request belongs to another owner")
        val attempt = synchronized(startLock) {
            if (activeStart != null) return FreeFlightCommandResult.Rejected("Admission is already pending or requires reconciliation")
            if (!admissionScope.isActive) return FreeFlightCommandResult.Rejected("The application admission owner is inactive")
            StartAttempt(owner, request).also {
                activeStart = it
                mutableStartState.value = FreeFlightStartState.Checking(it.identity)
            }
        }
        admissionScope.launch(start = CoroutineStart.UNDISPATCHED) {
            // Never turn caller/scope cancellation during an uncertain consume into release.
            // Process-death recovery still requires a real durable admission adapter.
            withContext(NonCancellable) {
                val result = try { guarded { startOwned(attempt) } }
                catch (cancel: CancellationException) { FreeFlightCommandResult.Failed(cancel) }
                if (result is FreeFlightCommandResult.Failed) attempt.failure = result.error
                if (!attempt.reconciliationRequired) {
                    synchronized(startLock) {
                        if (activeStart === attempt) {
                            activeStart = null
                            mutableStartState.value = FreeFlightStartState.Finished(attempt.identity, result, attempt.failureRecorded)
                        }
                    }
                }
                attempt.events.close()
                attempt.completion.complete(result)
            }
        }
        return attempt.completion.await()
    }

    /** Retry only recording/releasing a failed start, never submit another consume. */
    suspend fun retryFailedStartReconciliation(owner: FreeFlightOwnerToken, sessionId: String): Boolean = mutex.withLock {
        val attempt = synchronized(startLock) { activeStart } ?: return@withLock false
        if (attempt.owner != owner || attempt.request.sessionId != sessionId || !attempt.reconciliationRequired)
            return@withLock false
        withContext(NonCancellable) {
            try {
                recordFailureAndRelease(attempt)
                attempt.reconciliationRequired = false
                synchronized(startLock) {
                    if (activeStart === attempt) {
                        activeStart = null
                        mutableStartState.value = FreeFlightStartState.Finished(
                            attempt.identity,
                            FreeFlightCommandResult.Failed(requireNotNull(attempt.failure)),
                            attempt.failureRecorded,
                        )
                    }
                }
                true
            } catch (error: Exception) {
                mutableStartState.value = FreeFlightStartState.ReconciliationRequired(attempt.identity, error)
                false
            }
        }
    }

    private suspend fun startOwned(attempt: StartAttempt): FreeFlightCommandResult {
        val owner = attempt.owner
        val request = attempt.request
        if (mutableState.value?.status == FreeFlightStatus.ONGOING || store.active() != null)
            return FreeFlightCommandResult.Rejected("A flight archive is already active")
        if (store.load(request.sessionId, request.ownerScope) != null)
            return FreeFlightCommandResult.Rejected("This session ID already has an archive")
        if (!ownership.acquire(request.sessionId, owner))
            return FreeFlightCommandResult.Rejected("Another flight owns the runtime")
        attempt.reserved = true
        var committed = false
        try {
            val permit = when (val decision = awaitFormalAdmission(attempt)) {
                is FreeFlightAdmissionResult.Rejected -> return FreeFlightCommandResult.Rejected(decision.reason)
                is FreeFlightAdmissionResult.Accepted -> decision
            }
            attempt.accepted = permit
            return ownership.withCurrentOwner(request.sessionId, owner) {
                val initial = engine.start(request, reading()).copy(remoteRecordId = permit.remoteRecordId)
                commit(null, initial, owner) { committed = true }
            } ?: throw IllegalStateException("The accepted start belongs to an obsolete runtime owner")
        } finally {
            if (committed) withContext(NonCancellable) {
                // The archive is already real. ACK failure cannot turn that start into a failure,
                // release its runtime, or send accepted-but-unstarted failure reconciliation.
                acknowledgeStarted(StartAcknowledgement(attempt.identity, request, requireNotNull(attempt.accepted)))
            } else withContext(NonCancellable) {
                try { recordFailureAndRelease(attempt) }
                catch (error: Exception) {
                    attempt.reconciliationRequired = true
                    mutableStartState.value = FreeFlightStartState.ReconciliationRequired(attempt.identity, error)
                    throw IllegalStateException("The failed start still requires recorded reconciliation/runtime release", error)
                }
            }
        }
    }

    /** Retry only the exact local success ACK. Never calls formal admission or Core commands. */
    suspend fun retryStartAcknowledgement(owner: FreeFlightOwnerToken, sessionId: String): Boolean = withContext(NonCancellable) {
        acknowledgementMutex.withLock {
            val saved = pendingAcknowledgements[FreeFlightStartIdentity(owner, sessionId)] ?: return@withLock false
            recordStartedAcknowledgement(saved)
        }
    }

    private suspend fun acknowledgeStarted(value: StartAcknowledgement): Boolean = acknowledgementMutex.withLock {
        pendingAcknowledgements[value.identity] = value
        mutableAcknowledgements.value = mutableAcknowledgements.value + (value.identity to FreeFlightStartAcknowledgement.Required(value.identity))
        recordStartedAcknowledgement(value)
    }

    private suspend fun recordStartedAcknowledgement(value: StartAcknowledgement): Boolean {
        return try {
            admission.localStartCommitted(value.request, value.accepted)
            pendingAcknowledgements.remove(value.identity)
            mutableAcknowledgements.value = mutableAcknowledgements.value + (value.identity to FreeFlightStartAcknowledgement.Recorded(value.identity))
            true
        } catch (error: Exception) {
            mutableAcknowledgements.value = mutableAcknowledgements.value + (value.identity to FreeFlightStartAcknowledgement.Required(value.identity, error))
            false
        }
    }

    private suspend fun awaitFormalAdmission(attempt: StartAttempt): FreeFlightAdmissionResult {
        try {
            admission.request(attempt.owner, attempt.request) { event -> attempt.events.trySend(event) }
        } catch (error: Exception) {
            // The adapter may already have dispatched the request before throwing.
            // Keep its callback and reservation, instead of guessing rejected/refunded.
            mutableStartState.value = FreeFlightStartState.Uncertain(attempt.identity, error.message ?: "Admission outcome is uncertain")
        }
        while (true) when (val event = attempt.events.receive()) {
            is FreeFlightAdmissionEvent.Pending -> mutableStartState.value = FreeFlightStartState.Pending(attempt.identity, event.receiptId)
            is FreeFlightAdmissionEvent.Uncertain -> mutableStartState.value = FreeFlightStartState.Uncertain(attempt.identity, event.reason)
            is FreeFlightAdmissionEvent.Decided -> return event.result
        }
    }

    private suspend fun recordFailureAndRelease(attempt: StartAttempt) {
        attempt.accepted?.let { accepted ->
            if (!attempt.failureRecorded) {
                admission.localStartFailed(attempt.request, accepted)
                attempt.failureRecorded = true
            }
        }
        if (attempt.reserved) {
            ownership.release(attempt.request.sessionId, attempt.owner)
            attempt.reserved = false
        }
    }

    /** Own cold-restore policy is explicit: committed progress paused, no offline grant. */
    suspend fun restoreActive(owner: FreeFlightOwnerToken): FreeFlightCommandResult = guarded {
        val saved = store.active() ?: return@guarded FreeFlightCommandResult.Rejected("No active free flight")
        if (saved.ownerScope != owner.ownerScope)
            return@guarded FreeFlightCommandResult.Rejected("The archive belongs to another owner")
        if (!ownership.acquire(saved.sessionId, owner))
            return@guarded FreeFlightCommandResult.Rejected("Another flight owns the runtime")
        var committed = false
        try {
            ownership.withCurrentOwner(saved.sessionId, owner) {
                commit(saved, engine.restoreCheckpoint(saved, reading()), owner) { committed = true }
            } ?: FreeFlightCommandResult.Rejected("The restoration owner is obsolete")
        }
        finally {
            if (!committed) withContext(NonCancellable) { ownership.release(saved.sessionId, owner) }
        }
    }

    suspend fun tick(owner: FreeFlightOwnerToken): FreeFlightCommandResult = leasedCommand(owner) { saved ->
        commit(saved, engine.sample(saved, reading()), owner)
    }

    suspend fun pause(owner: FreeFlightOwnerToken): FreeFlightCommandResult = transition(owner) {
        engine.pause(it, reading())
    }

    suspend fun resume(owner: FreeFlightOwnerToken): FreeFlightCommandResult = transition(owner) {
        engine.resume(it, reading())
    }

    suspend fun prepareLanding(owner: FreeFlightOwnerToken): FreeFlightCommandResult = transition(owner) {
        engine.prepareLanding(it, reading())
    }

    suspend fun changeLandingRange(owner: FreeFlightOwnerToken, minutes: Int): FreeFlightCommandResult =
        transition(owner) { engine.changeLandingRange(it, minutes) }

    suspend fun selectLanding(
        owner: FreeFlightOwnerToken, sessionId: String, generation: Long, airport: PlannedAirport,
    ): FreeFlightCommandResult = transition(owner, sessionId) {
        engine.selectLanding(it, airport, generation)
    }

    suspend fun returnToCruise(owner: FreeFlightOwnerToken): FreeFlightCommandResult = transition(owner) {
        engine.returnToCruise(it, reading())
    }

    suspend fun prepareReturnCallback(owner: FreeFlightOwnerToken): FreeFlightReturnPermit? = mutex.withLock {
        val saved=mutableState.value ?: return@withLock null
        if (ownerToken!=owner || saved.ownerScope!=owner.ownerScope || !saved.cruiseReturnPending ||
            saved.clock.pause==FreeFlightPause.RECOVERY_REQUIRES_RESUME || saved.status!=FreeFlightStatus.ONGOING) return@withLock null
        ownership.withCurrentOwner(saved.sessionId,owner) { returnClock.prepare(owner,requireOwner(owner)) }
    }
    fun isReturnCallbackCurrent(permit:FreeFlightReturnPermit):Boolean {
        val saved=mutableState.value
        return ownerToken==permit.owner && saved?.sessionId==permit.sessionId && saved.landingSelectionGeneration==permit.generation &&
            saved.status==FreeFlightStatus.ONGOING && saved.phase==FreeFlightPhase.CRUISING && saved.cruiseReturnPending && saved.clock.pause!=FreeFlightPause.RECOVERY_REQUIRES_RESUME
    }
    fun captureReturnCallback(permit:FreeFlightReturnPermit):Boolean = returnClock.capture(permit) { isReturnCallbackCurrent(permit) }
    fun hasReturnCallback(permit:FreeFlightReturnPermit):Boolean = returnClock.captured(permit)
    suspend fun completeReturnToCruise(owner:FreeFlightOwnerToken,permit:FreeFlightReturnPermit):FreeFlightCommandResult = leasedCommand(owner,permit.sessionId) { saved ->
        val at=returnClock.capturedReading(permit,owner,saved) ?: return@leasedCommand FreeFlightCommandResult.Rejected("Actual cruise-return callback is not current/captured")
        commit(saved,engine.completeReturnToCruise(saved,at),owner)
    }

    suspend fun confirmLanding(owner: FreeFlightOwnerToken, generation: Long): FreeFlightCommandResult = leasedCommand(owner) { saved ->
        require(saved.phase == FreeFlightPhase.LANDING_SELECTION && saved.landingSelectionGeneration == generation)
        val airport = requireNotNull(saved.landingAirport)
        when (mapPresence.current(saved.sessionId, owner)) {
            FreeFlightMapAvailability.ABSENT -> return@leasedCommand commit(saved, engine.beginLandingWithoutMap(saved, generation, reading()), owner)
            FreeFlightMapAvailability.UNKNOWN -> return@leasedCommand FreeFlightCommandResult.Rejected("The actual landing map state is unknown")
            FreeFlightMapAvailability.PRESENT -> Unit
        }
        val measured = distance.kilometres(saved.position, FreeFlightPoint(airport.latitude, airport.longitude))
        commit(saved, engine.confirmLanding(saved, generation, measured, reading()), owner)
    }

    /** Register the actual callback under the real account/runtime lease, before posting UI work. */
    suspend fun prepareArrivalCallback(owner: FreeFlightOwnerToken): FreeFlightArrivalPermit? = mutex.withLock {
        val saved = mutableState.value ?: return@withLock null
        if (ownerToken != owner || saved.ownerScope != owner.ownerScope || saved.status != FreeFlightStatus.ONGOING)
            return@withLock null
        if (saved.phase != FreeFlightPhase.NO_MAP_ARRIVAL && !engine.landingReached(saved)) return@withLock null
        ownership.withCurrentOwner(saved.sessionId, owner) { arrivalClock.prepare(owner, requireOwner(owner)) }
    }

    /** Only the actual posted/camera result callback calls this; no caller-supplied timestamp. */
    fun captureArrivalCallback(permit: FreeFlightArrivalPermit): Boolean = arrivalClock.capture(permit) {
        val saved = mutableState.value
        saved != null && ownerToken == permit.owner && saved.sessionId == permit.sessionId && saved.phase == permit.phase &&
            saved.status == FreeFlightStatus.ONGOING
    }

    fun hasArrivalCallback(permit: FreeFlightArrivalPermit): Boolean = arrivalClock.captured(permit)

    suspend fun completeLanding(owner: FreeFlightOwnerToken, permit: FreeFlightArrivalPermit): FreeFlightCommandResult = leasedCommand(owner) { saved ->
        if (saved.phase != FreeFlightPhase.FINAL_APPROACH) return@leasedCommand FreeFlightCommandResult.Rejected("Final approach is not active")
        val at = arrivalClock.capturedReading(permit, owner, saved)
            ?: return@leasedCommand FreeFlightCommandResult.Rejected("The actual result callback has not been captured")
        commit(saved, engine.completeLanding(saved, at), owner)
    }

    suspend fun completeLandingWithoutMap(owner: FreeFlightOwnerToken, permit: FreeFlightArrivalPermit): FreeFlightCommandResult = leasedCommand(owner) { saved ->
        if (saved.phase != FreeFlightPhase.NO_MAP_ARRIVAL) return@leasedCommand FreeFlightCommandResult.Rejected("No-map arrival is not active")
        val at = arrivalClock.capturedReading(permit, owner, saved)
            ?: return@leasedCommand FreeFlightCommandResult.Rejected("The actual result callback has not been captured")
        commit(saved, engine.completeLandingWithoutMap(saved, at), owner)
    }

    suspend fun cancel(owner: FreeFlightOwnerToken): FreeFlightCommandResult = transition(owner) {
        engine.cancel(it, reading())
    }

    suspend fun bindRemoteRecord(
        owner: FreeFlightOwnerToken, sessionId: String, recordId: String,
    ): FreeFlightCommandResult = transition(owner, sessionId) {
        require(recordId.isNotBlank())
        require(it.remoteRecordId == null || it.remoteRecordId == recordId)
        it.copy(remoteRecordId = recordId)
    }

    /** Safe paint-only projection; this method does not persist or settle. */
    suspend fun preview(owner: FreeFlightOwnerToken): FreeFlightArchive = leasedRead(owner) {
        engine.sample(it, reading())
    }

    suspend fun landingSearch(owner: FreeFlightOwnerToken): FreeFlightLandingSearch = leasedRead(owner) { saved ->
        val radius = max(1.0, ceil(engine.landingRadiusKm(saved)))
        require(radius <= Int.MAX_VALUE.toDouble())
        FreeFlightLandingSearch(
            saved.sessionId, saved.landingSelectionGeneration, saved.position,
            requireNotNull(saved.landingRangeMinutes), radius.toInt(),
        )
    }

    /** Retry real external cleanup after a locally committed terminal result. */
    suspend fun synchronizeRuntime(owner: FreeFlightOwnerToken, sessionId: String? = null): FreeFlightCommandResult = leasedCommand(owner, sessionId, allowTerminal = true) { archive ->
        synchronize(archive, owner)
    }

    private suspend fun transition(
        owner: FreeFlightOwnerToken,
        sessionId: String? = null,
        reducer: (FreeFlightArchive) -> FreeFlightArchive,
    ): FreeFlightCommandResult = leasedCommand(owner, sessionId) { saved ->
        if (arrivalClock.hasCaptured(owner, saved))
            return@leasedCommand FreeFlightCommandResult.Rejected("The actual arrival result is awaiting persistence")
        if (returnClock.hasCaptured(owner, saved))
            return@leasedCommand FreeFlightCommandResult.Rejected("The actual cruise-return callback is awaiting persistence")
        commit(saved, reducer(saved), owner)
    }

    private suspend fun leasedCommand(
        owner: FreeFlightOwnerToken,
        sessionId: String? = null,
        allowTerminal: Boolean = false,
        action: suspend (FreeFlightArchive) -> FreeFlightCommandResult,
    ): FreeFlightCommandResult = guarded {
        withContext(NonCancellable) {
            val saved = requireOwner(owner, allowTerminal)
            require(sessionId == null || saved.sessionId == sessionId)
            val result = ownership.withCurrentOwner(saved.sessionId, owner) {
                action(requireOwner(owner, allowTerminal))
            } ?: return@withContext FreeFlightCommandResult.Rejected("The actual account/runtime owner is obsolete")
            // Core commandMutex is held by withCurrentOwner. Release only AFTER that lease
            // exits, or terminal synchronization would recursively deadlock the Core mutex.
            if (result is FreeFlightCommandResult.Committed && result.archive.status != FreeFlightStatus.ONGOING && result.runtimeSynchronized) {
                try { ownership.release(result.archive.sessionId, owner); result }
                catch (error: Exception) { result.copy(runtimeSynchronized = false, runtimeFailure = error) }
            } else result
        }
    }

    private suspend fun <T : Any> leasedRead(
        owner: FreeFlightOwnerToken,
        action: (FreeFlightArchive) -> T,
    ): T = mutex.withLock {
        val saved = requireOwner(owner)
        requireNotNull(ownership.withCurrentOwner(saved.sessionId, owner) { action(requireOwner(owner)) }) {
            "The actual account/runtime owner is obsolete"
        }
    }

    private suspend fun commit(
        previous: FreeFlightArchive?,
        proposed: FreeFlightArchive,
        owner: FreeFlightOwnerToken,
        onPersisted: () -> Unit = {},
    ): FreeFlightCommandResult = withContext(NonCancellable) {
        val sampleChanged = previous == null ||
            previous.position != proposed.position || previous.bearingDegrees != proposed.bearingDegrees ||
            previous.clock.activeElapsedMs != proposed.clock.activeElapsedMs ||
            previous.cumulativeDistanceKm != proposed.cumulativeDistanceKm || previous.status != proposed.status
        val next = proposed.copy(
            revision = previous?.let { Math.addExact(it.revision, 1L) } ?: 0L,
            sampleSequence = if (sampleChanged) Math.addExact(previous?.sampleSequence ?: 0L, 1L) else proposed.sampleSequence,
        ).validated()
        val sample = if (sampleChanged) FreeFlightPositionSample(
            next.sessionId, next.sampleSequence, next.clock.activeElapsedMs, next.position,
            next.bearingDegrees, next.cumulativeDistanceKm, next.updatedAtWallMs,
        ) else null
        if (!store.commit(previous?.revision, next, sample))
            return@withContext FreeFlightCommandResult.Rejected("Archive revision changed")
        onPersisted()
        arrivalClock.afterCommit(owner, next)
        returnClock.afterCommit(owner, next)
        ownerToken = owner
        // Publication follows both persistence and attempted runtime synchronization.
        try { synchronize(next, owner) }
        finally { mutableState.value = next }
    }

    private suspend fun synchronize(archive: FreeFlightArchive, owner: FreeFlightOwnerToken): FreeFlightCommandResult.Committed {
        return try {
            effects.synchronize(archive)
            FreeFlightCommandResult.Committed(archive, engine.landingReached(archive), true)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            FreeFlightCommandResult.Committed(archive, engine.landingReached(archive), false, error)
        }
    }

    private fun reading(): FreeFlightClockReading = returnClock.read(ownerToken,mutableState.value) { arrivalClock.read(ownerToken, mutableState.value) }

    private fun requireOwner(owner: FreeFlightOwnerToken, allowTerminal: Boolean = false): FreeFlightArchive {
        require(ownerToken == owner) { "Owner generation changed" }
        val archive = requireNotNull(mutableState.value)
        require(archive.ownerScope == owner.ownerScope)
        require(allowTerminal || archive.status == FreeFlightStatus.ONGOING)
        return archive
    }

    private suspend fun guarded(action: suspend () -> FreeFlightCommandResult): FreeFlightCommandResult =
        mutex.withLock {
            try { action() }
            catch (error: CancellationException) { throw error }
            catch (error: IllegalArgumentException) { FreeFlightCommandResult.Rejected(error.message.orEmpty()) }
            catch (error: Exception) { FreeFlightCommandResult.Failed(error) }
        }
}
