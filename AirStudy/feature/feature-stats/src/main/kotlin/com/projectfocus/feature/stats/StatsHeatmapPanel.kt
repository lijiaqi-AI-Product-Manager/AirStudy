package com.projectfocus.feature.stats

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

class StatsHeatmapPanel(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private val visual = StatsVisual(context)
    val previous: ImageButton
    val next: ImageButton
    val yearLabel: TextView = visual.text(13f, visual.secondaryText, true).apply { gravity = Gravity.CENTER }
    val emptyLabel: TextView = visual.text(13f, visual.tertiaryText).apply { gravity = Gravity.CENTER }
    val chart = StatsYearHeatmapView(context)
    val scroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
    private var currentState: StatsUiState? = null
    private var centerPending = false
    var interactionAllowed: () -> Boolean = { true }
    var currentWallMs: () -> Long = System::currentTimeMillis
    var onYearRequested: ((Int, StatsUiState) -> Unit)? = null

    init {
        orientation = VERTICAL
        setPadding(visual.dp(16), visual.dp(16), visual.dp(16), visual.dp(16))
        background = visual.card(18)
        elevation = visual.dp(2).toFloat()
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(visual.text(16f, visual.primaryText, true).apply { setText(R.string.stats_annual_heatmap_title) }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        fun arrow(icon: Int, label: Int) = ImageButton(context).apply {
            setImageResource(icon)
            setColorFilter(visual.secondaryText)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(visual.dp(9), visual.dp(9), visual.dp(9), visual.dp(9))
            contentDescription = context.getString(label)
        }
        previous = arrow(R.drawable.stats_ic_previous, R.string.stats_heatmap_previous_year)
        next = arrow(R.drawable.stats_ic_next, R.string.stats_heatmap_next_year)
        val navigation = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; background = visual.secondary(13) }
        navigation.addView(previous, LayoutParams(visual.dp(28), visual.dp(32)))
        navigation.addView(yearLabel, LayoutParams(visual.dp(56), visual.dp(32)))
        navigation.addView(next, LayoutParams(visual.dp(28), visual.dp(32)))
        header.addView(navigation)
        addView(header)
        scroll.addView(chart, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, visual.dp(136)))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, visual.dp(136)).apply { topMargin = visual.dp(10) })
        addView(emptyLabel, LayoutParams(LayoutParams.MATCH_PARENT, visual.dp(68)))
        chart.interactionAllowed = { validInteraction(currentState) }
        chart.currentWallMs = { currentWallMs() }
    }

    private fun validRender(state: StatsUiState?) = state != null && currentState === state && state.account?.isCurrent() == true
    private fun validInteraction(state: StatsUiState?) = validRender(state) && interactionAllowed()

    fun bind(state: StatsUiState) {
        val old = currentState
        currentState = state
        val current = state.account?.isCurrent() == true && state.dates != null
        yearLabel.text = if (current) context.getString(R.string.stats_heatmap_year_value_fmt, state.heatmapYear) else context.getString(R.string.stats_placeholder)
        previous.isEnabled = current
        next.isEnabled = current && state.canNextHeatmapYear
        previous.alpha = if (previous.isEnabled) 1f else .3f
        next.alpha = if (next.isEnabled) 1f else .3f
        fun request(direction: Int) { if (validInteraction(state)) onYearRequested?.invoke(direction, state) }
        previous.setOnClickListener { if (previous.isEnabled) request(-1) }
        next.setOnClickListener { if (next.isEnabled) request(1) }
        val model = state.annualHeatmap.takeIf { current && !state.isLoading }
        val reset = old?.account != state.account || old?.queryRevision != state.queryRevision || old?.heatmapRevision != state.heatmapRevision || old?.heatmapYear != state.heatmapYear
        if (model == null || model.days.isEmpty()) {
            chart.clear()
            scroll.visibility = GONE
            emptyLabel.visibility = VISIBLE
            emptyLabel.setText(when { state.readFailed && current -> R.string.stats_heatmap_unavailable
                state.isLoading || !current -> R.string.stats_placeholder
                !state.coverage.annual -> R.string.stats_history_incomplete
                else -> R.string.stats_heatmap_no_data_short })
        } else {
            emptyLabel.visibility = GONE
            scroll.visibility = VISIBLE
            chart.bind(model, state.account!!, reset)
            if (reset || old?.annualHeatmap?.days.isNullOrEmpty()) centerPending = true
        }
        if (centerPending) requestLayout()
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val state = currentState ?: return
        if (centerPending && validRender(state) && scroll.visibility == VISIBLE && scroll.width > 0) {
            state.dateRange?.firstDate?.let { scroll.scrollTo(chart.scrollFor(it, scroll.width), 0) }
            centerPending = false
        }
    }
    fun release() {
        currentState = null
        centerPending = false
        chart.clear()
        onYearRequested = null
        interactionAllowed = { false }
        currentWallMs = { 0 }
    }
}
