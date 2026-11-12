package com.projectfocus.feature.stats

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.projectfocus.core.ui.sky.SkyPressFeedback

class StatsScenePanel @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private val visual = StatsVisual(context)
    private val labels = StatsSceneLabels(context)
    val title = visual.text(17f, visual.primaryText, true)
    val subtitle = visual.text(12f, visual.tertiaryText)
    val toggle = visual.text(11f, visual.accent, true)
    val chart = StatsDonutView(context)
    val legend = LinearLayout(context).apply { orientation = VERTICAL }
    var onSceneRequested: ((String, StatsUiState) -> Unit)? = null
    var interactionAllowed: (() -> Boolean)? = null
    private var bound: StatsUiState? = null
    var allTime = false; private set
    internal var displayedRows: List<StatsSceneEntry> = emptyList(); private set

    init {
        orientation = VERTICAL
        setPadding(visual.dp(16), visual.dp(16), visual.dp(16), visual.dp(16))
        background = visual.card(18)
        elevation = visual.dp(2).toFloat()
        val stackedHeading = resources.configuration.fontScale >= 1.3f
        val heading = LinearLayout(context).apply {
            orientation = if(stackedHeading) VERTICAL else HORIZONTAL
            gravity = if(stackedHeading) Gravity.START else Gravity.CENTER_VERTICAL
        }
        heading.addView(title, if(stackedHeading) LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT)
            else LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        toggle.apply {
            gravity = Gravity.CENTER
            minHeight = visual.dp(48)
            minWidth = visual.dp(48)
            setPadding(visual.dp(12), 0, visual.dp(12), 0)
            background = selectionBackground()
            SkyPressFeedback.install(this)
            isFocusable = true
            setOnClickListener {
                val state = bound ?: return@setOnClickListener
                if (!interactive(state)) return@setOnClickListener
                allTime = !allTime
                render(state)
                if (ValueAnimator.areAnimatorsEnabled()) {
                    chart.animate().cancel(); legend.animate().cancel()
                    chart.alpha = .58f; chart.scaleX = .985f; chart.scaleY = .985f; legend.alpha = .42f
                    chart.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
                    legend.animate().alpha(1f).setDuration(160).start()
                }
            }
        }
        heading.addView(toggle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            if(stackedHeading)topMargin=visual.dp(4)
        })
        addView(heading, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(subtitle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = visual.dp(4) })
        addView(chart, LayoutParams(LayoutParams.MATCH_PARENT, visual.dp(300)).apply { topMargin = visual.dp(4) })
        addView(legend, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = visual.dp(8) })
    }

    fun bind(state: StatsUiState) {
        if (bound?.account != state.account) allTime = false
        bound = state
        render(state)
    }
    private fun selectionBackground() = visual.rounded(10, intArrayOf(visual.alpha(visual.accent,.08f),visual.alpha(visual.accent,.08f)))
    private fun interactive(state: StatsUiState) = bound === state && state.account?.isCurrent() == true &&
        !state.isLoading && (interactionAllowed?.invoke() ?: true)
    private fun render(state: StatsUiState) {
        title.setText(if(allTime)R.string.stats_all_time_scenario_title else R.string.stats_scenario_period_title)
        subtitle.setText(if(allTime)R.string.stats_all_time_scenario_subtitle else R.string.stats_scenario_period_subtitle)
        toggle.setText(if(allTime)R.string.stats_show_current_period else R.string.stats_show_all_scenarios)
        val ready = !state.isLoading && state.account?.isCurrent() == true
        toggle.isEnabled = ready
        val complete = if (allTime) state.coverage.allScenes else state.coverage.periodScenes
        displayedRows = if (!ready || !complete) emptyList() else if (allTime) state.allTimeScenes else state.periodScenes
        chart.contentDescription = context.getString(if(allTime)R.string.stats_scenario_chart_accessibility_hint else R.string.stats_period_scenario_chart_accessibility_hint)
        chart.bind(displayedRows, context.getString(if(allTime)R.string.stats_all_time_label else R.string.stats_current_period_label),state.selectedSceneKey,ready)
        chart.visibility = if(ready && complete)VISIBLE else GONE
        cancelLegendAnimations()
        legend.removeAllViews()
        if (displayedRows.isEmpty()) {
            legend.addView(visual.text(13f,visual.tertiaryText).apply {
                gravity = Gravity.CENTER
                setPadding(0,visual.dp(14),0,visual.dp(14))
                setText(if(state.readFailed)R.string.stats_read_failed else if(ready && !complete)R.string.stats_history_incomplete else if(ready)R.string.stats_empty else R.string.stats_placeholder)
            },LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT))
            return
        }
        val rows = displayedRows.take(6)
        rows.forEachIndexed { index, entry ->
            val percentage = StatsScenes.percentage(entry,displayedRows)
            val color = labels.color(entry.iconKey)
            val name = labels.label(entry.key,entry.iconKey)
            val row = LinearLayout(context).apply {
                orientation = VERTICAL
                minimumHeight = visual.dp(48)
                setPadding(visual.dp(4),visual.dp(12),visual.dp(4),visual.dp(11))
                tag = entry.key
                isSelected = entry.key == state.selectedSceneKey
                background = if(isSelected)selectionBackground() else null
                isFocusable = true
                setOnClickListener { if(interactive(state))onSceneRequested?.invoke(entry.key,state) }
            }
            val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            header.addView(ImageView(context).apply { setImageResource(labels.icon(entry.iconKey));setColorFilter(color) },
                LayoutParams(visual.dp(22),visual.dp(22)))
            val nameLabel = visual.text(13f,visual.primaryText,true).apply {
                text = name;tag = "stats-scene-name"
            }
            val details = context.getString(R.string.stats_scene_time_count_pct_fmt,
                StatsFocusText.rich(context,entry.focusMinutes,visual.secondaryText).toString(),entry.completedFlights,
                context.getString(R.string.stats_percent_value_fmt,Math.round(percentage)))
            val detailsLabel = visual.text(11f,visual.secondaryText).apply {
                text = details;tag = "stats-scene-details"
            }
            if(resources.configuration.fontScale >= 1.3f || resources.configuration.screenWidthDp < 360) {
                val detailColumn = LinearLayout(context).apply {
                    orientation = VERTICAL
                    addView(nameLabel,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT))
                    addView(detailsLabel,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT).apply {
                        topMargin=visual.dp(4)
                    })
                }
                header.addView(detailColumn,LayoutParams(0,LayoutParams.WRAP_CONTENT,1f).apply { leftMargin=visual.dp(10) })
            } else {
                header.addView(nameLabel,LayoutParams(0,LayoutParams.WRAP_CONTENT,1f).apply { leftMargin=visual.dp(10) })
                header.addView(detailsLabel)
            }
            row.addView(header,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT))
            val rail = FrameLayout(context).apply { background = solid(color,.14f) }
            val fill = View(context).apply { background = solid(color,1f);pivotX = 0f;scaleX = 0f }
            rail.addView(fill,FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT))
            row.addView(rail,LayoutParams(LayoutParams.MATCH_PARENT,visual.dp(4)).apply { topMargin = visual.dp(8) })
            val expected = bound
            fill.post {
                if (bound !== expected || fill.parent == null || !state.account!!.isCurrent()) return@post
                val fraction = (percentage/100.0).coerceIn(0.0,1.0).toFloat()
                if(ValueAnimator.areAnimatorsEnabled())fill.animate().scaleX(fraction).setDuration(420).start() else fill.scaleX = fraction
            }
            row.contentDescription = context.getString(R.string.stats_scene_accessibility_fmt,name,details)
            legend.addView(row,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT))
            if(index < rows.lastIndex) legend.addView(View(context).apply { setBackgroundColor(visual.alpha(visual.border,.28f)) },
                LayoutParams(LayoutParams.MATCH_PARENT,visual.dp(1)).apply { leftMargin = visual.dp(34) })
        }
    }
    private fun solid(color: Int, opacity: Float) = visual.rounded(2,intArrayOf(visual.alpha(color,opacity),visual.alpha(color,opacity)))
    private fun cancelLegendAnimations() {
        for (i in 0 until legend.childCount) {
            val row=legend.getChildAt(i) as? LinearLayout ?: continue
            (row.getChildAt(1) as? FrameLayout)?.getChildAt(0)?.animate()?.cancel()
        }
    }
    override fun dispatchDraw(canvas: Canvas) {
        if(bound?.account?.isCurrent() == false) {
            allTime=false;bound=null;displayedRows=emptyList();chart.clear();cancelLegendAnimations();legend.removeAllViews()
            chart.visibility=GONE;toggle.isEnabled=false
        }
        super.dispatchDraw(canvas)
    }
    override fun onDetachedFromWindow() {
        chart.animate().cancel();legend.animate().cancel();cancelLegendAnimations()
        super.onDetachedFromWindow()
    }
}
