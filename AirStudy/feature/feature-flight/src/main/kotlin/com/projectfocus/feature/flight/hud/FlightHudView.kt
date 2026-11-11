package com.projectfocus.feature.flight.hud

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import com.projectfocus.feature.flight.R

data class FlightHudUiState(
    val active: Boolean = false, val paused: Boolean = false, val windowVisible: Boolean = false,
    val lightIcons: Boolean = true,
    val routeText: String = "", val remainingTimeText: String = "", val remainingMinutesText: String = "",
    val remainingKmText: String = "", val traveledKmText: String = "", val progress: Float = 0f,
    val counters: FlightHudCounters? = null,
    val freeFlightLandControl: FreeFlightLandControl = FreeFlightLandControl.HIDDEN,
    val mapAvailable: Boolean = true, val mapRetryEnabled: Boolean = false,
    val mapStatusText: String = "", val elapsed: Boolean = false,
)

enum class FreeFlightLandControl { HIDDEN, LAND, RETURN_TO_CRUISE }

data class FlightHudCallbacks(
    val onPauseResume: () -> Unit = {}, val onAbandon: () -> Unit = {},
    val onToggleWindow: () -> Unit = {}, val onMapStyle: () -> Unit = {},
    val onLandOrReturn: () -> Unit = {},
    val onRetryMap: () -> Unit = {},
)

/** Visible flight controls follow activity_main; app state mapping is explicit, not recovered DEX. */
class FlightHudView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : RelativeLayout(context, attrs) {
    var callbacks = FlightHudCallbacks()
    val ambientButton: TextView
    val volumeLabel: TextView
    val volumeControl: SeekBar
    private val pause: ImageView
    private val land: ImageView
    private val cancel: View
    private val window: ImageView
    private val audio: ImageView
    private val volumePanel: View
    private val counterWidths = SourceHudWidthAnimator()
    private var focusCompact = false
    private var currentState = FlightHudUiState()

    init {
        LayoutInflater.from(context).inflate(R.layout.pf_flight_hud, this, true)
        pause = findViewById(R.id.pf_flight_pause)
        land = findViewById(R.id.pf_flight_land)
        cancel = findViewById(R.id.pf_flight_cancel)
        window = findViewById(R.id.pf_flight_window_toggle)
        audio = findViewById(R.id.pf_flight_audio)
        volumePanel = findViewById(R.id.pf_flight_volume_panel)
        ambientButton = findViewById(R.id.pf_flight_ambient)
        volumeLabel = findViewById(R.id.pf_flight_volume_label)
        volumeControl = findViewById(R.id.pf_flight_volume)
        pause.setOnClickListener { callbacks.onPauseResume() }
        land.setOnClickListener { callbacks.onLandOrReturn() }
        cancel.setOnLongClickListener { callbacks.onAbandon(); true }
        cancel.setOnClickListener { announceForAccessibility(context.getString(R.string.pf_flight_hold_cancel)) }
        window.setOnClickListener { callbacks.onToggleWindow() }
        findViewById<View>(R.id.pf_flight_map_style).setOnClickListener { callbacks.onMapStyle() }
        audio.setOnClickListener { volumePanel.visibility = if (volumePanel.visibility == VISIBLE) GONE else VISIBLE }
        findViewById<View>(R.id.pf_flight_focus_retry).setOnClickListener { callbacks.onRetryMap() }
        listOf(R.id.pf_flight_left_controls, R.id.pf_flight_right_controls, R.id.pf_flight_volume_panel,
            R.id.pf_flight_distance_container, R.id.pf_flight_time_container).forEach {
            com.projectfocus.core.ui.sky.SkySurfaces.card(findViewById(it), radius = 24f, elevation = 4f, solid = true)
        }
        listOf(pause, land, window, audio, cancel, findViewById(R.id.pf_flight_map_style),
            findViewById<View>(R.id.pf_flight_focus_retry)).forEach {
            com.projectfocus.core.ui.sky.SkyPressFeedback.install(it)
        }
        com.projectfocus.core.ui.sky.SkySurfaces.secondary(cancel)
        render(FlightHudUiState())
    }

