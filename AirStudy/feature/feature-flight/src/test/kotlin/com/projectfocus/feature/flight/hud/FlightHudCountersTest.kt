package com.projectfocus.feature.flight.hud

import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.model.flight.freeflight.FreeFlightMetricUnit
import org.junit.Test

class FlightHudCountersTest {
    @Test fun sourceRemainingSwitchesToWholeKilometresAndWholeMinutesAtTheActualBoundaries() {
        val before = FlightHudCounters.remaining(.9994, 59)
        assertThat(before.metrics.distance.value).isEqualTo(999)
        assertThat(before.metrics.distance.unit).isEqualTo(FreeFlightMetricUnit.METRES)
        assertThat(before.metrics.time.value).isEqualTo(59)
        assertThat(before.metrics.time.unit).isEqualTo(FreeFlightMetricUnit.SECONDS)
        val after = FlightHudCounters.remaining(.9995, 60)
        assertThat(after.metrics.distance.value).isEqualTo(1)
        assertThat(after.metrics.distance.unit).isEqualTo(FreeFlightMetricUnit.KILOMETRES)
        assertThat(after.metrics.time.value).isEqualTo(1)
        assertThat(after.metrics.time.unit).isEqualTo(FreeFlightMetricUnit.MINUTES)
    }

    @Test fun sourceCoreElapsedRetainsSecondsUntilOneHundredWithoutRoundingMinutesUp() {
        val before = FlightHudCounters.coreElapsed(1.5, 99)
        assertThat(before.elapsed).isTrue()
        assertThat(before.metrics.distance.value).isEqualTo(2)
        assertThat(before.metrics.time.value).isEqualTo(99)
        assertThat(before.metrics.time.unit).isEqualTo(FreeFlightMetricUnit.SECONDS)
        val after = FlightHudCounters.coreElapsed(1.5, 100)
        assertThat(after.metrics.time.value).isEqualTo(1)
        assertThat(after.metrics.time.unit).isEqualTo(FreeFlightMetricUnit.MINUTES)
    }
}
