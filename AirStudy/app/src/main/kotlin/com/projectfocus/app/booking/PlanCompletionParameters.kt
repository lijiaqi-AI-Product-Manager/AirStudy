package com.projectfocus.app.booking

import com.projectfocus.core.network.dto.FlightPlanLegSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max


internal object PlanCompletionParameters {
    fun parseEpoch(text: String?): Long? {
        if (text == null || text.trim { it <= '\u0020' }.isEmpty()) return null
        for (pattern in arrayOf("yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX")) {
            try {
                // Source trims only its empty guard, then parses the original
                // String, with default leniency and no full-consumption check.
                return SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(text)?.time
            } catch (_: Throwable) { /* Source catch-all tries the second pattern. */ }
        }
        return null
    }

    fun actualArrival(timestamp: Long): String {
        if (timestamp <= 0) return ""
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date(timestamp))
        } catch (_: Throwable) { "" }
    }

    fun differenceMinutes(leg: FlightPlanLegSnapshot, actual: Long): Int {
        var expected = parseEpoch(leg.expectedArrivalAt)
        // cmpg-double / if-gtz also enters for NaN. A plain Kotlin >0 differs.
        if (expected == null && leg.scheduledDepartureAt.isNotEmpty() && !(leg.distanceKm <= 0.0)) {
            expected = parseEpoch(leg.scheduledDepartureAt)?.let { it + sourceDurationMillis(leg.distanceKm) }
        }
        if (actual <= 0 || expected == null || expected <= 0) return Int.MIN_VALUE
        val delta = actual - expected
        val magnitude = abs(delta)
        if (magnitude <= 60_000L) return 0
        // Deliberately preserve long division then JVM narrowing before max.
        val minutes = max(1, (magnitude / 60_000L).toInt())
        return if (delta < 0) -minutes else minutes
    }

    private fun sourceDurationMillis(distance: Double): Long =
        if (!distance.isFinite() || distance <= 0.0) 0 else ((distance / 800.0) * 3_600_000.0).toLong()

    /** Card receipt lookup: device departure day first, then UTC day of its local midnight. */
    fun receiptDays(leg: FlightPlanLegSnapshot, deviceZone: TimeZone): List<String> {
        val departure = parseEpoch(leg.scheduledDepartureAt) ?: return emptyList()
        val local = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = deviceZone }
        val first = local.format(Date(departure))
        val midnight = local.parse(first) ?: return listOf(first)
        val second = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(midnight)
        return listOf(first, second).distinct()
    }
}
