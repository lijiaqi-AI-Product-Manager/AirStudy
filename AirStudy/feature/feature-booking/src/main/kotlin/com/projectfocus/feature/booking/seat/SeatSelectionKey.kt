package com.projectfocus.feature.booking.seat


internal fun seatDraftKey(
    recordId: String,
    draftKey: String,
    flightNo: String,
): String = when {
    recordId.isNotBlank() -> recordId
    draftKey.isNotBlank() -> draftKey
    else -> flightNo
}
