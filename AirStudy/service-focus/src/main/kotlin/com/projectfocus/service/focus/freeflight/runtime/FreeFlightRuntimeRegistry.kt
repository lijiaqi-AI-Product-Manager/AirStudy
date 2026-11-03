package com.projectfocus.service.focus.freeflight.runtime

import com.projectfocus.core.model.flight.freeflight.FreeFlightArchive
import com.projectfocus.core.model.flight.freeflight.FreeFlightStatus
import com.projectfocus.service.focus.freeflight.FreeFlightOwnerToken
import com.projectfocus.service.focus.freeflight.FreeFlightSessionRepository
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class FreeFlightRuntimeKey(val sessionId: String, val owner: FreeFlightOwnerToken, val driverId: String)
enum class FreeFlightDriverState { REQUESTED, RUNNING, STOP_REQUESTED, STOPPED, FAILED }
enum class FreeFlightShieldState { CHECKING, ACTIVE, DISABLED, MISSING_USAGE, MISSING_OVERLAY, RULES_FAILED, FAILED }
data class FreeFlightRuntimeObservation(
    val key: FreeFlightRuntimeKey? = null,
    val state: FreeFlightDriverState = FreeFlightDriverState.STOPPED,
    val foregroundConfirmed: Boolean = false,
    val dispatching: Boolean = false,
    val shielding: FreeFlightShieldState = FreeFlightShieldState.CHECKING,
    val detail: String? = null,
)

/** Intents only look up this actual in-process registration; they never mint an owner. */
class FreeFlightRuntimeTarget internal constructor(
    val key: FreeFlightRuntimeKey,
    val archive: FreeFlightArchive,
    val repository: FreeFlightSessionRepository,
    val arrival: FreeFlightArrivalCoordinator,
)

/** Created only after the actual Service root Job and every child finished. */
class FreeFlightStoppedDriver internal constructor(
    val key: FreeFlightRuntimeKey, private val lifetime: Job, private val isLatest: () -> Boolean,
) {
    fun requireStopped() {
        check(lifetime.isCompleted) { "The actual runtime jobs have not finished" }
        check(isLatest()) { "A newer actual driver replaced this stopped proof" }
    }
}

class FreeFlightRuntimePending(message: String) : IllegalStateException(message)

/** The actual Application owns cleanup and confirmations, independently of Service/UI Jobs. */
class FreeFlightRuntimeRegistry(private val scope: CoroutineScope, private val owners: FreeFlightRuntimeOwners) {
    private val lock = Any()
    private val mutableTarget = MutableStateFlow<FreeFlightRuntimeTarget?>(null)
    val target: StateFlow<FreeFlightRuntimeTarget?> = mutableTarget.asStateFlow()
    private val mutableObservation = MutableStateFlow(FreeFlightRuntimeObservation())
    val observation = mutableObservation.asStateFlow()
    private class Driver(val key: FreeFlightRuntimeKey, val lifetime: Job, var closing: Boolean = false)
    private var driver: Driver? = null
    private var stoppedProof: FreeFlightStoppedDriver? = null
    private var confirmation: Pair<Pair<FreeFlightRuntimeKey, Boolean>, Job>? = null
    private val arrivalRetry = AtomicLong(0L)

    init {
        scope.launch {
            owners.invalidations.collect { owner ->
                val proof = synchronized(lock) {
                    stoppedProof?.takeIf { it.key.owner == owner && target.value?.key == it.key && driver == null }
                }
                if (proof != null) reconcileStopped(proof)
            }
        }
    }

    fun stage(archive: FreeFlightArchive, owner: FreeFlightOwnerToken, repository: FreeFlightSessionRepository,
              arrival: FreeFlightArrivalCoordinator): FreeFlightRuntimeTarget = synchronized(lock) {
        require(archive.ownerScope == owner.ownerScope)
        val old = mutableTarget.value
        val same = old != null && old.key.sessionId == archive.sessionId && old.key.owner == owner
        val observed = mutableObservation.value
        check(old == null || same || (driver == null && observed.state in setOf(FreeFlightDriverState.STOPPED, FreeFlightDriverState.FAILED))) {
            "The previous actual driver is still active or closing"
        }
        if (same) require(archive.revision >= requireNotNull(old).archive.revision)
        val terminal = archive.status != FreeFlightStatus.ONGOING
        val needsNew = !same || (!terminal && driver == null && observed.state in setOf(FreeFlightDriverState.STOPPED, FreeFlightDriverState.FAILED))
        val key = if (needsNew) FreeFlightRuntimeKey(archive.sessionId, owner, UUID.randomUUID().toString()) else requireNotNull(old).key
        val desired = FreeFlightRuntimeTarget(key, archive, repository, arrival)
        mutableTarget.value = desired
        if (needsNew) {
            stoppedProof = null
            mutableObservation.value = FreeFlightRuntimeObservation(key, if (terminal) FreeFlightDriverState.STOP_REQUESTED else FreeFlightDriverState.REQUESTED)
            watchConfirmation(key, terminal)
        } else if (terminal && observed.state != FreeFlightDriverState.STOPPED) {
            mutableObservation.value = observed.copy(state = FreeFlightDriverState.STOP_REQUESTED)
            watchConfirmation(key, true)
        }
        desired
    }

