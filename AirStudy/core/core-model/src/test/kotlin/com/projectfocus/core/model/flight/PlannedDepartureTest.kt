package com.projectfocus.core.model.flight

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.DateTimeException
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PlannedDepartureTest {
    @Test
    fun `departure keeps its date and zone when converted to an instant`() {
        val clock = PlannedDeparture("2026-10-31", 23, 59, "Asia/Shanghai")
        assertThat(clock.minuteOfDay).isEqualTo(1439)
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-10-31T15:59:00Z"))
    }

    @Test
    fun `invalid wall clock and date values cannot become a confirmed schedule`() {
        for ((hour, minute) in listOf(-1 to 0, 24 to 0, 0 to -1, 0 to 60)) {
            assertThrows(IllegalArgumentException::class.java) {
                PlannedDeparture("2026-10-31", hour, minute, "UTC")
            }
        }
        assertThrows(DateTimeException::class.java) { PlannedDeparture("2026-02-30", 0, 0, "UTC") }
        assertThrows(DateTimeException::class.java) { PlannedDeparture("2026-10-31", 0, 0, "Invalid/Zone") }
    }
}
