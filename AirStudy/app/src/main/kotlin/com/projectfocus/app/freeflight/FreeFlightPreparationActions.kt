package com.projectfocus.app.freeflight

import com.projectfocus.app.MainActivity
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.freeflight.FreeFlightStartRequest
import com.projectfocus.service.focus.freeflight.FreeFlightCommandResult
import com.projectfocus.service.focus.freeflight.FreeFlightOwnerToken
import com.projectfocus.service.focus.freeflight.FreeFlightStartState
import com.projectfocus.service.focus.freeflight.runtime.FreeFlightRuntimeObservation
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow

/** Captured by the actual account/host lease, never reconstructed as authority from a Bundle. */
data class FreeFlightPrepared(
    val preparationId: String,
    val owner: FreeFlightOwnerToken,
    val sessionId: String,
    val departure: PlannedAirport,
    val flightNumber: String?,
    val shieldingCategoryId: Long?,
) {
    init {
        require(preparationId.isNotBlank() && sessionId.isNotBlank())
        require(shieldingCategoryId == null || shieldingCategoryId >= 0L)
    }
}

sealed interface FreeFlightPreparationDecision {
    data class Prepared(val value: FreeFlightPrepared) : FreeFlightPreparationDecision
    data class Unavailable(val message: String) : FreeFlightPreparationDecision
    data class Rejected(val message: String) : FreeFlightPreparationDecision
}

sealed interface FreeFlightPermissionDecision {
    object Ready : FreeFlightPermissionDecision
    /** System permission/settings was opened or denied; another explicit click rechecks actual state. */
    data class NeedsAction(val message: String?) : FreeFlightPermissionDecision
    data class Unavailable(val message: String) : FreeFlightPermissionDecision
}

enum class FreeFlightPreparationDismissal { HEADING_CANCEL, BACK_EXIT }

/**
 * Required real host effects. This file supplies no allowing/accepting/default implementation.
 * prepare uses actual FreeFlightPreparationAdmission, AFTER account restore and owner capture.
 * start performs formal repository.start, distinct from prepare. Its underlying operation and
 * shared reservation must outlive cancellation of this screen's waiting coroutine. A real
 * startState Pending/Uncertain stays bound to the SAME owner/session and is never retried here.
 */
interface FreeFlightPreparationActions {
    /** Retained UI binding identity exists before prepare resolves; it grants no owner authority. */
    val preparationId: String
    /** Missing any actual admission/owner/HMS/runtime port must produce a user-facing reason. */
    val unavailableReason: String?
    /** Real preference-backed snapshots; no guessed source intensity or unrelated white-noise volume. */
    val rulerFeedback: FreeFlightRulerFeedback
    val startState: StateFlow<FreeFlightStartState>
    /** Actual driver confirmation is independent of local archive/admission completion. */
    val runtimeState: StateFlow<FreeFlightRuntimeObservation>
    val ownerInvalidations: Flow<FreeFlightOwnerToken>
    suspend fun prepare(): FreeFlightPreparationDecision
    /** Retrieve the retained real lease/context, not repeat prepare or mint from saved UI fields. */
    suspend fun restore(preparationId: String, sessionId: String?): FreeFlightPreparationDecision
    suspend fun isCurrent(prepared: FreeFlightPrepared): Boolean
    suspend fun ensureKeepAlivePermissions(prepared: FreeFlightPrepared): FreeFlightPermissionDecision
    /** Actual map/setup effects; attached Main controls are owned by the production controller. */
    fun preparationShown(prepared: FreeFlightPrepared)
    /** Called after actual surface removal; UI restoration only, never release a submitted flight. */
    fun preparationHidden(prepared: FreeFlightPrepared?, submitted: Boolean)
    fun headingChanged(prepared: FreeFlightPrepared, headingDegrees: Int)
    suspend fun start(prepared: FreeFlightPrepared, request: FreeFlightStartRequest): FreeFlightCommandResult
    /** Synchronize the SAME committed flight; never run formal admission again. */
    suspend fun retryRuntime(prepared: FreeFlightPrepared): FreeFlightCommandResult
    /** UI exit only. submitted=true MUST NOT cancel formal admission, invalidate its lease or release reservation. */
    fun dismissed(prepared: FreeFlightPrepared?, submitted: Boolean, reason: FreeFlightPreparationDismissal)
    /** Real result callback for captured owner/session. Committed is delivered only after the
     * matching actual foreground/dispatcher confirmation, separately from its original receipt. */
    fun startOutcome(prepared: FreeFlightPrepared, result: FreeFlightCommandResult)
}

/** Root installs an actual adapter. Absence leaves mode1 unavailable; this is not an auto-Hilt fake. */
interface FreeFlightPreparationActionsProvider {
    fun freeFlightPreparationActions(activity: MainActivity, restoringPreparationId: String?): FreeFlightPreparationActions?
}
