package com.projectfocus.app.booking

import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.network.dto.FlightPlanLegSnapshot
import org.junit.Test
import java.time.ZoneId
import java.time.LocalDate
import com.projectfocus.core.common.plan.SourceFlightArrivalOutcome

class FlightPlanCardPresentationTest {
    @Test fun scheduledThenExpectedAreParsedSeparatelyAndActualDoesNotOverrideCardArrival() {
        val leg = leg().copy(scheduledArrivalAt = "2026-10-31T02:00:00Z", expectedArrivalAt = "2026-10-31T03:00:00Z",
            actualArrivalAt = "2026-10-31T04:00:00Z")
        assertThat(FlightPlanCardPresentation.arrival(leg, ZoneId.of("UTC"))).isEqualTo("02:00")
        assertThat(FlightPlanCardPresentation.arrival(leg.copy(scheduledArrivalAt = "bad"), ZoneId.of("UTC"))).isEqualTo("03:00")
        assertThat(FlightPlanCardPresentation.arrival(leg.copy(scheduledArrivalAt = "bad", expectedArrivalAt = "bad"), ZoneId.of("UTC")))
            .isEqualTo("00:07") // Original H:100km→7 minutes30seconds; card formats HH:mm.
    }

    @Test fun clockUsesTheSuppliedDeviceZoneIndependentlyOfPlanOwnershipZone() {
        assertThat(FlightPlanCardPresentation.clock("2026-10-31T00:00:00Z", ZoneId.of("Asia/Shanghai"))).isEqualTo("08:00")
        assertThat(FlightPlanCardPresentation.clock("2026-10-31T00:00:00Z", ZoneId.of("UTC"))).isEqualTo("00:00")
        assertThat(FlightPlanCardPresentation.clock("invalid", ZoneId.of("UTC"))).isNull()
    }

    @Test fun distanceRetainsUnitAndSourceNonpositivePlaceholder() {
        assertThat(FlightPlanCardPresentation.distance(1099.5, "km")).isEqualTo("1099.5 km")
        assertThat(FlightPlanCardPresentation.distance(1099.5, "キロ")).isEqualTo("1099.5 キロ")
        assertThat(FlightPlanCardPresentation.distance(0.0, "km")).isEqualTo("--")
        assertThat(FlightPlanCardPresentation.distance(-1.0, "km")).isEqualTo("--")
    }

    @Test fun completionUsesExpectedAndActualRatherThanDisplayedScheduledArrival() {
        val completed = leg().copy(legStatus = "completed", expectedArrivalAt = "2026-10-31T01:00:00Z",
            actualArrivalAt = "2026-10-31T01:01:00Z", scheduledArrivalAt = "2026-10-31T04:00:00Z")
        assertThat(FlightPlanCardPresentation.outcome(completed)?.kind).isEqualTo(SourceFlightArrivalOutcome.Kind.ON_TIME)
        assertThat(FlightPlanCardPresentation.outcome(completed.copy(actualArrivalAt = "2026-10-31T01:01:00.001Z"))?.minutes).isEqualTo(1)
        assertThat(FlightPlanCardPresentation.outcome(completed.copy(actualArrivalAt = "bad"),
            java.time.Instant.parse("2026-10-31T01:02:00Z").toEpochMilli())?.minutes).isEqualTo(2)
        assertThat(FlightPlanCardPresentation.outcome(completed.copy(actualArrivalAt = "1970-01-01T00:00:00Z"),
            java.time.Instant.parse("2026-10-31T01:00:00Z").toEpochMilli())).isNull()
    }

    @Test fun canceledProjectionHonorsPastWaitingAndLocksWithoutMutatingWireStatus() {
        val today = LocalDate.parse("2026-11-01")
        val zone = ZoneId.of("UTC")
        val waiting = leg()
        assertThat(FlightPlanCardPresentation.canceled(waiting, today, zone)).isTrue()
        for (blocked in listOf(waiting.copy(readOnly = true), waiting.copy(archived = true),
            waiting.copy(legStatus = "future_status"), waiting.copy(scheduledDepartureAt = "invalid"))) {
            assertThat(FlightPlanCardPresentation.canceled(blocked, today, zone)).isFalse()
        }
        assertThat(FlightPlanCardPresentation.canceled(waiting.copy(legStatus = "已取消", readOnly = true), today, zone)).isTrue()
        assertThat(waiting.legStatus).isEqualTo("waiting_takeoff")
    }

    @Test fun delayCopyKeepsSubminuteAndMoreThanOneDayHoursButOnlyShowsTodaysWaiting() {
        val today = LocalDate.parse("2026-10-31")
        val zone = ZoneId.of("UTC")
        assertThat(FlightPlanCardPresentation.delayLabel(leg(), 1L, today, zone)).isEqualTo("+00:00")
        assertThat(FlightPlanCardPresentation.delayLabel(leg(), 90_060_000L, today, zone)).isEqualTo("+25:01")
        assertThat(FlightPlanCardPresentation.delayLabel(leg().copy(legStatus = "future_status"), 60_000L, today, zone)).isNull()
        assertThat(FlightPlanCardPresentation.delayLabel(leg(), 60_000L, today.plusDays(1), zone)).isNull()
    }

    @Test fun sourceDateParsingRetainsLenientCalendarAndNanExpectedFallback() {
        assertThat(FlightPlanCardPresentation.clock("2026-02-31T12:34:00Z", ZoneId.of("UTC"))).isEqualTo("12:34")
        val invalidDistance = leg().copy(legStatus = "completed", distanceKm = Double.NaN,
            actualArrivalAt = "2026-10-31T00:00:00Z")
        assertThat(FlightPlanCardPresentation.outcome(invalidDistance)?.kind).isEqualTo(SourceFlightArrivalOutcome.Kind.ON_TIME)
    }

    private fun leg() = FlightPlanLegSnapshot(1, null, null, "2026-10-31T00:00:00Z", "waiting_takeoff", 100.0, "", "", 1, "")
}
