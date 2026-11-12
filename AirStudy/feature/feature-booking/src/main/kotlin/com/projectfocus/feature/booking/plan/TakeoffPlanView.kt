package com.projectfocus.feature.booking.plan

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Build
import android.graphics.Rect
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupWindow
import java.util.Locale
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.ViewCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.projectfocus.feature.booking.R
import com.google.android.material.card.MaterialCardView
import com.projectfocus.core.ui.R as CoreUiR


class TakeoffPlanView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    var callbacks = TakeoffPlanCallbacks()

    private var renderedState = TakeoffPlanUiState()
    private var rendering = false
    private var segmentMenu: PopupWindow? = null
    private val floating: View
    private val compactDestination: View
    private val compactSegment: View
    private val expanded: View
    private val header: TextView
    private val toggle: SwitchCompat
    private val segmentAdapter = SegmentAdapter()
    private val hourWheel: SourceStringWheelPicker
    private val minuteWheel: SourceStringWheelPicker
    private val hours = (0..23).map { String.format(Locale.getDefault(), "%02d", it) }
    private val minutes = (0..59).map { String.format(Locale.getDefault(), "%02d", it) }
    private var entitlementGate: PlanEntitlementGateView? = null
    private var expandedMinimumHeight = 0

    init {
        LayoutInflater.from(context).inflate(R.layout.pf_takeoff_plan, this, true)
        floating = findViewById(R.id.pf_tp_floating_destination_container)
        compactDestination = findViewById(R.id.pf_tp_set_destination_compact_container)
        compactSegment = findViewById(R.id.pf_tp_add_segment_compact_container)
        expanded = findViewById(R.id.pf_tp_expanded_panel)
        expandedMinimumHeight = expanded.minimumHeight
        header = findViewById(R.id.pf_tp_header)
        // The source floating editor is elevated. Loading must paint above it.
        findViewById<View>(R.id.pf_tp_loading).elevation = floating.elevation
        toggle = expanded.findViewById(R.id.pf_tp_switch)
        expanded.findViewById<RecyclerView>(R.id.pf_tp_segment_list).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = segmentAdapter
            isNestedScrollingEnabled = false
        }
        header.setOnClickListener { if (interactive()) callbacks.onExpand(renderedState.panel != TakeoffPlanPanel.EXPANDED) }
        action(floating, R.id.pf_tp_floating_back, R.string.pf_tp_back_a11y) {
            callbacks.onBack?.invoke() ?: callbacks.onExpand(false)
        }
        action(expanded, R.id.pf_tp_prev_date, R.string.pf_tp_previous_date_a11y) {
            if (renderedState.canBrowsePreviousDate) callbacks.onDateOffset(-1)
        }
        action(expanded, R.id.pf_tp_next_date, R.string.pf_tp_next_date_a11y) {
            if (renderedState.canBrowseNextDate) callbacks.onDateOffset(1)
        }
        floating.findViewById<View>(R.id.pf_tp_floating_origin).setOnClickListener {
            if (interactive()) callbacks.onSelectOrigin()
        }
        floating.findViewById<View>(R.id.pf_tp_floating_destination).setOnClickListener {
            if (interactive()) callbacks.onSelectDestination()
        }
        floating.findViewById<View>(R.id.pf_tp_floating_scene_selector).setOnClickListener {
            if (interactive()) callbacks.onSelectScene()
        }
        hourWheel = floating.findViewById(R.id.pf_tp_floating_time_hour)
        minuteWheel = floating.findViewById(R.id.pf_tp_floating_time_minute)
        hourWheel.onItemSelected = { onClockChanged() }
        minuteWheel.onItemSelected = { onClockChanged() }
        floating.findViewById<View>(R.id.pf_tp_floating_save).setOnClickListener { confirmEditor() }
        floating.findViewById<View>(R.id.pf_tp_floating_time_save).setOnClickListener { confirmEditor() }
        compactDestination.findViewById<View>(R.id.pf_tp_set_destination_save).setOnClickListener {
            if (canConfirm()) callbacks.onConfirmDestination()
        }
        compactDestination.findViewById<View>(R.id.pf_tp_set_destination_route).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { if (interactive()) callbacks.onSelectDestination() }
        }
        compactSegment.findViewById<View>(R.id.pf_tp_set_destination_action).setOnClickListener {
            if (interactive()) {
                if (renderedState.confirmMode) { if (canConfirm()) callbacks.onConfirmPlan() }
                else callbacks.onSelectDestination()
            }
        }
        expanded.findViewById<View>(R.id.pf_tp_add_segment).setOnClickListener {
            if (interactive() && renderedState.canAddSegment) callbacks.onAddSegment()
        }
        expanded.findViewById<View>(R.id.pf_tp_flight_seat).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { if (interactive()) callbacks.onSeat() }
        }
        toggle.setOnCheckedChangeListener { _, checked ->
            if (!rendering && interactive() && renderedState.canTogglePlan) callbacks.onPlanEnabled(checked)
        }
        render(renderedState)
    }

    /** Pure binding: re-rendering never calls an action or invents a segment. */
    fun render(state: TakeoffPlanUiState) {
        dismissSegmentMenu()
        renderedState = state
        rendering = true
        try {
            floating.visibility = visible(state.panel == TakeoffPlanPanel.FLOATING_DESTINATION)
            compactDestination.visibility = visible(state.panel == TakeoffPlanPanel.SET_DESTINATION_COMPACT)
            compactSegment.visibility = visible(state.panel == TakeoffPlanPanel.ADD_SEGMENT_COMPACT)
            expanded.visibility = visible(state.panel == TakeoffPlanPanel.EXPANDED)
            header.visibility = visible(state.panel != TakeoffPlanPanel.FLOATING_DESTINATION)
            header.contentDescription = context.getString(
                if (state.panel == TakeoffPlanPanel.EXPANDED) R.string.pf_tp_collapse_a11y else R.string.pf_tp_expand_a11y,
            )
            bindFloating(state)
            text(compactDestination, R.id.pf_tp_set_destination_route, route(state.origin, state.destination))
            compactDestination.findViewById<View>(R.id.pf_tp_set_destination_route).contentDescription =
                airportDescription(false, state.destination)
            bindSegment(compactSegment.findViewById(R.id.pf_tp_segment_card), state.segments.firstOrNull(), null)
            text(compactSegment, R.id.pf_tp_set_destination_action, context.getString(
                if (state.confirmMode) R.string.pf_tp_common_save else R.string.pf_tp_takeoff_plan_step1_set_destination_action,
            ))
            text(expanded, R.id.pf_tp_date_title, value(state.dateText))
            toggle.isChecked = state.planEnabled
            segmentAdapter.items = state.segments.toList()
            segmentAdapter.notifyDataSetChanged()
            val footer = expanded.findViewById<View>(R.id.pf_tp_footer_container)
            footer.visibility = View.VISIBLE
            text(expanded, R.id.pf_tp_total_distance, state.totalDistanceText?.takeIf { it.isNotBlank() }?.let {
                context.getString(R.string.pf_tp_takeoff_plan_total_distance_fmt, it, "")
            } ?: na())
            val seat = state.seatText?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.pf_tp_takeoff_plan_seat_not_confirmed)
            text(expanded, R.id.pf_tp_flight_seat, seat)
            expanded.findViewById<View>(R.id.pf_tp_flight_seat).contentDescription =
                context.getString(R.string.pf_tp_seat_a11y, seat)
            // No simulated flight progress. Source's zero-progress baseline stays zero.
            expanded.findViewById<View>(R.id.pf_tp_distance_progress).visibility = View.GONE
            setControlsEnabled(this, interactive())
            toggle.isEnabled = interactive() && state.canTogglePlan
            expanded.findViewById<View>(R.id.pf_tp_prev_date).isEnabled = interactive() && state.canBrowsePreviousDate
            expanded.findViewById<View>(R.id.pf_tp_next_date).isEnabled = interactive() && state.canBrowseNextDate
            floating.findViewById<View>(R.id.pf_tp_floating_save).isEnabled = canConfirm()
            floating.findViewById<View>(R.id.pf_tp_floating_time_save).isEnabled = canConfirm()
            compactDestination.findViewById<View>(R.id.pf_tp_set_destination_save).isEnabled = canConfirm()
            expanded.findViewById<View>(R.id.pf_tp_add_segment).isEnabled = interactive() && state.canAddSegment
            compactSegment.findViewById<View>(R.id.pf_tp_set_destination_action).isEnabled =
                interactive() && (!state.confirmMode || canConfirm())
            floating.findViewById<View>(R.id.pf_tp_floating_time_save).alpha = if (state.canSave) 1f else .45f
            // The API31 source compact layout has no local loading overlay.
            compactSegment.findViewById<View>(R.id.pf_tp_add_segment_loading_overlay)?.visibility = View.GONE
            findViewById<View>(R.id.pf_tp_loading).visibility = visible(state.loading)
            bindEntitlementGate(state)
            requestLayout()
            post { installTouchTargets() }
        } finally {
            rendering = false
        }
    }

    private fun bindEntitlementGate(state: TakeoffPlanUiState) {
        val parent = expanded as FrameLayout
        if (!state.entitlementGateVisible || state.panel != TakeoffPlanPanel.EXPANDED) {
            entitlementGate?.let {
                parent.removeView(it)
                expanded.findViewById<View>(R.id.pf_tp_content_scroll).visibility = View.VISIBLE
                expanded.findViewById<View>(R.id.pf_tp_footer_container).visibility = View.VISIBLE
            }
            entitlementGate = null
            expanded.minimumHeight = expandedMinimumHeight
            return
        }
        expanded.findViewById<View>(R.id.pf_tp_content_scroll).visibility = View.GONE
        expanded.findViewById<View>(R.id.pf_tp_footer_container).visibility = View.GONE
        // Own standalone page gives the original full-parent overlay a measured
        // host after its wrap-content scroll/footer are hidden. Main's map parent
        // sizing is still a separate integration gap.
        expanded.minimumHeight = maxOf(expandedMinimumHeight, (280 * resources.displayMetrics.density).toInt())
        val gate = entitlementGate ?: PlanEntitlementGateView(context).also {
            parent.addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                topMargin = (44 * resources.displayMetrics.density).toInt()
            })
            entitlementGate = it
        }
        gate.bind(state.entitlementGateAuthenticated)
        gate.onAccountAction = { callbacks.onAccountAction() }
    }

    private fun bindFloating(state: TakeoffPlanUiState) {
        text(floating, R.id.pf_tp_floating_dep_code, value(state.origin?.code))
        text(floating, R.id.pf_tp_floating_dep_city, value(state.origin?.city))
        text(floating, R.id.pf_tp_floating_arr_code, value(state.destination?.code))
        text(floating, R.id.pf_tp_floating_arr_city, value(state.destination?.city))
        floating.findViewById<View>(R.id.pf_tp_floating_origin).contentDescription = airportDescription(true, state.origin)
        floating.findViewById<View>(R.id.pf_tp_floating_destination).contentDescription = airportDescription(false, state.destination)
        val step = state.editorStep
        text(floating, R.id.pf_tp_floating_title, context.getString(when (step) {
            TakeoffPlanEditorStep.DESTINATION -> R.string.pf_tp_takeoff_plan_step1_set_destination_title
            TakeoffPlanEditorStep.SCENE -> R.string.pf_tp_takeoff_plan_step2_set_scene_title
            TakeoffPlanEditorStep.DEPARTURE_TIME -> R.string.pf_tp_takeoff_plan_step3_set_time_title
        }))
        floating.findViewById<View>(R.id.pf_tp_floating_step12_container).visibility = visible(step != TakeoffPlanEditorStep.DEPARTURE_TIME)
        floating.findViewById<View>(R.id.pf_tp_floating_route_container).visibility = visible(step == TakeoffPlanEditorStep.DESTINATION)
        floating.findViewById<View>(R.id.pf_tp_floating_scene_container).visibility = visible(step == TakeoffPlanEditorStep.SCENE)
        floating.findViewById<View>(R.id.pf_tp_floating_time_container).visibility = visible(step == TakeoffPlanEditorStep.DEPARTURE_TIME)
        text(floating, R.id.pf_tp_floating_scene_text, state.sceneText ?: context.getString(R.string.pf_tp_all_scenarios))
        floating.findViewById<ImageView>(R.id.pf_tp_floating_scene_icon).apply {
            val icon = state.sceneIconRes?.takeIf { it > 0 }
            visibility = visible(icon != null)
            if (icon == null) setImageDrawable(null) else setImageResource(icon)
            if (state.sceneColor == null) clearColorFilter() else setColorFilter(state.sceneColor)
        }
        val hour = if (state.departureHour != null) state.departureHour.takeIf { it in 0..23 }
            else state.departureHourText?.toIntOrNull()?.takeIf { it in 0..23 }
        val minute = if (state.departureMinute != null) state.departureMinute.takeIf { it in 0..59 }
            else state.departureMinuteText?.toIntOrNull()?.takeIf { it in 0..59 }
        bindWheel(hourWheel, hours, hour)
        bindWheel(minuteWheel, minutes, minute)
        text(floating, R.id.pf_tp_floating_time_save, state.timeActionText ?: context.getString(R.string.pf_tp_takeoff_plan_time_preview))
        floating.findViewById<View>(R.id.pf_tp_floating_time_hour).contentDescription =
            context.getString(R.string.pf_tp_departure_hour_a11y, value(hourWheel.getSelectedItem()))
        floating.findViewById<View>(R.id.pf_tp_floating_time_minute).contentDescription =
            context.getString(R.string.pf_tp_departure_minute_a11y, value(minuteWheel.getSelectedItem()))
        text(floating, R.id.pf_tp_floating_eta, value(state.etaText))
    }

    private fun bindWheel(wheel: SourceStringWheelPicker, values: List<String>, selected: Int?) {
        val entries = if (selected == null) emptyList() else values
        if (!wheel.hasData(entries)) wheel.setData(entries)
        if (selected != null && selected != wheel.getSelectedIndex()) wheel.setSelectedIndex(selected)
        wheel.setColors(context.getColor(CoreUiR.color.pf_tp_plan_card_picker_normal), context.getColor(CoreUiR.color.pf_tp_plan_card_picker_selected))
    }

    private fun onClockChanged() {
        if (rendering || !interactive()) return
        if (hourWheel.getSelectedItem() == null || minuteWheel.getSelectedItem() == null) return
        callbacks.onDepartureTimeChanged(hourWheel.getSelectedIndex(), minuteWheel.getSelectedIndex())
    }

    private fun bindSegment(view: View, segment: TakeoffPlanSegmentUi?, index: Int?) {
        val fullList = index != null
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val primary = if (fullList && !night) context.getColor(R.color.pf_tp_segment_primary_day) else Color.WHITE
        val secondary = context.getColor(if (fullList && !night) R.color.pf_tp_segment_secondary_day else R.color.pf_tp_segment_secondary_night)
        if (fullList && view is MaterialCardView) {
            view.setCardBackgroundColor(context.getColor(when {
                Build.VERSION.SDK_INT >= 33 && night -> R.color.pf_tp_segment_bg_blur_night
                Build.VERSION.SDK_INT >= 33 -> R.color.pf_tp_segment_bg_blur_day
                night -> R.color.pf_tp_segment_bg_night
                else -> R.color.pf_tp_segment_bg_day
            }))
        }
        text(view, R.id.pf_tp_segment_tag, segment?.tagText?.takeIf { it.isNotBlank() }
            ?: index?.let { context.getString(R.string.pf_tp_takeoff_plan_segment_label_fmt, it + 1) }
            ?: context.getString(R.string.pf_tp_takeoff_plan_add_segment_draft))
        text(view, R.id.pf_tp_dep_code, segmentValue(segment?.origin?.code))
        text(view, R.id.pf_tp_dep_city, segmentValue(segment?.origin?.city))
        text(view, R.id.pf_tp_arr_code, segmentValue(segment?.destination?.code))
        text(view, R.id.pf_tp_arr_city, segmentValue(segment?.destination?.city))
        text(view, R.id.pf_tp_departure_time, segmentValue(segment?.departureTimeText))
        text(view, R.id.pf_tp_arrival_time, segmentValue(segment?.arrivalTimeText))
        text(view, R.id.pf_tp_distance, segmentValue(segment?.distanceText))
        listOf(R.id.pf_tp_dep_code, R.id.pf_tp_arr_code, R.id.pf_tp_distance).forEach {
            view.findViewById<TextView>(it).setTextColor(primary)
        }
        listOf(R.id.pf_tp_dep_city, R.id.pf_tp_arr_city, R.id.pf_tp_departure_label, R.id.pf_tp_arrival_label).forEach {
            view.findViewById<TextView>(it).setTextColor(secondary)
        }
        val timeColor = segment?.timeColor ?: primary
        view.findViewById<TextView>(R.id.pf_tp_departure_time).setTextColor(timeColor)
        view.findViewById<TextView>(R.id.pf_tp_arrival_time).setTextColor(timeColor)
        view.findViewById<ImageView>(R.id.pf_tp_flight_arrow).setColorFilter(secondary, PorterDuff.Mode.SRC_IN)
        val title = segment?.sceneText?.trim { it <= ' ' }?.takeIf { it.isNotEmpty() }
        text(view, R.id.pf_tp_focus_scene, title ?: context.getString(R.string.pf_tp_common_not_set))
        val sceneColor = segment?.sceneColor?.takeIf { it != 0 }
        view.findViewById<TextView>(R.id.pf_tp_focus_scene).setTextColor(sceneColor ?: primary)
        view.findViewById<ImageView>(R.id.pf_tp_focus_icon).apply {
            visibility = View.VISIBLE
            setImageResource(segment?.sceneIconRes?.takeIf { it > 0 } ?: R.drawable.pf_tp_ic_focus_study)
            // Remove the template AppCompat tint as well as its currently applied
            // filter. Source type0 explicitly clears tint; it does not paint black.
            val tint = sceneColor?.let(ColorStateList::valueOf)
            if (this is AppCompatImageView) supportImageTintList = tint
            imageTintList = tint
            clearColorFilter()
            if (sceneColor != null) setColorFilter(sceneColor, PorterDuff.Mode.SRC_IN)
        }
        text(view, R.id.pf_tp_segment_status, segment?.statusText?.trim { it <= ' ' }?.takeIf { it.isNotEmpty() }
            ?: context.getString(R.string.pf_tp_takeoff_plan_status_waiting_takeoff))
        view.findViewById<TextView>(R.id.pf_tp_segment_status).setTextColor(
            segment?.statusColor ?: context.getColor(R.color.pf_tp_plan_status_waiting),
        )
        view.findViewById<TextView>(R.id.pf_tp_segment_status).apply {
            val style = segment?.statusTextStyle ?: 0
            try { setTypeface(ResourcesCompat.getFont(context, CoreUiR.font.pf_apk2_harmonyos_sans_sc_medium), style) }
            catch (_: Throwable) { setTypeface(null, style) } // Original j.a fallback.
        }
        view.alpha = segment?.terminalAlpha?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        delay(view, R.id.pf_tp_departure_delay, segment?.departureDelayText)
        delay(view, R.id.pf_tp_arrival_delay, segment?.arrivalDelayText)
        ViewCompat.setScreenReaderFocusable(view, true)
        view.contentDescription = context.getString(R.string.pf_tp_segment_a11y,
            route(segment?.origin, segment?.destination), segmentValue(segment?.departureTimeText),
            segmentValue(segment?.arrivalTimeText), segmentValue(segment?.distanceText), segmentValue(segment?.statusText))
        ViewCompat.removeAccessibilityAction(view, AccessibilityActionCompat.ACTION_DISMISS.id)
        // Existing compatibility action remains; the source's ordinary entrance
        // is the measured row's long-press menu below.
        if (segment?.removable == true) {
            ViewCompat.replaceAccessibilityAction(view, AccessibilityActionCompat.ACTION_DISMISS,
                context.getString(R.string.pf_tp_remove_segment_a11y)) { _, _ ->
                if (interactive() && renderedState.segments.any { it.id == segment.id && it.removable }) {
                    callbacks.onRemoveSegment(segment.id)
                    true
                } else false
            }
        }
        val longClickable = fullList && segment != null &&
            (segment.contextMenuEnabled || segment.editable || segment.removable || segment.batchDeleteCanceled)
        val touch = floatArrayOf(0f, 0f)
        view.setOnTouchListener(if (longClickable) View.OnTouchListener { _, event ->
            touch[0] = event.rawX
            touch[1] = event.rawY
            false
        } else null)
        view.setOnLongClickListener(if (longClickable) View.OnLongClickListener { row ->
            showSegmentMenu(row, checkNotNull(segment).id, touch[0], touch[1])
        } else null)
        view.isLongClickable = longClickable
    }

    private fun segmentValue(value: String?): String =
        value?.trim { it <= ' ' }?.takeIf { it.isNotEmpty() } ?: "--"

    private fun showSegmentMenu(row: View, id: String, rawX: Float, rawY: Float): Boolean {
        if (!interactive() || renderedState.panel != TakeoffPlanPanel.EXPANDED || row.windowToken == null) return false
        val item = renderedState.segments.firstOrNull { it.id == id } ?: return false
        if (!(item.contextMenuEnabled || item.editable || item.removable || item.batchDeleteCanceled)) return false
        dismissSegmentMenu()
        val menu = SourcePlanSegmentMenu(row, item.editable, item.removable,
            onEdit = {
                val current = renderedState.segments.firstOrNull { it.id == id }
                if (interactive() && renderedState.panel == TakeoffPlanPanel.EXPANDED && current?.editable == true) {
                    callbacks.onEditSegment(id)
                }
            },
            onDelete = {
                val current = renderedState.segments.firstOrNull { it.id == id }
                if (interactive() && renderedState.panel == TakeoffPlanPanel.EXPANDED && current?.removable == true) {
                    callbacks.onRemoveSegment(id)
                }
            },
            batchDeleteCanceled = item.batchDeleteCanceled,
            onBatchDelete = {
                val current = renderedState.segments.firstOrNull { it.id == id }
                if (interactive() && renderedState.panel == TakeoffPlanPanel.EXPANDED && current?.batchDeleteCanceled == true) {
                    callbacks.onDeleteCanceledSegments(id)
                }
            },
        )
        segmentMenu = menu.popup
        menu.popup.setOnDismissListener { if (segmentMenu === menu.popup) segmentMenu = null }
        menu.show(rawX, rawY)
        return true
    }

    private fun dismissSegmentMenu() {
        val previous = segmentMenu
        segmentMenu = null
        previous?.dismiss()
    }

    override fun onDetachedFromWindow() {
        dismissSegmentMenu()
        super.onDetachedFromWindow()
    }

    private fun delay(root: View, id: Int, value: String?) {
        root.findViewById<TextView>(id).apply {
            text = value.orEmpty()
            visibility = visible(!value.isNullOrBlank())
        }
    }

    private fun confirmEditor() {
        if (!canConfirm()) return
        when (renderedState.editorStep) {
            TakeoffPlanEditorStep.DESTINATION -> callbacks.onConfirmDestination()
            TakeoffPlanEditorStep.SCENE -> callbacks.onConfirmScene()
            TakeoffPlanEditorStep.DEPARTURE_TIME -> callbacks.onConfirmTime()
        }
    }

    private fun canConfirm() = interactive() && renderedState.canSave

    private fun interactive() = renderedState.enabled && !renderedState.loading
    private fun na() = context.getString(R.string.pf_tp_common_na)
    private fun value(value: String?) = value?.takeIf { it.isNotBlank() } ?: na()
    private fun visible(condition: Boolean) = if (condition) View.VISIBLE else View.GONE
    private fun text(root: View, id: Int, value: String) { root.findViewById<TextView>(id).text = value }
    private fun route(origin: TakeoffPlanAirportUi?, destination: TakeoffPlanAirportUi?) =
        context.getString(R.string.pf_tp_route_fmt, value(origin?.city), value(origin?.code),
            value(destination?.city), value(destination?.code))

    private fun airportDescription(origin: Boolean, airport: TakeoffPlanAirportUi?) = context.getString(
        if (origin) R.string.pf_tp_select_origin_a11y else R.string.pf_tp_select_destination_a11y,
        value(airport?.code), value(airport?.city),
    )

    private fun action(root: View, id: Int, description: Int, callback: () -> Unit) {
        root.findViewById<View>(id).apply {
            contentDescription = context.getString(description)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnClickListener { if (interactive()) callback() }
        }
    }

    private fun setControlsEnabled(view: View, enabled: Boolean) {
        if (view.isClickable || view.isFocusable) view.isEnabled = enabled
        if (view is ViewGroup) for (index in 0 until view.childCount) setControlsEnabled(view.getChildAt(index), enabled)
    }

    private fun installTouchTargets() {
        val minimum = (48 * resources.displayMetrics.density).toInt()
        val delegates = mutableListOf<TouchDelegate>()
        fun collect(view: View) {
            if (view.visibility != View.VISIBLE) return
            if (view.isClickable && view.isEnabled && view.width > 0 && view.height > 0 && view !== this) {
                val bounds = Rect(0, 0, view.width, view.height)
                offsetDescendantRectToMyCoords(view, bounds)
                val dx = ((minimum - bounds.width()).coerceAtLeast(0) + 1) / 2
                val dy = ((minimum - bounds.height()).coerceAtLeast(0) + 1) / 2
                if (dx > 0 || dy > 0) {
                    bounds.inset(-dx, -dy)
                    if (bounds.intersect(0, 0, width, height)) {
                        delegates += TouchDelegate(bounds, view)
                    }
                }
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
        }
        collect(this)
        touchDelegate = object : TouchDelegate(Rect(), this) {
            override fun onTouchEvent(event: MotionEvent): Boolean = delegates.any { delegate ->
                val copy = MotionEvent.obtain(event)
                try { delegate.onTouchEvent(copy) } finally { copy.recycle() }
            }
        }
    }

    private inner class SegmentAdapter : RecyclerView.Adapter<SegmentHolder>() {
        var items: List<TakeoffPlanSegmentUi> = emptyList()
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = SegmentHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.pf_item_flight_segment, parent, false),
        )
        override fun onBindViewHolder(holder: SegmentHolder, position: Int) {
            bindSegment(holder.itemView, items[position], position)
        }
    }

    private class SegmentHolder(view: View) : RecyclerView.ViewHolder(view)
}
