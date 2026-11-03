package com.projectfocus.app.booking

import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.network.dto.FlightPlanLegSnapshot
import java.time.Instant
import java.util.TimeZone
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlanCompletionParametersTest {
    @get:org.junit.Rule
    val realApplicationLifetime = com.projectfocus.app.testing.RealApplicationLifetimeRule()

    private val epoch = Instant.parse("2026-10-31T04:00:00Z").toEpochMilli()

    @Test fun utcFormatterAndExpectedParseDoNotBecomeStrictIsoOrUseOtherArrivalFields() {
        assertThat(PlanCompletionParameters.actualArrival(0)).isEmpty()
        assertThat(PlanCompletionParameters.actualArrival(-1)).isEmpty()
        assertThat(PlanCompletionParameters.actualArrival(epoch + 789)).isEqualTo("2026-10-31T04:00:00.789Z")
        assertThat(PlanCompletionParameters.parseEpoch("2026-10-31T04:00:00Zsuffix")).isEqualTo(epoch)
        assertThat(PlanCompletionParameters.parseEpoch("  ")).isNull()
        val zeroExpected = leg().copy(expectedArrivalAt = "1970-01-01T00:00:00Z")
        assertThat(PlanCompletionParameters.differenceMinutes(zeroExpected, epoch)).isEqualTo(Int.MIN_VALUE)
        val scheduledOnly = leg().copy(expectedArrivalAt = "", scheduledArrivalAt = "2026-10-31T07:00:00Z",
            actualArrivalAt = "2026-10-31T01:00:00Z")

        assertThat(PlanCompletionParameters.differenceMinutes(scheduledOnly, epoch + 510_001)).isEqualTo(1)
    }

    @Test fun minuteThresholdIsInclusiveThenTruncatedWithSourceJvmNarrowing() {
        for ((delta, expected) in listOf(-120_000L to -2, -119_999L to -1, -60_001L to -1,
            -60_000L to 0, 0L to 0, 60_000L to 0, 60_001L to 1, 119_999L to 1, 120_000L to 2)) {
            assertThat(PlanCompletionParameters.differenceMinutes(leg(), epoch + delta)).isEqualTo(expected)
        }
        val narrowed = (Int.MAX_VALUE.toLong() + 1L) * 60_000L
        assertThat(PlanCompletionParameters.differenceMinutes(leg(), epoch + narrowed)).isEqualTo(1)
        assertThat(PlanCompletionParameters.differenceMinutes(leg(), 0)).isEqualTo(Int.MIN_VALUE)
    }

    @Test fun cmpgNanAndInfinityFallbackAreDistinctFromNonpositiveDistance() {
        val fallback = leg().copy(expectedArrivalAt = "")
        assertThat(PlanCompletionParameters.differenceMinutes(fallback.copy(distanceKm = Double.NaN), epoch + 60_001)).isEqualTo(1)
        assertThat(PlanCompletionParameters.differenceMinutes(fallback.copy(distanceKm = Double.POSITIVE_INFINITY), epoch + 60_001)).isEqualTo(1)
        for (distance in listOf(0.0, -1.0, Double.NEGATIVE_INFINITY)) {
            assertThat(PlanCompletionParameters.differenceMinutes(fallback.copy(distanceKm = distance), epoch + 60_001))
                .isEqualTo(Int.MIN_VALUE)
        }
        assertThat(PlanCompletionParameters.differenceMinutes(fallback.copy(scheduledDepartureAt = "invalid"), epoch)).isEqualTo(Int.MIN_VALUE)
    }

    @Test fun receiptFallbackUsesDeviceDepartureDayAndUtcDateOfLocalMidnight() {
        assertThat(PlanCompletionParameters.receiptDays(leg(), TimeZone.getTimeZone("Asia/Shanghai")))
            .containsExactly("2026-10-31", "2026-10-30").inOrder()
        assertThat(PlanCompletionParameters.receiptDays(leg(), TimeZone.getTimeZone("UTC")))
            .containsExactly("2026-10-31")
        assertThat(PlanCompletionParameters.receiptDays(leg().copy(scheduledDepartureAt = "bad"), TimeZone.getTimeZone("Asia/Shanghai"))).isEmpty()
    }

    private fun leg() = FlightPlanLegSnapshot(1, null, null, "2026-10-31T04:00:00Z", "in_progress",
        100.0, "", "", 1, "学习", expectedArrivalAt = "2026-10-31T04:00:00Z")
}
