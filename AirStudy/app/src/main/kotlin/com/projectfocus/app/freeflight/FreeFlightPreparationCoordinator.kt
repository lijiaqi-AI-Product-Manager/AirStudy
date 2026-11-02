package com.projectfocus.app.freeflight

import com.projectfocus.core.common.seat.SeatMapGeometry
import com.projectfocus.core.model.flight.freeflight.FreeFlightScene
import com.projectfocus.core.model.flight.freeflight.FreeFlightStartRequest
import com.projectfocus.feature.booking.seat.SeatSceneChoice
import com.projectfocus.service.focus.freeflight.FreeFlightCommandResult
import com.projectfocus.service.focus.freeflight.FreeFlightStartIdentity
import com.projectfocus.service.focus.freeflight.FreeFlightStartState
import com.projectfocus.service.focus.freeflight.runtime.FreeFlightDriverState
import com.projectfocus.service.focus.freeflight.runtime.FreeFlightRuntimeObservation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

enum class FreeFlightPreparationStep {
    IDLE, CHECKING, HEADING, PERMISSION, SEAT, READY, STARTING, PENDING, UNCERTAIN, RUNTIME_WAITING, STARTED, REJECTED, UNAVAILABLE, CLOSED,
}

data class FreeFlightSeatChoice(val seat: String, val scene: SeatSceneChoice)

data class FreeFlightPreparationUiState(
    val step: FreeFlightPreparationStep = FreeFlightPreparationStep.IDLE,
    val prepared: FreeFlightPrepared? = null,
    val heading: Float = 0f,
    val selection: FreeFlightSeatChoice? = null,
    val submitted: Boolean = false,
    val message: String? = null,
)

/** Saved UI data is not an owner grant. restore() asks the real host to recover its retained context. */
data class FreeFlightPreparationSaved(
    val preparationId: String,
    val sessionId: String?,
    val heading: Float,
    val selection: FreeFlightSeatChoice?,
    val step: FreeFlightPreparationStep,
    val submitted: Boolean,
)

