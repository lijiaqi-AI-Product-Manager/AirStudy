package com.projectfocus.feature.booking.seat

import com.projectfocus.core.common.seat.SeatMapGeometry


internal fun canonicalSeat(raw: String?): String? {
    if (raw == null) return null
    val match = Regex("^(0?[1-9]|[12][0-9]|30)([ACDF])$").matchEntire(raw) ?: return null
    val row = match.groupValues[1].toInt() - 1
    val column = SeatMapGeometry.COLUMNS.indexOf(match.groupValues[2])
    return SeatMapGeometry.label(row, column)
}
