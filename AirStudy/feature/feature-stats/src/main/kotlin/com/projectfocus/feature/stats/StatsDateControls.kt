package com.projectfocus.feature.stats

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.projectfocus.core.ui.sky.SkyPressFeedback
import com.projectfocus.core.ui.sky.SkySurfaces
import java.time.ZoneId

class StatsDateControls @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private val visual = StatsVisual(context)
    private val track = FrameLayout(context)
    private val indicator = View(context)
    private val tabs = StatsPeriodTabsLayout(context)
    private var selected = StatsPeriod.WEEK.ordinal
    private var colorsBound = false
    private var colorAnimator: ValueAnimator? = null
    private var readyForVisibility = false
    val periodButtons: List<TextView>
    val previous: ImageButton
    val next: ImageButton
    val rangeLabel: TextView
    var onPeriodRequested: ((StatsPeriod) -> Unit)? = null
    var onMoveRequested: ((Int) -> Unit)? = null
    var onClearSceneRequested: ((com.projectfocus.service.focus.history.HistoryAccount, Long) -> Unit)? = null
    private val sceneLabels = StatsSceneLabels(context)
    val sceneFilter = LinearLayout(context)
    val sceneFilterLabel = visual.text(13f, visual.primaryText, true)
    private val sceneIcon = ImageView(context)
    private val clearIcon = ImageView(context)
    private var boundState: StatsUiState? = null
    private val motion = PathInterpolator(0.2f, 0f, 0f, 1f)

    init {
        orientation = VERTICAL
        setPadding(visual.dp(12), visual.dp(12), visual.dp(12), visual.dp(12))
        background = visual.card()
        elevation = visual.dp(2).toFloat()
        track.background = visual.secondary(20)
        track.clipChildren = false
        indicator.background = visual.selected(17)
        indicator.elevation = visual.dp(2).toFloat()
        indicator.visibility = INVISIBLE
        track.addView(indicator, FrameLayout.LayoutParams(0, visual.dp(34)))
        tabs.elevation = visual.dp(2).toFloat()
        val labels = listOf(R.string.stats_period_day, R.string.stats_period_week, R.string.stats_period_month,
            R.string.stats_period_year, R.string.stats_period_custom)
        periodButtons = labels.mapIndexed { index, label ->
            visual.text(12f, visual.secondaryText, true).apply {
                setText(label)
                gravity = Gravity.CENTER
                maxLines = 1
                setHorizontallyScrolling(false)
                isClickable = true
                isFocusable = true
                SkyPressFeedback.install(this)
                setOnClickListener { if (isEnabled) onPeriodRequested?.invoke(StatsPeriod.entries[index]) }
                tabs.addView(this, android.view.ViewGroup.LayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.MATCH_PARENT))
            }
        }
        track.addView(tabs, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            setMargins(visual.dp(3), visual.dp(3), visual.dp(3), visual.dp(3))
        })
        addView(track, LayoutParams(LayoutParams.MATCH_PARENT, visual.dp(58)))
        val navigation = StatsDateNavigationLayout(context)
        fun arrow(icon: Int, label: Int, direction: Int) = ImageButton(context).apply {
            setImageResource(icon)
            setColorFilter(visual.secondaryText)
            SkySurfaces.secondary(this,24f)
            SkyPressFeedback.install(this)
            setPadding(visual.dp(10), visual.dp(10), visual.dp(10), visual.dp(10))
            contentDescription = context.getString(label)
            setOnClickListener { if (isEnabled) onMoveRequested?.invoke(direction) }
        }
        previous = arrow(R.drawable.stats_ic_previous, R.string.stats_previous_period, -1)
        next = arrow(R.drawable.stats_ic_next, R.string.stats_next_period, 1)
        rangeLabel = visual.text(14f, visual.primaryText, true).apply {
            gravity = Gravity.CENTER
            setSingleLine(false)
            ellipsize = null
        }
        navigation.addView(previous, android.view.ViewGroup.LayoutParams(visual.dp(48),visual.dp(48)))
        navigation.addView(rangeLabel, android.view.ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT))
        navigation.addView(next, android.view.ViewGroup.LayoutParams(visual.dp(48),visual.dp(48)))
        addView(navigation, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = visual.dp(9) })
        sceneFilter.apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(visual.dp(12),visual.dp(6),visual.dp(8),visual.dp(6))
            minimumHeight=SkySurfaces.dp(context,48f)
            SkyPressFeedback.install(this)
            visibility = GONE
            isFocusable = true
            addView(sceneIcon, LayoutParams(visual.dp(16), visual.dp(16)).apply { rightMargin = visual.dp(8) })
            sceneFilterLabel.apply { gravity = Gravity.CENTER_VERTICAL; setSingleLine(false); ellipsize = null }
            addView(sceneFilterLabel, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            clearIcon.setImageResource(com.projectfocus.core.ui.R.drawable.pf_scene_ic_close)
            clearIcon.contentDescription = context.getString(R.string.stats_clear_scenario_filter)
            addView(clearIcon, LayoutParams(visual.dp(16), visual.dp(16)))
        }
        addView(sceneFilter, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = visual.dp(8) })
        readyForVisibility = true
    }

    fun bind(state: StatsUiState, zone: ZoneId) {
        boundState = state
        val dates = state.dates.takeIf { state.account?.isCurrent() == true }
        val ready = state.account?.isCurrent() == true && dates != null && state.dateRange != null
        val index = dates?.period?.ordinal ?: StatsPeriod.WEEK.ordinal
        val changed = selected != index
        selected = index
        periodButtons.forEachIndexed { i, tab ->
            tab.isEnabled = ready
            tab.isSelected = i == selected
        }
        if (changed || !colorsBound || !ready) {
            colorAnimator?.cancel()
            val from = periodButtons.map { it.currentTextColor }
            val to = periodButtons.indices.map { if (it == selected) visual.onAccent else visual.secondaryText }
            if (changed && ready && isLaidOut && ValueAnimator.areAnimatorsEnabled()) {
                colorAnimator = ValueAnimator.ofFloat(0f,1f).apply {
                    duration = 300
                    interpolator = motion
                    addUpdateListener { animator ->
                        val fraction = animator.animatedValue as Float
                        periodButtons.forEachIndexed { i, tab -> tab.setTextColor(visual.mix(from[i],to[i],fraction)) }
                    }
                    start()
                }
            } else periodButtons.forEachIndexed { i, tab -> tab.setTextColor(to[i]) }
            colorsBound = true
        }
        rangeLabel.text = if (dates == null) context.getString(R.string.stats_placeholder)
            else StatsDateLabels.label(context, dates, zone, state.nowWallMs, true)
        rangeLabel.contentDescription = dates?.let { StatsDateLabels.label(context, it, zone, state.nowWallMs, false) }
        val custom = dates?.period == StatsPeriod.CUSTOM
        previous.visibility = if (custom) INVISIBLE else VISIBLE
        next.visibility = if (custom) INVISIBLE else VISIBLE
        previous.isEnabled = ready && !custom
        next.isEnabled = ready && !custom && state.canNextPeriod
        next.alpha = if (next.isEnabled) 1f else 0.3f
        sceneFilter.visibility = if (ready && state.selectedSceneKey.isNotEmpty()) VISIBLE else GONE
        if (sceneFilter.visibility == VISIBLE) {
            val category = sceneLabels.category(state)
            val color = sceneLabels.color(category)
            sceneIcon.setImageResource(sceneLabels.icon(category))
            sceneIcon.setColorFilter(color)
            clearIcon.setColorFilter(color)
            sceneFilterLabel.text = sceneLabels.label(state.selectedSceneKey)
            sceneFilterLabel.setTextColor(visual.primaryText)
            sceneFilter.background = visual.rounded(16, intArrayOf(visual.alpha(color, 0.12f), visual.alpha(color, 0.12f)), visual.alpha(color, 0.28f))
            sceneFilter.contentDescription = "${sceneFilterLabel.text}，${context.getString(R.string.stats_clear_scenario_filter)}"
            sceneFilter.setOnClickListener {
                if (boundState === state && state.account?.isCurrent() == true) onClearSceneRequested?.invoke(state.account, state.queryRevision)
            }
        } else sceneFilter.setOnClickListener(null)
        if (changed || indicator.visibility != VISIBLE) moveIndicator(changed && ready && isLaidOut)
    }

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        if (boundState?.account?.isCurrent() == false) {
            sceneFilter.visibility = GONE
            sceneFilterLabel.text = ""
            sceneFilter.setOnClickListener(null)
        }
        super.dispatchDraw(canvas)
    }

    private fun moveIndicator(animate: Boolean) {
        val cell = periodButtons[selected]
        if (cell.width == 0 || track.height == 0) return
        indicator.layoutParams = (indicator.layoutParams as FrameLayout.LayoutParams).apply {
            width = cell.width
            height = cell.height
            leftMargin = visual.dp(3)
            topMargin = visual.dp(3)
        }
        indicator.layout(visual.dp(3),visual.dp(3),visual.dp(3)+cell.width,visual.dp(3)+cell.height)
        indicator.animate().cancel()
        val destination = cell.left.toFloat()
        if (animate && ValueAnimator.areAnimatorsEnabled()) indicator.animate().translationX(destination).translationY(cell.top.toFloat()).setDuration(300).setInterpolator(motion).start()
        else { indicator.translationX = destination;indicator.translationY=cell.top.toFloat() }
        indicator.visibility = VISIBLE
    }

    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        val available=(View.MeasureSpec.getSize(widthMeasureSpec)-paddingLeft-paddingRight-visual.dp(6)).coerceAtLeast(0)
        val height=tabs.requiredHeight(available)+visual.dp(6)
        if(track.layoutParams.height!=height)track.layoutParams=track.layoutParams.apply { this.height=height }
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed || indicator.visibility != VISIBLE) moveIndicator(false)
    }

    override fun onDetachedFromWindow() {
        indicator.animate().cancel()
        colorAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (readyForVisibility && visibility != VISIBLE) {
            indicator.animate().cancel()
            colorAnimator?.cancel()
            periodButtons.forEachIndexed { i, tab -> tab.setTextColor(if (i == selected) visual.onAccent else visual.secondaryText) }
            moveIndicator(false)
        }
    }
}
