package com.projectfocus.core.model.flight

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.Serializable

/** Product date/zone snapshot for the source departure HH:mm, independent of duration. */
@Serializable
data class PlannedDeparture(
    val date: String,
    val hour: Int,
    val minute: Int,
    val zoneId: String,
) {
    init {
        require(LocalDate.parse(date).toString() == date) { "Noncanonical departure date" }
        require(hour in 0..23 && minute in 0..59) { "Invalid departure clock time" }
        ZoneId.of(zoneId)
    }

    val minuteOfDay: Int get() = hour * 60 + minute

    /** Java time's zone resolution is a product adapter, not proof of the original helper. */
    fun instant(): Instant = LocalDate.parse(date).atTime(hour, minute).atZone(ZoneId.of(zoneId)).toInstant()
}
