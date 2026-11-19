package com.projectfocus.core.common.plan

import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.common.plan.SourceFlightArrivalOutcome.Kind
import org.junit.jupiter.api.Test

class SourceFlightArrivalOutcomeTest {
    private val expected = 1_793_423_520_000L

    @Test fun inclusiveOneMinuteWindowIsOnTimeInBothDirections() {
        for (offset in listOf(-60_000L, -1L, 0L, 1L, 60_000L)) {
            assertThat(SourceFlightArrivalOutcome.classify(expected + offset, expected))
                .isEqualTo(SourceFlightArrivalOutcome.Outcome(Kind.ON_TIME, 0))
        }
    }

    @Test fun earlyAndLateMinutesUseIntegerDivisionRatherThanRounding() {
        for ((offset, minutes) in listOf(60_001L to 1, 61_000L to 1, 119_999L to 1, 120_000L to 2)) {
            assertThat(SourceFlightArrivalOutcome.classify(expected - offset, expected))
                .isEqualTo(SourceFlightArrivalOutcome.Outcome(Kind.EARLY, minutes))
            assertThat(SourceFlightArrivalOutcome.classify(expected + offset, expected))
                .isEqualTo(SourceFlightArrivalOutcome.Outcome(Kind.DELAYED, minutes))
        }
    }

    @Test fun missingOrNonpositiveReceiptsHaveNoInventedOutcome() {
        for (invalid in listOf(null, 0L, -1L, Long.MIN_VALUE)) {
            assertThat(SourceFlightArrivalOutcome.classify(invalid, expected)).isNull()
            assertThat(SourceFlightArrivalOutcome.classify(expected, invalid)).isNull()
        }
    }

    @Test fun narrowingMatchesTheOriginalForExtremePositiveTimestampDifferences() {
        assertThat(SourceFlightArrivalOutcome.classify(Long.MAX_VALUE, 1L))
            .isEqualTo(SourceFlightArrivalOutcome.Outcome(Kind.DELAYED, 1_692_789_776))
    }
}
