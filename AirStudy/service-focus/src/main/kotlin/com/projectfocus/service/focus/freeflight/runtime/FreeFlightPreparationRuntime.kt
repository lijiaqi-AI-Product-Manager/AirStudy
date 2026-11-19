package com.projectfocus.service.focus.freeflight.runtime

import com.projectfocus.service.focus.freeflight.FreeFlightOwnerToken
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FreeFlightPreparationRuntimeKey internal constructor(
    val preparationId: String, val sessionId: String, val owner: FreeFlightOwnerToken, val driverId: String,
)
enum class FreeFlightPreparationDriverState {
    REQUESTED, FOREGROUND, STOP_REQUESTED, STOPPED, CANCELLED_UNATTACHED, HANDED_OFF, FAILED,
}
data class FreeFlightPreparationObservation(
    val key: FreeFlightPreparationRuntimeKey? = null,
    val state: FreeFlightPreparationDriverState = FreeFlightPreparationDriverState.STOPPED,
    val foregroundConfirmed: Boolean = false,
    val detail: String? = null,
)

/** One in-process registration for the preparation phase of the SAME actual Service. It does not
 * reserve a flight, write an archive, debit admission, publish timer readiness or retire a slot. */
class FreeFlightPreparationRuntime(private val scope: CoroutineScope) {
    private class Driver(val key: FreeFlightPreparationRuntimeKey, val lifetime: Job, var closing: Boolean = false)
    private val lock = Any()
    private val mutableObservation = MutableStateFlow(FreeFlightPreparationObservation())
    val observation = mutableObservation.asStateFlow()
    private var driver: Driver? = null

    fun stage(preparationId: String, sessionId: String, owner: FreeFlightOwnerToken): FreeFlightPreparationRuntimeKey = synchronized(lock) {
        require(preparationId.isNotBlank() && sessionId.isNotBlank())
        val previous = observation.value
        val same = previous.key?.let { it.preparationId == preparationId && it.sessionId == sessionId && it.owner == owner } == true
        if (same && previous.state in ACTIVE) return requireNotNull(previous.key)
        check(driver == null) { "The previous actual preparation driver is still active or joining" }
        check(previous.state !in ACTIVE && previous.state != FreeFlightPreparationDriverState.STOP_REQUESTED) {
            "The previous preparation has not been cancelled"
        }
        check(!same || previous.state != FreeFlightPreparationDriverState.HANDED_OFF) { "This preparation already belongs to the flight driver" }
        val key = FreeFlightPreparationRuntimeKey(preparationId, sessionId, owner, UUID.randomUUID().toString())
        mutableObservation.value = FreeFlightPreparationObservation(key, FreeFlightPreparationDriverState.REQUESTED)
        key
    }

    fun resolve(sessionId: String?, driverId: String?): FreeFlightPreparationRuntimeKey? = synchronized(lock) {
        observation.value.takeIf { it.state in ACTIVE }?.key?.takeIf { it.sessionId == sessionId && it.driverId == driverId }
    }

    fun attach(key: FreeFlightPreparationRuntimeKey, lifetime: Job): Boolean = synchronized(lock) {
        if (observation.value.key != key || observation.value.state !in ACTIVE || !lifetime.isActive) return false
        driver?.let { return it.key == key && it.lifetime === lifetime && !it.closing }
        driver = Driver(key, lifetime)
        true
    }

    fun canPromote(key: FreeFlightPreparationRuntimeKey) = synchronized(lock) {
        observation.value.key == key && observation.value.state in ACTIVE &&
            driver?.let { it.key == key && !it.closing && it.lifetime.isActive } == true
    }

    fun foreground(key: FreeFlightPreparationRuntimeKey) = synchronized(lock) {
        if (canPromote(key)) mutableObservation.value = observation.value.copy(
            state = FreeFlightPreparationDriverState.FOREGROUND, foregroundConfirmed = true, detail = null)
    }

    fun requestStop(preparationId: String, sessionId: String, owner: FreeFlightOwnerToken) = synchronized(lock) {
        val value = observation.value
        if (value.key?.let { it.preparationId == preparationId && it.sessionId == sessionId && it.owner == owner } != true ||
            value.state == FreeFlightPreparationDriverState.HANDED_OFF) return@synchronized
        mutableObservation.value = value.copy(state = if (driver == null) FreeFlightPreparationDriverState.CANCELLED_UNATTACHED
            else FreeFlightPreparationDriverState.STOP_REQUESTED)
    }

    fun failure(key: FreeFlightPreparationRuntimeKey, error: Throwable) = synchronized(lock) {
        if (observation.value.key == key && observation.value.state != FreeFlightPreparationDriverState.HANDED_OFF)
            mutableObservation.value = observation.value.copy(state = FreeFlightPreparationDriverState.FAILED,
                detail = error.message ?: error.javaClass.simpleName)
    }

    /** The Service has joined every preparation child; its root is transferred, never called stopped. */
    fun handOff(key: FreeFlightPreparationRuntimeKey, lifetime: Job, flight: FreeFlightRuntimeKey): Boolean = synchronized(lock) {
        if (key.owner != flight.owner || key.sessionId != flight.sessionId || observation.value.key != key ||
            observation.value.state !in ACTIVE || !lifetime.isActive) return false
        val actual = driver ?: return false
        if (actual.key != key || actual.lifetime !== lifetime || actual.closing) return false
        driver = null
        mutableObservation.value = observation.value.copy(state = FreeFlightPreparationDriverState.HANDED_OFF, foregroundConfirmed = false)
        true
    }

    /** STOPPED follows the actual root/children join on the Application, never mere cancellation. */
    fun destroyed(key: FreeFlightPreparationRuntimeKey, lifetime: Job) {
        val actual = synchronized(lock) {
            val actual = driver?.takeIf { it.key == key && it.lifetime === lifetime && !it.closing } ?: return
            actual.closing = true
            mutableObservation.value = observation.value.copy(state = FreeFlightPreparationDriverState.STOP_REQUESTED, foregroundConfirmed = false)
            actual
        }
        scope.launch {
            actual.lifetime.join()
            synchronized(lock) {
                if (driver !== actual || observation.value.key != key) return@launch
                driver = null
                mutableObservation.value = observation.value.copy(state = FreeFlightPreparationDriverState.STOPPED, foregroundConfirmed = false)
            }
        }
    }

    companion object {
        private val ACTIVE = setOf(FreeFlightPreparationDriverState.REQUESTED, FreeFlightPreparationDriverState.FOREGROUND)
    }
}
