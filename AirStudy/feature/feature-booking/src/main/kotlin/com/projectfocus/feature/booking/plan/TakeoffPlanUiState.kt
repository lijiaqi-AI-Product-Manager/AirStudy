package com.projectfocus.feature.booking.plan

/** Display-only input. The host owns airport identity, calculation, validation and persistence. */
data class TakeoffPlanAirportUi(
    val code: String,
    val city: String,
)

enum class TakeoffPlanPanel {
    FLOATING_DESTINATION,
    SET_DESTINATION_COMPACT,
    ADD_SEGMENT_COMPACT,
    EXPANDED,
}

enum class TakeoffPlanEditorStep {
    DESTINATION,
    SCENE,
    DEPARTURE_TIME,
}

/** No example airport, time, route or distance is supplied by this component. */
data class TakeoffPlanSegmentUi(
    val id: String,
    val origin: TakeoffPlanAirportUi?,
    val destination: TakeoffPlanAirportUi?,
    val tagText: String? = null,
    val departureTimeText: String? = null,
    val arrivalTimeText: String? = null,
    val distanceText: String? = null,
    val sceneText: String? = null,
    val statusText: String? = null,
    val departureDelayText: String? = null,
    val arrivalDelayText: String? = null,
    val removable: Boolean = false,
    val sceneIconRes: Int? = null,
    val sceneColor: Int? = null,
    /** Source status resource resolved by the host; null retains waiting/gold. */
    val statusColor: Int? = null,
    /** Source completed/canceled rows use .45; the host owns status classification. */
    val terminalAlpha: Float = 1f,
    val editable: Boolean = false,
    /** Allows the source menu to show two disabled rows for an earlier segment. */
    val contextMenuEnabled: Boolean = false,
    /** Canceled times are red; completed times retain normal primary text. */
    val timeColor: Int? = null,
    val batchDeleteCanceled: Boolean = false,
    /** Original completion classification uses normal, bold, or bold-italic. */
    val statusTextStyle: Int = 0,
)

data class TakeoffPlanUiState(
    val panel: TakeoffPlanPanel = TakeoffPlanPanel.FLOATING_DESTINATION,
    val origin: TakeoffPlanAirportUi? = null,
    val destination: TakeoffPlanAirportUi? = null,
    val segments: List<TakeoffPlanSegmentUi> = emptyList(),
    val dateText: String? = null,
    val totalDistanceText: String? = null,
    val seatText: String? = null,
    val sceneText: String? = null,
    val planEnabled: Boolean = false,
    val canSave: Boolean = false,
    val canAddSegment: Boolean = false,
    val loading: Boolean = false,
    val enabled: Boolean = true,
    val editorStep: TakeoffPlanEditorStep = TakeoffPlanEditorStep.DESTINATION,
    val departureHourText: String? = null,
    val departureMinuteText: String? = null,
    val etaText: String? = null,
    /** Typed values take priority over the legacy presentation strings. */
    val departureHour: Int? = null,
    val departureMinute: Int? = null,
    /** Caller maps a real scene; null hides the source's optional icon. */
    val sceneIconRes: Int? = null,
    val sceneColor: Int? = null,
    /** Source state3 J: save a completed draft, otherwise open destination editing. */
    val confirmMode: Boolean = false,
    /** Source U() owns preview/earliest/select-first copy; host supplies its decision. */
    val timeActionText: String? = null,
    /** Host availability, independent of source global plan on/off state. */
    val canTogglePlan: Boolean = true,
    val canBrowsePreviousDate: Boolean = true,
    val canBrowseNextDate: Boolean = true,
    val entitlementGateVisible: Boolean = false,
    val entitlementGateAuthenticated: Boolean = false,
)

/** "Save" confirms the editor; starting a flight remains the host's separate action. */
data class TakeoffPlanCallbacks(
    val onSelectOrigin: () -> Unit = {},
    val onSelectDestination: () -> Unit = {},
    val onConfirmDestination: () -> Unit = {},
    val onSeat: () -> Unit = {},
    val onExpand: (Boolean) -> Unit = {},
    val onAddSegment: () -> Unit = {},
    val onRemoveSegment: (String) -> Unit = {},
    val onDateOffset: (Int) -> Unit = {},
    val onPlanEnabled: (Boolean) -> Unit = {},
    val onSelectScene: () -> Unit = {},
    val onSelectTime: () -> Unit = {},
    val onConfirmScene: () -> Unit = {},
    val onConfirmTime: () -> Unit = {},
    /** Source floating back arrow. Null preserves the existing collapse callback. */
    val onBack: (() -> Unit)? = null,
    val onDepartureTimeChanged: (hour: Int, minute: Int) -> Unit = { _, _ -> },
    val onConfirmPlan: () -> Unit = {},
    val onEditSegment: (String) -> Unit = {},
    val onDeleteCanceledSegments: (String) -> Unit = {},
    val onAccountAction: () -> Unit = {},
)