/** UI state and waiting Jobs only. No archive/booking/history/quota writes or detached success path. */
class FreeFlightPreparationCoordinator(
    private val actions: FreeFlightPreparationActions,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(FreeFlightPreparationUiState())
    val state: StateFlow<FreeFlightPreparationUiState> = mutableState.asStateFlow()
    private var waiting: Job? = null
    private var operation = 0L
    private var detached = false
    private var deliveredOutcome: FreeFlightStartIdentity? = null
    private var failureNotified: FreeFlightStartIdentity? = null
    private var headingRevision = 0L
    private var committedStart: FreeFlightCommandResult.Committed? = null
    private var runtimeRetry: Job? = null
    private val observation = scope.launch {
        actions.startState.collect { start ->
            val ticket = operation
            try { observeStart(start) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                if (valid(ticket) && state.value.submitted) mutableState.value = state.value.copy(step = FreeFlightPreparationStep.UNCERTAIN)
            }
        }
    }
    private val invalidationObservation = scope.launch {
        actions.ownerInvalidations.collect { owner ->
            if (!detached && state.value.prepared?.owner == owner && state.value.step != FreeFlightPreparationStep.CLOSED) {
                ++operation;waiting?.cancel();mutableState.value = state.value.copy(step = FreeFlightPreparationStep.UNAVAILABLE)
            }
        }
    }
    private val runtimeObservation = scope.launch {
        actions.runtimeState.collect { runtime ->
            val current = state.value
            val prepared = current.prepared ?: return@collect
            val committed = committedStart ?: return@collect
            val ticket = operation
            if (!current.submitted || !valid(ticket) || !runtimeReady(prepared, runtime)) return@collect
            try {
                if (actions.isCurrent(prepared) && valid(ticket) && state.value.prepared == prepared)
                    deliverCommitted(prepared, committed)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* Keep the saved flight waiting; this is not an admission rejection. */ }
        }
    }

    fun begin() {
        if (detached || state.value.step != FreeFlightPreparationStep.IDLE) return
        actions.unavailableReason?.let { mutableState.value = state.value.copy(step = FreeFlightPreparationStep.UNAVAILABLE, message = it); return }
        val ticket = ++operation
        mutableState.value = state.value.copy(step = FreeFlightPreparationStep.CHECKING)
        waiting = scope.launch {
            try { accept(actions.prepare(), ticket, null) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (valid(ticket)) unavailable() }
        }
    }

    fun restore(saved: FreeFlightPreparationSaved) {
        if (detached || state.value.step != FreeFlightPreparationStep.IDLE) return
        val ticket = ++operation
        mutableState.value = state.value.copy(step = FreeFlightPreparationStep.CHECKING, submitted = saved.submitted)
        waiting = scope.launch {
            try { accept(actions.restore(saved.preparationId, saved.sessionId), ticket, saved) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (valid(ticket)) unavailable() }
        }
    }

    private suspend fun accept(decision: FreeFlightPreparationDecision, ticket: Long, saved: FreeFlightPreparationSaved?) {
        if (!valid(ticket)) return
        when (decision) {
            is FreeFlightPreparationDecision.Prepared -> {
                val context = decision.value
                if (context.preparationId != actions.preparationId ||
                    saved?.let { it.preparationId != context.preparationId || (it.sessionId != null && it.sessionId != context.sessionId) } == true) {
                    if (valid(ticket)) unavailable();return
                }
                val ownerCurrent = actions.isCurrent(context)
                if (!valid(ticket)) return
                if (!ownerCurrent) { unavailable();return }
                val step = when {
                    saved?.submitted == true || identityOf(actions.startState.value) == FreeFlightStartIdentity(context.owner, context.sessionId) -> FreeFlightPreparationStep.STARTING
                    saved?.step == FreeFlightPreparationStep.READY && saved.selection != null -> FreeFlightPreparationStep.READY
                    saved?.step == FreeFlightPreparationStep.SEAT -> FreeFlightPreparationStep.SEAT
                    else -> FreeFlightPreparationStep.HEADING
                }
                val submitted = saved?.submitted == true || identityOf(actions.startState.value) == FreeFlightStartIdentity(context.owner, context.sessionId)
                mutableState.value = FreeFlightPreparationUiState(step, context, saved?.heading?.takeIf { it.isFinite() } ?: 0f, saved?.selection, submitted)
                actions.preparationShown(context)
                actions.headingChanged(context, normalize(state.value.heading))
                if (submitted) {
                    val start = actions.startState.value
                    if (start == FreeFlightStartState.Idle) mutableState.value = state.value.copy(step = FreeFlightPreparationStep.UNCERTAIN)
                    else observeStart(start)
                }
            }
            is FreeFlightPreparationDecision.Unavailable -> mutableState.value = state.value.copy(step = FreeFlightPreparationStep.UNAVAILABLE, message = decision.message)
            is FreeFlightPreparationDecision.Rejected -> mutableState.value = state.value.copy(step = FreeFlightPreparationStep.REJECTED, message = decision.message)
        }
    }

    fun headingChanged(value: Float) {
        val current = state.value
        if (detached || current.step != FreeFlightPreparationStep.HEADING || !value.isFinite()) return
        val prepared = current.prepared ?: return
        mutableState.value = current.copy(heading = value)
        val revision = ++headingRevision
        scope.launch {
            try {
                if (actions.isCurrent(prepared) && !detached && revision == headingRevision && state.value.prepared == prepared && state.value.step == FreeFlightPreparationStep.HEADING)
                    actions.headingChanged(prepared, normalize(value))
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (!detached && state.value.prepared == prepared && state.value.step == FreeFlightPreparationStep.HEADING) unavailable() }
        }
    }

    fun continueToSeat() {
        val current = state.value
        if (detached || current.step != FreeFlightPreparationStep.HEADING || current.prepared == null) return
        val context = checkNotNull(current.prepared);val ticket = ++operation
        mutableState.value = current.copy(step = FreeFlightPreparationStep.PERMISSION, message = null)
        waiting = scope.launch {
            try {
                if (!actions.isCurrent(context) || !valid(ticket)) { if (valid(ticket)) unavailable(); return@launch }
                val result = actions.ensureKeepAlivePermissions(context)
                if (!valid(ticket)) return@launch
                val ownerCurrent = actions.isCurrent(context)
                if (!valid(ticket)) return@launch
                if (!ownerCurrent) { unavailable();return@launch }
                mutableState.value = when (result) {
                    FreeFlightPermissionDecision.Ready -> state.value.copy(step = FreeFlightPreparationStep.SEAT, message = null)
                    is FreeFlightPermissionDecision.NeedsAction -> state.value.copy(step = FreeFlightPreparationStep.HEADING, message = result.message)
                    is FreeFlightPermissionDecision.Unavailable -> state.value.copy(step = FreeFlightPreparationStep.HEADING, message = result.message)
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (valid(ticket)) mutableState.value = state.value.copy(step = FreeFlightPreparationStep.HEADING) }
        }
    }

    fun confirmSeat(value: FreeFlightSeatChoice) {
        val current = state.value
        if (detached || current.step != FreeFlightPreparationStep.SEAT || !validSeat(value.seat) ||
            value.scene.optionId.isBlank() || value.scene.title.trim { it <= '\u0020' }.isEmpty()) return
        val prepared = current.prepared ?: return
        val ticket = ++operation
        scope.launch {
            if (actions.isCurrent(prepared) && valid(ticket) && state.value.step == FreeFlightPreparationStep.SEAT)
                mutableState.value = state.value.copy(step = FreeFlightPreparationStep.READY, selection = value, message = null)
        }
    }

    fun chooseSeat(value: FreeFlightSeatChoice) {
        if (!detached && state.value.step == FreeFlightPreparationStep.SEAT && validSeat(value.seat) && value.scene.optionId.isNotBlank())
            mutableState.value = state.value.copy(selection = value)
    }

    fun unavailableRestoredScene() {
        if (state.value.step == FreeFlightPreparationStep.READY && !state.value.submitted)
            mutableState.value = state.value.copy(step = FreeFlightPreparationStep.SEAT, selection = null)
    }

    fun submit() {
        val current = state.value
        if (detached || current.step != FreeFlightPreparationStep.READY || current.submitted) return
        val prepared = checkNotNull(current.prepared);val selected = current.selection ?: return
        val request = FreeFlightStartRequest(
            sessionId = prepared.sessionId, ownerScope = prepared.owner.ownerScope,
            departure = prepared.departure, headingDegrees = normalize(current.heading).toDouble(), seat = selected.seat,
            scene = FreeFlightScene(selected.scene.type.coerceAtLeast(0), selected.scene.title.trim { it <= '\u0020' }.takeIf(String::isNotEmpty), selected.scene.iconKey),
            flightNumber = prepared.flightNumber, shieldingCategoryId = prepared.shieldingCategoryId,
        )
        val ticket = ++operation
        mutableState.value = current.copy(step = FreeFlightPreparationStep.STARTING)
        waiting = scope.launch {
            try {
                if (!actions.isCurrent(prepared) || !valid(ticket)) { if (valid(ticket)) unavailable(); return@launch }
                mutableState.value = state.value.copy(submitted = true)
                val result = actions.start(prepared, request)
                if (valid(ticket) && state.value.prepared == prepared && actions.isCurrent(prepared) && valid(ticket)) outcome(prepared, result)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (valid(ticket) && state.value.prepared == prepared) mutableState.value = state.value.copy(step = FreeFlightPreparationStep.UNCERTAIN) }
        }
    }

    private suspend fun observeStart(start: FreeFlightStartState) {
        val current = state.value;val prepared = current.prepared ?: return
        val ticket = operation
        if (detached || !current.submitted || current.step == FreeFlightPreparationStep.CLOSED || !actions.isCurrent(prepared)) return
        if (!valid(ticket) || state.value.prepared != prepared) return
        val identity = identityOf(start) ?: return
        if (identity != FreeFlightStartIdentity(prepared.owner, prepared.sessionId)) return
        if (deliveredOutcome == identity) return
        mutableState.value = when (start) {
            is FreeFlightStartState.Checking -> current.copy(step = FreeFlightPreparationStep.STARTING)
            is FreeFlightStartState.Pending -> current.copy(step = FreeFlightPreparationStep.PENDING)
            is FreeFlightStartState.Uncertain, is FreeFlightStartState.ReconciliationRequired -> current.copy(step = FreeFlightPreparationStep.UNCERTAIN)
            is FreeFlightStartState.Finished -> { outcome(prepared, start.result);return }
            FreeFlightStartState.Idle -> current
        }
    }

    private fun identityOf(start: FreeFlightStartState): FreeFlightStartIdentity? = when (start) {
            is FreeFlightStartState.Checking -> start.identity
            is FreeFlightStartState.Pending -> start.identity
            is FreeFlightStartState.Uncertain -> start.identity
            is FreeFlightStartState.ReconciliationRequired -> start.identity
            is FreeFlightStartState.Finished -> start.identity
            FreeFlightStartState.Idle -> null
    }

    private fun outcome(prepared: FreeFlightPrepared, result: FreeFlightCommandResult) {
        if (detached || state.value.step == FreeFlightPreparationStep.CLOSED || state.value.prepared != prepared) return
        val identity = FreeFlightStartIdentity(prepared.owner, prepared.sessionId)
        if (deliveredOutcome == identity) return
        if (result is FreeFlightCommandResult.Committed && (result.archive.sessionId != prepared.sessionId || result.archive.ownerScope != prepared.owner.ownerScope)) return
        if (result is FreeFlightCommandResult.Committed) {
            committedStart = result
            if (runtimeReady(prepared, actions.runtimeState.value)) deliverCommitted(prepared, result)
            else mutableState.value = state.value.copy(step = FreeFlightPreparationStep.RUNTIME_WAITING)
            return
        }
        if (result is FreeFlightCommandResult.Failed) {
            mutableState.value = state.value.copy(step = FreeFlightPreparationStep.UNCERTAIN)
            if (failureNotified != identity) { failureNotified = identity;actions.startOutcome(prepared, result) }
            return
        }
        deliveredOutcome = identity
        mutableState.value = state.value.copy(step = when (result) {
            is FreeFlightCommandResult.Committed -> FreeFlightPreparationStep.STARTED
            is FreeFlightCommandResult.Rejected -> FreeFlightPreparationStep.REJECTED
            is FreeFlightCommandResult.Failed -> FreeFlightPreparationStep.UNCERTAIN
        })
        actions.startOutcome(prepared, result)
    }

    private fun runtimeReady(prepared: FreeFlightPrepared, runtime: FreeFlightRuntimeObservation): Boolean =
        runtime.key?.let { it.sessionId == prepared.sessionId && it.owner == prepared.owner } == true &&
            runtime.state == FreeFlightDriverState.RUNNING && runtime.foregroundConfirmed && runtime.dispatching

    private fun deliverCommitted(prepared: FreeFlightPrepared, committed: FreeFlightCommandResult.Committed) {
        val identity = FreeFlightStartIdentity(prepared.owner, prepared.sessionId)
        if (deliveredOutcome == identity || !runtimeReady(prepared, actions.runtimeState.value)) return
        deliveredOutcome = identity
        mutableState.value = state.value.copy(step = FreeFlightPreparationStep.STARTED)
        // Preserve the original command receipt, including its original runtime failure. The
        // separate real observation above is the evidence that the driver is running now.
        actions.startOutcome(prepared, committed)
    }

    fun retryRuntime() {
        val current = state.value
        val prepared = current.prepared ?: return
        if (detached || current.step != FreeFlightPreparationStep.RUNTIME_WAITING || !current.submitted ||
            committedStart == null || runtimeRetry?.isActive == true) return
        val ticket = operation
        runtimeRetry = scope.launch {
            try {
                if (!actions.isCurrent(prepared) || !valid(ticket)) return@launch
                val result = actions.retryRuntime(prepared)
                if (valid(ticket) && state.value.prepared == prepared && actions.isCurrent(prepared) && valid(ticket) &&
                    result is FreeFlightCommandResult.Committed) outcome(prepared, result)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* Keep the same local start and an explicit runtime retry. */ }
        }
    }

    fun dismiss(reason: FreeFlightPreparationDismissal) {
        if (detached || state.value.step == FreeFlightPreparationStep.CLOSED) return
        val context = state.value.prepared;val submitted = state.value.submitted
        ++operation;waiting?.cancel();mutableState.value = state.value.copy(step = FreeFlightPreparationStep.CLOSED)
        actions.dismissed(context, submitted, reason)
    }

    /** Rotation/onStop only detach this UI. The actual owner's lease/formal operation is not cancelled. */
    fun detach() { detached = true;++operation;waiting?.cancel();runtimeRetry?.cancel();observation.cancel();invalidationObservation.cancel();runtimeObservation.cancel() }

    fun saved(): FreeFlightPreparationSaved? = state.value.takeUnless {
        it.step in setOf(FreeFlightPreparationStep.IDLE, FreeFlightPreparationStep.CLOSED, FreeFlightPreparationStep.UNAVAILABLE, FreeFlightPreparationStep.REJECTED, FreeFlightPreparationStep.STARTED)
    }?.let { FreeFlightPreparationSaved(it.prepared?.preparationId ?: actions.preparationId, it.prepared?.sessionId, it.heading, it.selection, it.step, it.submitted) }

    private fun valid(ticket: Long): Boolean = !detached && ticket == operation && state.value.step != FreeFlightPreparationStep.CLOSED
    private fun unavailable() { mutableState.value = state.value.copy(step = FreeFlightPreparationStep.UNAVAILABLE) }

    companion object {
        fun normalize(value: Float): Int = ((value.toInt() % 360) + 360) % 360
        fun validSeat(value: String): Boolean = (1..SeatMapGeometry.ROWS).any { row -> SeatMapGeometry.COLUMNS.any { column -> value == "$row$column" } }
    }
}
