package com.projectfocus.app.booking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceOriginReconciliationPolicyTest {
    private val policy = SourceOriginReconciliationPolicy

    @Test fun `empty origin and matching code accept before refresh or reconciliation`() {
        assertEquals(SourceOriginReconciliationPolicy.MismatchAction.ACCEPT,
            policy.mismatchAction(" \t", "PVG", true, true, true))
        assertEquals(SourceOriginReconciliationPolicy.MismatchAction.ACCEPT,
            policy.mismatchAction("PEK", null, false, false, false))
        assertEquals(SourceOriginReconciliationPolicy.MismatchAction.ACCEPT,
            policy.mismatchAction(" pek\r", "PEK", true, true, true))
    }

    @Test fun `nonempty mismatch uses the initial refresh before pending or receipt repair`() {
        assertEquals(SourceOriginReconciliationPolicy.MismatchAction.REFRESH_ONCE,
            policy.mismatchAction("PEK", "PVG", true, true, true))
        assertEquals(SourceOriginReconciliationPolicy.MismatchAction.RESEND_PENDING,
            policy.mismatchAction("PEK", "PVG", false, true, true))
        assertEquals(SourceOriginReconciliationPolicy.MismatchAction.REQUEUE_COMPLETION,
            policy.mismatchAction("PEK", "PVG", false, false, true))
        assertEquals(SourceOriginReconciliationPolicy.MismatchAction.REJECT,
            policy.mismatchAction("PEK", "PVG", false, false, false))
    }

    @Test fun `pending match checks index and mapped states but does not require a matching date`() {
        assertTrue(policy.recentPendingMatches(2, "yesterday-or-opaque", 2, 3, 0, NOW))
        assertTrue(policy.recentPendingMatches(1, "older-date", 1, 2, -1, NOW))
        assertFalse(policy.recentPendingMatches(2, "today", 1, 3, 0, NOW))
        assertFalse(policy.recentPendingMatches(0, "today", 0, 3, 0, NOW))
        assertFalse(policy.recentPendingMatches(2, " \t", 2, 3, 0, NOW))
        assertFalse(policy.recentPendingMatches(2, "today", 2, 1, 0, NOW))
        assertFalse(policy.recentPendingMatches(2, "today", 2, 4, 0, NOW))
    }

    @Test fun `pending age is inclusive48h and accepts missing or future timestamps`() {
        val boundary = NOW - SourceOriginReconciliationPolicy.MAX_AGE_MILLIS
        assertTrue(policy.recentPendingMatches(2, "any", 2, 3, boundary, NOW))
        assertFalse(policy.recentPendingMatches(2, "any", 2, 3, boundary - 1, NOW))
        assertTrue(policy.recentPendingMatches(2, "any", 2, 3, 0, NOW))
        assertTrue(policy.recentPendingMatches(2, "any", 2, 3, NOW + 60_000, NOW))
    }

    @Test fun `receipt requires a positive value but uses the same inclusive signed age guard`() {
        val boundary = NOW - SourceOriginReconciliationPolicy.MAX_AGE_MILLIS
        assertTrue(policy.receiptAllowsRequeue("today", 2, boundary, NOW))
        assertFalse(policy.receiptAllowsRequeue("today", 2, boundary - 1, NOW))
        assertFalse(policy.receiptAllowsRequeue("today", 2, 0, NOW))
        assertFalse(policy.receiptAllowsRequeue("", 2, NOW, NOW))
        assertFalse(policy.receiptAllowsRequeue("today", 0, NOW, NOW))
        assertTrue(policy.receiptAllowsRequeue(" ", 2, NOW + 60_000, NOW))
    }

    @Test fun `poll scheduling and settlement retain the source separate boundaries`() {
        assertTrue(policy.canSchedulePoll(0))
        assertTrue(policy.canSchedulePoll(39))
        assertFalse(policy.canSchedulePoll(40))
        assertEquals(500L, SourceOriginReconciliationPolicy.POLL_DELAY_MILLIS)
        assertTrue(policy.queueStillPending(" old-date ", 1))
        assertTrue(policy.queueStillPending("\u00a0", 1))
        assertFalse(policy.queueStillPending("\t ", 1))
        assertFalse(policy.queueStillPending("old-date", 0))
    }

    private companion object { const val NOW = 500_000_000L }
}