    fun resolve(sessionId: String?, driverId: String?): FreeFlightRuntimeTarget? = target.value?.takeIf {
        it.key.sessionId == sessionId && it.key.driverId == driverId
    }
    fun isRunning(key: FreeFlightRuntimeKey) = observation.value.let {
        it.key == key && it.state == FreeFlightDriverState.RUNNING && it.foregroundConfirmed && it.dispatching
    }
    fun isStopped(key: FreeFlightRuntimeKey) = observation.value.let {
        it.key == key && it.state == FreeFlightDriverState.STOPPED && !it.foregroundConfirmed && !it.dispatching
    }

    /** Registers the real Service supervisor before any of its drivers can be launched. */
    fun attachDriver(key: FreeFlightRuntimeKey, lifetime: Job): Boolean = synchronized(lock) {
        if (target.value?.key != key || !lifetime.isActive) return false
        driver?.let { return it.key == key && it.lifetime === lifetime && !it.closing }
        // A delayed old Intent cannot resurrect a previously stopped driver/nonce.
        if (stoppedProof?.key == key || observation.value.state == FreeFlightDriverState.STOPPED) return false
        driver = Driver(key, lifetime)
        true
    }

    fun driverReady(key: FreeFlightRuntimeKey) {
        val desired = synchronized(lock) {
            val actual = driver?.takeIf { it.key == key && !it.closing && it.lifetime.isActive } ?: return
            val desired = target.value?.takeIf { it.key == actual.key && it.archive.status == FreeFlightStatus.ONGOING } ?: return
            confirmation?.second?.cancel(); confirmation = null
            mutableObservation.value = observation.value.copy(key = key, state = FreeFlightDriverState.RUNNING,
                foregroundConfirmed = true, dispatching = true, detail = null)
            desired
        }
        confirmOutsideLease(desired)
    }

    /** onDestroy initiates proof; STOPPED is published after join on the Application scope. */
    fun driverDestroyed(key: FreeFlightRuntimeKey, lifetime: Job, reason: String) {
        val actual = synchronized(lock) {
            val actual = driver?.takeIf { it.key == key && it.lifetime === lifetime && !it.closing } ?: return
            actual.closing = true
            mutableObservation.value = observation.value.copy(state = FreeFlightDriverState.STOP_REQUESTED,
                foregroundConfirmed = false, dispatching = false, detail = reason)
            actual
        }
        scope.launch {
            // Never join on Android Main or while holding repository/account/Core leases.
            actual.lifetime.join()
            lateinit var proof: FreeFlightStoppedDriver
            proof = FreeFlightStoppedDriver(key, actual.lifetime) {
                synchronized(lock) { stoppedProof === proof && target.value?.key == key && driver == null }
            }
            synchronized(lock) {
                if (driver !== actual || target.value?.key != key) return@launch
                driver = null
                stoppedProof = proof
                confirmation?.second?.cancel(); confirmation = null
                mutableObservation.value = observation.value.copy(state = FreeFlightDriverState.STOPPED,
                    foregroundConfirmed = false, dispatching = false, detail = reason)
            }
            reconcileStopped(proof)
        }
    }

    fun failure(key: FreeFlightRuntimeKey, error: Throwable) = synchronized(lock) {
        if (target.value?.key == key) mutableObservation.value = observation.value.copy(state = FreeFlightDriverState.FAILED,
            detail = error.message ?: error.javaClass.simpleName)
    }
    fun shield(key: FreeFlightRuntimeKey, state: FreeFlightShieldState, detail: String? = null) = synchronized(lock) {
        if (target.value?.key == key) mutableObservation.value = observation.value.copy(shielding = state, detail = detail)
    }
    fun arrivalFailure(key: FreeFlightRuntimeKey, error: Throwable) = synchronized(lock) {
        if (target.value?.key == key) mutableObservation.value = observation.value.copy(detail = "Arrival callback failed: ${error.message}")
    }
    fun retryArrival(key: FreeFlightRuntimeKey): Boolean = synchronized(lock) {
        if (target.value?.key != key || !isRunning(key)) false else { arrivalRetry.incrementAndGet(); true }
    }
    fun arrivalRetryGeneration() = arrivalRetry.get()

    private suspend fun reconcileStopped(proof: FreeFlightStoppedDriver) {
        try {
            proof.requireStopped()
            val retired = owners.retireStopped(proof)
            val desired = synchronized(lock) { target.value?.takeIf { it.key == proof.key } }
            if (!retired && desired != null && desired.archive.status != FreeFlightStatus.ONGOING) confirmOutsideLease(desired)
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { failure(proof.key, error) }
    }

    private fun confirmOutsideLease(target: FreeFlightRuntimeTarget) {
        scope.launch {
            try { target.repository.synchronizeRuntime(target.key.owner) }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { failure(target.key, error) }
        }
    }
    private fun watchConfirmation(key: FreeFlightRuntimeKey, terminal: Boolean) {
        val identity = key to terminal
        if (confirmation?.first == identity && confirmation?.second?.isActive == true) return
        confirmation?.second?.cancel()
        confirmation = identity to scope.launch {
            delay(5_000L)
            if (observation.value.key == key && (if (terminal) !isStopped(key) else !isRunning(key)))
                failure(key, IllegalStateException(if (terminal) "Service stop was not confirmed" else "Service foreground start was not confirmed"))
        }
    }
}
