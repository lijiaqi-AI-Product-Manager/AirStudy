package com.projectfocus.core.common.plan


object SourceFlightArrivalOutcome {
    enum class Kind { ON_TIME, EARLY, DELAYED }
    data class Outcome(val kind: Kind, val minutes: Int)

    fun classify(actual: Long?, expected: Long?): Outcome? {
        if (actual == null || expected == null || actual <= 0L || expected <= 0L) return null
        val difference = actual - expected
        val magnitude = kotlin.math.abs(difference)
        if (magnitude <= 60_000L) return Outcome(Kind.ON_TIME, 0)
        // Original narrows the integer quotient to Int before Math.max.
        return Outcome(if (difference < 0L) Kind.EARLY else Kind.DELAYED,
            maxOf(1, (magnitude / 60_000L).toInt()))
    }
}
