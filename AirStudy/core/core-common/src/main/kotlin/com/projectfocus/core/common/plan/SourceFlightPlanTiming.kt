package com.projectfocus.core.common.plan


object SourceFlightPlanTiming {
    data class Segment(val status: String?, val departureMillis: Long?, val distanceKm: Double)

    fun durationMillis(distanceKm: Double): Long =
        if (!distanceKm.isFinite() || distanceKm <= 0.0) 0L else ((distanceKm / 800.0) * 3_600_000.0).toLong()

    /** One captured now, input order unchanged, result indexed by position. */
    fun delays(segments: List<Segment?>, nowMillis: Long): List<Long> {
        var carry = -1L
        return segments.map { leg ->
            if (leg == null) return@map 0L
            val status = SourceFlightPlanStatus.parse(leg.status?.lowercase(java.util.Locale.US))
            if (status in setOf(SourceFlightPlanStatus.CANCELED, SourceFlightPlanStatus.UNKNOWN)) {
                carry = -1L
                return@map 0L
            }
            val departure = leg.departureMillis ?: return@map 0L
            when (status) {
                SourceFlightPlanStatus.COMPLETED -> {
                    carry = departure + durationMillis(leg.distanceKm)
                    0L
                }
                SourceFlightPlanStatus.IN_PROGRESS -> {
                    carry = nowMillis + durationMillis(leg.distanceKm)
                    0L
                }
                SourceFlightPlanStatus.WAITING -> {
                    val earliest = maxOf(nowMillis, if (carry > 0L) carry + 120_000L else nowMillis)
                    val delay = maxOf(0L, earliest - departure)
                    carry = maxOf(departure, earliest) + durationMillis(leg.distanceKm)
                    delay
                }
                else -> { carry = -1L; 0L }
            }
        }
    }
}
