package com.projectfocus.core.model.flight

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FlightLegPlanTest {
    private val origin = PlannedAirport(1, "AAA", "Origin", "First", "X", 0.0, 0.0)
    private val destination = PlannedAirport(2, "BBB", "Destination", "Second", "X", 0.0, 0.8993216059187306)

    @Test
    fun `a real 100km route retains distance independently from its rounded eight minutes`() {
        val plan = FlightLegPlan.between(origin, destination)
        assertThat(plan.distanceKm).isEqualTo(100.0)
        assertThat(plan.durationMinutes).isEqualTo(8)
        assertThat(plan.categoryId).isNull()
        // 8/60*800 is 106.666..., so reconstructing from minutes would corrupt the route.
        assertThat(plan.distanceKm).isNotEqualTo(plan.durationMinutes / 60.0 * 800.0)
        assertThat(Json.decodeFromString<FlightLegPlan>(Json.encodeToString(plan))).isEqualTo(plan)
    }

    @Test
    fun `confirmed airports cannot be relabelled with invented distance or duration`() {
        val plan = FlightLegPlan.between(origin, destination)
        assertThrows<IllegalArgumentException> { plan.copy(distanceKm = 106.66666666666667) }
        assertThrows<IllegalArgumentException> { plan.copy(durationMinutes = 25) }
        assertThrows<IllegalArgumentException> { FlightLegPlan.between(origin, origin) }
    }

    @Test
    fun `invalid coordinates and zero-minute routes cannot become a confirmed flight`() {
        assertThrows<IllegalArgumentException> { origin.copy(latitude = Double.NaN) }
        assertThrows<IllegalArgumentException> { destination.copy(longitude = 181.0) }
        assertThrows<IllegalArgumentException> {
            FlightLegPlan.between(origin, destination.copy(longitude = 0.00001))
        }
    }

    @Test
    fun `missing category is null and session sentinels cannot become a category foreign key`() {
        val plan = FlightLegPlan.between(origin, destination)
        assertThat(plan.categoryId).isNull()
        assertThat(plan.copy(categoryId = 0L).categoryId).isEqualTo(0L)
        assertThrows<IllegalArgumentException> { plan.copy(categoryId = -1L) }
        assertThrows<IllegalArgumentException> { plan.copy(categoryId = -2L) }
    }
}
