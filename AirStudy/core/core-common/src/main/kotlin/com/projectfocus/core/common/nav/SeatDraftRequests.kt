package com.projectfocus.core.common.nav









object SeatDraftRequests {
    const val CLEAR: String = "seat_clear_draft"

    /** 选座确认回传：请求键。 */
    const val SELECTION: String = "seat_selection_result"

    /** 选座确认回传：座位值键（旧的裸字符串）。 */
    const val SEAT: String = "seat"

    /** 选座确认回传：`SeatSelectionResult` 对象键。 */
    const val RESULT_DATA: String = "seat_selection_result_data"
}