    fun render(state: FlightHudUiState) {
        currentState = state
        visibility = if (state.active) VISIBLE else GONE
        if (!state.active) { volumePanel.visibility = GONE; return }
        val selecting = state.freeFlightLandControl == FreeFlightLandControl.RETURN_TO_CRUISE
        pause.visibility = if (selecting) GONE else VISIBLE
        audio.visibility = if (selecting) GONE else VISIBLE
        land.visibility = if (state.freeFlightLandControl == FreeFlightLandControl.HIDDEN) GONE else VISIBLE
        land.setImageResource(if (selecting) R.drawable.pf_flight_ic_land_back else R.drawable.pf_flight_ic_land)
        land.contentDescription = context.getString(if (selecting) R.string.pf_flight_return_cruise else R.string.pf_flight_land)
        pause.setImageResource(if (state.paused) R.drawable.pf_flight_ic_play else R.drawable.pf_flight_ic_pause)
        pause.contentDescription = context.getString(if (state.paused) R.string.pf_flight_resume else R.string.pf_flight_pause)
        cancel.visibility = if (state.paused) VISIBLE else GONE
        findViewById<View>(R.id.pf_flight_right_controls).visibility = if (state.paused || selecting) GONE else VISIBLE
        val focus = !state.mapAvailable && !state.windowVisible
        renderFocus(state, focus)
        findViewById<View>(R.id.pf_flight_pause_overlay).visibility = if (state.paused && !focus) VISIBLE else GONE
        window.contentDescription = context.getString(if (state.windowVisible) R.string.pf_flight_show_map else R.string.pf_flight_show_window)
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val tint = ContextCompat.getColor(context, com.projectfocus.core.ui.R.color.sky_ink)
        listOf(pause, land, window, audio, findViewById<ImageView>(R.id.pf_flight_map_style)).forEach {
            it.imageTintList = ColorStateList.valueOf(tint)
        }
        renderCounters(state, night)
        findViewById<View>(R.id.pf_flight_stats).visibility = if (state.paused || selecting) GONE else VISIBLE
        if (selecting) volumePanel.visibility = GONE
        contentDescription = context.getString(R.string.pf_flight_summary, state.routeText,
            state.traveledKmText, state.remainingKmText, state.remainingTimeText)
    }

    private fun renderFocus(state: FlightHudUiState, visible: Boolean) {
        findViewById<View>(R.id.pf_flight_focus_backdrop).visibility = if (visible) VISIBLE else GONE
        val scroll = findViewById<View>(R.id.pf_flight_focus_scroll)
        scroll.visibility = if (visible) VISIBLE else GONE
        if (!visible) return
        val selecting = state.freeFlightLandControl == FreeFlightLandControl.RETURN_TO_CRUISE
        findViewById<View>(R.id.pf_flight_focus_status).visibility = if (focusCompact) GONE else VISIBLE
        findViewById<TextView>(R.id.pf_flight_focus_status).setText(
            when { selecting -> R.string.sky_focus_choose_airport
                state.paused -> R.string.pf_flight_paused
                else -> R.string.sky_focus_flying })
        findViewById<TextView>(R.id.pf_flight_focus_label).setText(
            when { focusCompact && selecting -> R.string.sky_focus_choose_airport
                focusCompact && state.paused -> R.string.pf_flight_paused
                state.elapsed -> R.string.sky_focus_elapsed
                focusCompact -> R.string.pf_hud_source_time_remaining
                else -> R.string.sky_focus_remaining })
        findViewById<TextView>(R.id.pf_flight_focus_clock).text = state.remainingTimeText
        findViewById<TextView>(R.id.pf_flight_focus_route).text = state.routeText
        findViewById<TextView>(R.id.pf_flight_focus_map_status).text = state.mapStatusText
        findViewById<View>(R.id.pf_flight_focus_retry).visibility = if (state.mapRetryEnabled) VISIBLE else GONE
        findViewById<SkyFocusDialView>(R.id.pf_flight_focus_dial).setProgress(if (state.elapsed) null else state.progress)
    }

    private fun updateFocusGeometry(compact: Boolean) {
        if (compact == focusCompact) return
        focusCompact = compact
        val size = com.projectfocus.core.ui.sky.SkySurfaces.dp(context, if (compact) 160f else 240f)
        findViewById<View>(R.id.pf_flight_focus_dial_frame).let {
            it.layoutParams = it.layoutParams.apply { width = size; height = size }
        }
        val horizontal = com.projectfocus.core.ui.sky.SkySurfaces.dp(context, if (compact) 20f else 28f)
        findViewById<View>(R.id.pf_flight_focus_clock_content).setPadding(horizontal, 0, horizontal, 0)
        findViewById<View>(R.id.pf_flight_focus_content).let {
            val vertical = com.projectfocus.core.ui.sky.SkySurfaces.dp(context, if (compact) 4f else 12f)
            it.setPadding(it.paddingLeft, vertical, it.paddingRight, vertical)
        }
        findViewById<View>(R.id.pf_flight_focus_route).let {
            it.layoutParams = (it.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                topMargin = com.projectfocus.core.ui.sky.SkySurfaces.dp(context, if (compact) 4f else 14f)
                bottomMargin = com.projectfocus.core.ui.sky.SkySurfaces.dp(context, if (compact) 4f else 16f)
            }
        }
        renderFocus(currentState, currentState.active && !currentState.mapAvailable && !currentState.windowVisible)
    }

    fun applySafeInsets(left: Int, top: Int, right: Int, bottom: Int) {
        require(left >= 0 && top >= 0 && right >= 0 && bottom >= 0)
        if (paddingLeft == left && paddingTop == top && paddingRight == right && paddingBottom == bottom) return
        clipToPadding = false
        setPadding(left, top, right, bottom)
        findViewById<View>(R.id.pf_flight_focus_backdrop).let {
            it.layoutParams = (it.layoutParams as android.view.ViewGroup.MarginLayoutParams).apply { setMargins(-left, -top, -right, -bottom) }
        }
    }

