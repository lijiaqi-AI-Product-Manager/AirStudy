package com.projectfocus.core.common.plan

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SourceFlightPlanTimingTest {
    private val now = 1_793_423_520_000L

    @Test fun delayArrayNormalizesWithUsLocaleBeforeTheSharedStatusParser() {
        val before = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
            assertThat(SourceFlightPlanTiming.delays(listOf(
                SourceFlightPlanTiming.Segment("IN_PROGRESS", now, 800.0),
                SourceFlightPlanTiming.Segment("WAITING_TAKEOFF", now, 0.0)), now))
                .containsExactly(0L, 3_720_000L).inOrder()
        } finally { java.util.Locale.setDefault(before) }
    }

    @Test fun continuousDurationRetainsSecondsAndRejectsNonfiniteInput() {
        assertThat(SourceFlightPlanTiming.durationMillis(1099.5)).isEqualTo(4_947_750L)
        assertThat(SourceFlightPlanTiming.durationMillis(13.5)).isEqualTo(60_750L)
        for (invalid in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThat(SourceFlightPlanTiming.durationMillis(invalid)).isEqualTo(0L)
        }
    }

    @Test fun completedThenWaitingUsesDepartureBasedCarryAndTwoMinuteConnection() {
        val completed = SourceFlightPlanTiming.Segment("completed", now - 60_000L, 13.5)
        val waiting = SourceFlightPlanTiming.Segment("waiting_takeoff", now, 13.5)
        assertThat(SourceFlightPlanTiming.delays(listOf(completed, waiting, waiting), now))
            .containsExactly(0L, 120_750L, 301_500L).inOrder()
    }

    @Test fun runningUsesCapturedNowAndCanceledOrUnknownRowsBreakTheConnection() {
        val running = SourceFlightPlanTiming.Segment("in_progress", now - 3_600_000L, 800.0)
        val waiting = SourceFlightPlanTiming.Segment("waiting_takeoff", now + 60_000L, 800.0)
        val canceled = SourceFlightPlanTiming.Segment("canceled", now, 800.0)
        assertThat(SourceFlightPlanTiming.delays(listOf(running, waiting, canceled, waiting,
            canceled.copy(status = "future_status"), waiting), now))
            .containsExactly(0L, 3_660_000L, 0L, 0L, 0L, 0L).inOrder()
    }

    @Test fun nullAndUnparseableRowsKeepPriorCarryRatherThanSortingOrDroppingPositions() {
        val completed = SourceFlightPlanTiming.Segment("completed", now, 800.0)
        val missing = SourceFlightPlanTiming.Segment("completed", null, 800.0)
        val waiting = SourceFlightPlanTiming.Segment("waiting_takeoff", now, 0.0)
        assertThat(SourceFlightPlanTiming.delays(listOf(completed, null, missing, waiting), now))
            .containsExactly(0L, 0L, 0L, 3_720_000L).inOrder()
        assertThat(SourceFlightPlanTiming.delays(listOf(completed, missing.copy(status = "canceled"), waiting), now))
            .containsExactly(0L, 0L, 0L).inOrder()
    }
}
