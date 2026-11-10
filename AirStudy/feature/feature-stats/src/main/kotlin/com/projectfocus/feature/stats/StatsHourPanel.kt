package com.projectfocus.feature.stats

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat

class StatsHourPanel @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private val visual = StatsVisual(context)
    private val peak = number()
    private val active = number()
    val peakText: TextView get() = peak
    val activeHoursText: TextView get() = active
    val chart = StatsHourChart(context)
    val message = visual.text(13f, visual.tertiaryText).apply { gravity = Gravity.CENTER }
    private var bound: StatsUiState? = null
    var interactionAllowed: () -> Boolean = { true }
    var onRetryRequested: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        setPadding(visual.dp(16), visual.dp(16), visual.dp(16), visual.dp(16))
        background = visual.card(18); elevation = visual.dp(2).toFloat()
        addView(visual.text(17f, visual.primaryText, true).apply { setText(R.string.stats_hour_of_day_title) })
        val summary = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(visual.dp(4), visual.dp(8), visual.dp(4), visual.dp(8)) }
        fun metric(value: StatsNumberView, label: Int) = LinearLayout(context).apply {
            orientation = VERTICAL; gravity = Gravity.CENTER
            addView(value, LayoutParams(-1, -2))
            addView(visual.text(12f, visual.tertiaryText).apply { setText(label); gravity = Gravity.CENTER }, LayoutParams(-2, -2).apply { topMargin = visual.dp(4) })
        }
        summary.addView(metric(peak, R.string.stats_peak_period_label), LayoutParams(0, -2, 1f))
        summary.addView(View(context).apply { setBackgroundColor(visual.alpha(visual.border, .35f)) }, LayoutParams(visual.dp(1), visual.dp(40)))
        summary.addView(metric(active, R.string.stats_active_hours_label), LayoutParams(0, -2, 1f))
        addView(summary, LayoutParams(-1, -2).apply { topMargin = visual.dp(10) })
        addView(chart, LayoutParams(-1, visual.dp(150)).apply { topMargin = visual.dp(10) })
        addView(message, LayoutParams(-1, visual.dp(68)).apply { topMargin = visual.dp(10) })
        chart.interactionAllowed = { bound?.let { it.account?.isCurrent() == true && !it.isLoading } == true && interactionAllowed() }
        bind(StatsUiState())
    }
    private fun number() = StatsNumberView(context, 20f).apply {
        gravity = Gravity.CENTER; setSingleLine(true); setHorizontallyScrolling(false)
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 12, 20, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
    }
    fun bind(state: StatsUiState) {
        val old = bound; bound = state
        val current = state.account?.isCurrent() == true
        val data = state.hourHabits.takeIf { current && !state.isLoading }
        val dash = context.getString(R.string.stats_placeholder)
        if (data == null) {
            peak.showStatic(dash); active.showStatic(dash); chart.clear(); chart.visibility = GONE; message.visibility = VISIBLE
            message.setText(if (state.readFailed && current) R.string.stats_read_failed else if (!state.coverage.hours) R.string.stats_history_incomplete else R.string.stats_placeholder)
        } else {
            message.visibility = GONE; chart.visibility = VISIBLE
            peak.showStatic(data.peakHour?.let { context.getString(R.string.stats_peak_hour_value_fmt, it, (it + 1) % 24) } ?: dash)
            val format: (Double) -> CharSequence = { context.getString(R.string.stats_hours_count_fmt, Math.round(it)) }
            if (old?.account != state.account || old?.hourHabits != data) active.animateTo(data.activeHours.toDouble(), format)
            else active.showStatic(format(data.activeHours.toDouble()))
            if (old?.account != state.account || old?.queryRevision != state.queryRevision) chart.clearSelection()
            chart.bind(data, state.account!!)
        }
        message.setOnClickListener {
            if (bound === state && state.readFailed && state.account?.isCurrent() == true && interactionAllowed()) onRetryRequested?.invoke()
        }
    }
    override fun dispatchDraw(canvas: Canvas) {
        if (bound?.account?.isCurrent() == false) bind(StatsUiState())
        super.dispatchDraw(canvas)
    }
    fun release() { bind(StatsUiState()); bound = null; onRetryRequested = null; interactionAllowed = { false }; chart.interactionAllowed = { false } }
}