    private fun renderCounters(state: FlightHudUiState, night: Boolean) {
        val distance = findViewById<SourceNumberScrollView>(R.id.pf_flight_distance_value)
        val time = findViewById<SourceNumberScrollView>(R.id.pf_flight_time_value)
        val counters = state.counters
        distance.visibility = if (counters == null) INVISIBLE else VISIBLE
        time.visibility = if (counters == null) INVISIBLE else VISIBLE
        if (counters == null) return
        val textColor = context.getColor(com.projectfocus.core.ui.R.color.sky_ink)
        listOf(distance, time).forEach { it.setTextSize(24);it.setTextColor(textColor) }
        require(counters.metrics.distance.value in 0L..Int.MAX_VALUE.toLong())
        require(counters.metrics.time.value in 0L..Int.MAX_VALUE.toLong())
        distance.setValue(counters.metrics.distance.value.toInt())
        time.setValue(counters.metrics.time.value.toInt())
        time.contentDescription = state.remainingTimeText
        findViewById<TextView>(R.id.pf_flight_distance_unit).text = counters.metrics.distance.unit.label()
        findViewById<TextView>(R.id.pf_flight_time_unit).text = counters.metrics.time.unit.label()
        findViewById<TextView>(R.id.pf_flight_distance_label).setText(if (counters.elapsed)
            R.string.pf_hud_source_distance_traveled else R.string.pf_hud_source_distance_remaining)
        findViewById<TextView>(R.id.pf_flight_time_label).setText(if (counters.elapsed)
            R.string.pf_hud_source_time_elapsed else R.string.pf_hud_source_time_remaining)
        listOf(R.id.pf_flight_distance_unit, R.id.pf_flight_time_unit,
            R.id.pf_flight_distance_label, R.id.pf_flight_time_label).forEach {
            findViewById<TextView>(it).setTextColor(textColor)
        }
        fitCounterCards(width - paddingLeft - paddingRight)
        counterWidths.fit(findViewById(R.id.pf_flight_distance_content), findViewById(R.id.pf_flight_distance_container))
        counterWidths.fit(findViewById(R.id.pf_flight_time_content), findViewById(R.id.pf_flight_time_container))
    }

    private fun fitCounterCards(viewportWidth: Int) {
        if (viewportWidth <= 0) return
        val density = resources.displayMetrics.density
        val contentLimit = ((viewportWidth - 64f * density) / 2f - 24f * density).toInt().coerceAtLeast(1)
        listOf(R.id.pf_flight_distance_label, R.id.pf_flight_time_label).forEach { id ->
            findViewById<TextView>(id).apply {
                maxWidth = contentLimit
                maxLines = 1
                setHorizontallyScrolling(false)
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 8, 12, 1, TypedValue.COMPLEX_UNIT_SP)
            }
        }
        listOf(R.id.pf_flight_distance_value, R.id.pf_flight_time_value).forEach { id ->
            findViewById<SourceNumberScrollView>(id).apply {
                setTextSize(24)
                measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                if (measuredWidth > contentLimit)
                    setTextSize((24f * contentLimit / measuredWidth).toInt().coerceAtLeast(8))
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom
        updateFocusGeometry(MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED &&
            availableHeight > 0 && availableHeight / resources.displayMetrics.density < 640f)
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED)
            fitCounterCards(MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun com.projectfocus.core.model.flight.freeflight.FreeFlightMetricUnit.label(): String = when (this) {
        com.projectfocus.core.model.flight.freeflight.FreeFlightMetricUnit.METRES -> "m"
        com.projectfocus.core.model.flight.freeflight.FreeFlightMetricUnit.KILOMETRES -> "km"
        com.projectfocus.core.model.flight.freeflight.FreeFlightMetricUnit.SECONDS -> "s"
        com.projectfocus.core.model.flight.freeflight.FreeFlightMetricUnit.MINUTES -> "min"
    }

    override fun onDetachedFromWindow() {
        counterWidths.detach()
        super.onDetachedFromWindow()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val minimum = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48f, resources.displayMetrics).toInt()
        val delegates = listOf(pause, land, audio, window, findViewById<ImageView>(R.id.pf_flight_map_style), cancel)
            .filter { it.visibility == VISIBLE && it.parent.let { parent -> parent !is View || parent.visibility == VISIBLE } }
            .map { child ->
                val rect = Rect()
                child.getDrawingRect(rect)
                offsetDescendantRectToMyCoords(child, rect)
                rect.inset(-((minimum - rect.width()).coerceAtLeast(0) / 2), -((minimum - rect.height()).coerceAtLeast(0) / 2))
                TouchDelegate(rect, child)
            }
        touchDelegate = object : TouchDelegate(Rect(), this) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                for (delegate in delegates) {
                    val copy = MotionEvent.obtain(event)
                    val handled = try { delegate.onTouchEvent(copy) } finally { copy.recycle() }
                    if (handled) return true
                }
                return false
            }
        }
    }
}
