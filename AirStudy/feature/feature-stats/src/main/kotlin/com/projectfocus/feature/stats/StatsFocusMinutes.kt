package com.projectfocus.feature.stats

import com.projectfocus.service.focus.history.RecordedFlight

internal object StatsFocusMinutes {
    fun value(record: RecordedFlight): Double = when (record) {
        is RecordedFlight.Countdown -> record.record.actualFocusMinutes.coerceAtLeast(0).toDouble()
        is RecordedFlight.CountUp -> record.archive.clock.activeElapsedMs / 60_000.0
    }
}
