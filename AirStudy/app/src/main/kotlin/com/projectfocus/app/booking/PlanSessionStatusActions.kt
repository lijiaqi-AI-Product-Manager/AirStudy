package com.projectfocus.app.booking

import com.projectfocus.core.network.di.TokenStore
import kotlinx.serialization.Serializable

/** Own accepted-session handoff. The source has no proved durable command journal. */
@Serializable
enum class AcceptedPlanStatus { STARTED, ABORTED, COMPLETED }

@Serializable
data class AcceptedPlanStatusCommand(
    val sessionId: String,
    val selection: ActivePlanSelection,
    val sessionTimestampMillis: Long,
    val status: AcceptedPlanStatus,
    val completedAtMillis: Long? = null,
)

@Serializable
internal enum class AcceptedPlanCommandAction { QUEUED, PROTECTED_COMPLETION, NO_PENDING }

@Serializable
internal data class AcceptedPlanCommandReceipt(
    val command: AcceptedPlanStatusCommand,
    val action: AcceptedPlanCommandAction,
    val entryId: String? = null,
)

interface PlanSessionStatusActions {
    /**
     * Queue at most once for this exact session/status. Returning means a durable
     * local handoff, not server acknowledgement. A retry keeps its first receipt.
     */
    fun handoff(command: AcceptedPlanStatusCommand, expectedIdentity: TokenStore.Snapshot)
}
