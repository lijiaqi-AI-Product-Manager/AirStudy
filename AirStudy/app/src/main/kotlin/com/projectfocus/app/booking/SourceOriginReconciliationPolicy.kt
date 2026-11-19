package com.projectfocus.app.booking


internal object SourceOriginReconciliationPolicy {
    const val MAX_AGE_MILLIS = 172_800_000L
    const val POLL_DELAY_MILLIS = 500L
    const val MAX_POLL_ATTEMPTS = 40

    enum class MismatchAction {
        ACCEPT,
        REFRESH_ONCE,
        RESEND_PENDING,
        REQUEUE_COMPLETION,
        REJECT,
    }

    fun mismatchAction(currentCode: String?, requiredCode: String?, allowRefresh: Boolean,
                       currentOrPreviousPending: Boolean, recentReceipt: Boolean): MismatchAction {
        val current = javaTrim(currentCode)
        val required = javaTrim(requiredCode)
        if (current.isEmpty() || required.isEmpty() || current.equals(required, ignoreCase = true)) {
            return MismatchAction.ACCEPT
        }
        if (allowRefresh) return MismatchAction.REFRESH_ONCE
        if (currentOrPreviousPending) return MismatchAction.RESEND_PENDING
        if (recentReceipt) return MismatchAction.REQUEUE_COMPLETION
        return MismatchAction.REJECT
    }

    /** z(): storedDate need not equal device today or even parse as a date. */
    fun recentPendingMatches(expectedLegIndex: Int, storedDate: String?, storedLegIndex: Int,
                             mappedStatus: Int, timestampMillis: Long, nowMillis: Long): Boolean {
        if (expectedLegIndex <= 0 || javaTrim(storedDate).isEmpty() || storedLegIndex <= 0) return false
        if (timestampMillis > 0 && nowMillis - timestampMillis > MAX_AGE_MILLIS) return false
        return storedLegIndex == expectedLegIndex && (mappedStatus == 2 || mappedStatus == 3)
    }

    /** H(): unlike z's date helper, this precondition does not trim its date argument. */
    fun receiptAllowsRequeue(date: String?, legIndex: Int, receiptMillis: Long, nowMillis: Long): Boolean =
        date != null && date.isNotEmpty() && legIndex > 0 && receiptMillis > 0 &&
            nowMillis - receiptMillis <= MAX_AGE_MILLIS

    /** F checks this before scheduling; each accepted attempt delays its callback by500ms. */
    fun canSchedulePoll(attempt: Int): Boolean = attempt < MAX_POLL_ATTEMPTS

    /** a5.p0#3: status and current origin are deliberately not part of this test. */
    fun queueStillPending(date: String?, legIndex: Int): Boolean =
        javaTrim(date).isNotEmpty() && legIndex > 0

    private fun javaTrim(value: String?): String = value.orEmpty().trim { it <= '\u0020' }
}
