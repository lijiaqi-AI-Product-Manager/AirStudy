package com.projectfocus.app.booking

import com.projectfocus.core.common.plan.SourceFlightArrivalOutcome
import com.projectfocus.core.common.plan.SourceFlightPlanStatus
import com.projectfocus.core.common.plan.SourceFlightPlanTiming
import com.projectfocus.core.network.dto.FlightPlanLegSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import java.text.SimpleDateFormat


internal object FlightPlanCardPresentation {
    fun clock(value: String, deviceZone: ZoneId = ZoneId.systemDefault()): String? = try {
        instantMillis(value)?.let { DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
            .format(Instant.ofEpochMilli(it).atZone(deviceZone)) }
    } catch (_: Exception) { null }

    fun arrival(leg: FlightPlanLegSnapshot, deviceZone: ZoneId = ZoneId.systemDefault()): String? =
        clock(leg.scheduledArrivalAt, deviceZone) ?: clock(leg.expectedArrivalAt, deviceZone) ?: try {
            clock(Instant.ofEpochMilli(requireNotNull(instantMillis(leg.scheduledDepartureAt)))
                .plusMillis(SourceFlightPlanTiming.durationMillis(leg.distanceKm))
                .toString(), deviceZone)
        } catch (_: Exception) { null }

    fun instantMillis(value: String): Long? {
        if (value.trim { it <= '\u0020' }.isEmpty()) return null
        for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX")) {
            try {
                SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .parse(value)?.let { return it.time }
            } catch (_: Exception) { /* Original tries the next pattern with the untrimmed input. */ }
        }
        return null
    }

    fun departureDate(leg: FlightPlanLegSnapshot, deviceZone: ZoneId = ZoneId.systemDefault()): LocalDate? =
        instantMillis(leg.scheduledDepartureAt)?.let { Instant.ofEpochMilli(it).atZone(deviceZone).toLocalDate() }


    fun canceled(leg: FlightPlanLegSnapshot, today: LocalDate = LocalDate.now(), deviceZone: ZoneId = ZoneId.systemDefault()): Boolean {
        val status = SourceFlightPlanStatus.parse(leg.legStatus)
        if (status == SourceFlightPlanStatus.CANCELED) return true
        if (leg.archived || leg.readOnly || status != SourceFlightPlanStatus.WAITING) return false
        return departureDate(leg, deviceZone)?.isBefore(today) == true
    }

    fun outcome(leg: FlightPlanLegSnapshot, receiptMillis: Long? = null): SourceFlightArrivalOutcome.Outcome? {
        val expected = instantMillis(leg.expectedArrivalAt) ?: instantMillis(leg.scheduledDepartureAt)
            ?.takeIf { !(leg.distanceKm <= 0.0) }?.plus(SourceFlightPlanTiming.durationMillis(leg.distanceKm))
        return SourceFlightArrivalOutcome.classify(instantMillis(leg.actualArrivalAt) ?: receiptMillis, expected)
    }

    fun delayLabel(leg: FlightPlanLegSnapshot, delayMillis: Long, today: LocalDate = LocalDate.now(),
                   deviceZone: ZoneId = ZoneId.systemDefault()): String? {
        if (delayMillis <= 0L || canceled(leg, today, deviceZone) ||
            SourceFlightPlanStatus.parse(leg.legStatus) != SourceFlightPlanStatus.WAITING || departureDate(leg, deviceZone) != today) return null
        val minutes = delayMillis / 60_000L
        return String.format(Locale.US, "+%02d:%02d", minutes / 60L, minutes % 60L)
    }

    fun distance(value: Double, unit: String): String =
        if (value > 0.0) String.format(Locale.US, "%.1f", value) + " " + unit else "--"
}
