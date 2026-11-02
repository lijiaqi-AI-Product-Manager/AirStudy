package com.projectfocus.app.booking

import com.projectfocus.core.network.di.TokenStore

/** A status request was queued, not a server acknowledgement or session acceptance. */
enum class PlanAbortStatusAction { QUEUED_WAITING, PRESERVED_COMPLETION, NO_PENDING }

data class PlanFlightStatusSnapshot(
    val ownerScope: String,
    val entryId: String,
    val selection: ActivePlanSelection,
    val targetStatus: String,
    val sessionTimestampMillis: Long,
    val inProgressSent: Boolean,
    val actuallyStarted: Boolean,
)

/**
 * Explicit source boarding/abort ports. The current app has no source boarding
 * callback; a host must supply a genuinely captured planned-session snapshot and
 * identity. On, seat selection and a wall-clock timer do not call these ports.
 */
interface PlanFlightStatusActions {
    fun markInProgress(selection: ActivePlanSelection, sessionTimestampMillis: Long,
                       expectedIdentity: TokenStore.Snapshot)
    fun notifyAborted(activeSelection: ActivePlanSelection?, expectedIdentity: TokenStore.Snapshot): PlanAbortStatusAction
    /** Ordinary L retry. Origin reconciliation keeps its distinct forced-reset entry. */
    fun retryPendingStatus(): PlanReconciliationLease
    fun snapshot(): PlanFlightStatusSnapshot?
}
