package com.projectfocus.app.booking

/** Read from the status-dispatch SP, never from the seat navigation pending record. */
data class PlanPendingReconciliationState(
    val ownerScope: String,
    val entryId: String,
    val localDate: String,
    val legIndex: Int,
    val targetStatus: String,
    val mappedStatus: Int,
    val timestampMillis: Long,
)

/** Closing cancels only an attempt created by that lease; it never deletes durable state. */
interface PlanReconciliationLease {
    fun close()
}

/** Own bridge around the existing completion dispatcher and exact receipt SP. */
interface PlanOriginReconciliationAccess {
    fun pendingStatus(): PlanPendingReconciliationState?
    fun exactReceiptMillis(localDate: String, legIndex: Int): Long?
    fun resendPending(): PlanReconciliationLease
    /** Persist H's metadata only; x's resend step owns the subsequent network attempt. */
    fun requeueFromReceipt(selection: ActivePlanSelection, receiptMillis: Long)
}
