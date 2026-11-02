package com.projectfocus.feature.booking.draft


data class BookingDraft(
    val flightNo: String,
    val fromCode: String,
    val toCode: String,
    val seat: String,
    val savedAtMillis: Long,
    /** 来源记录的 id；主页预览/带航班深链时为空。 */
    val recordId: String = "",
)
