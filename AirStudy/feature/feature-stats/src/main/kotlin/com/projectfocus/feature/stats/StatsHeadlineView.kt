package com.projectfocus.feature.stats

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.projectfocus.service.focus.history.HistoryAccount
import java.time.ZoneId
import kotlin.math.abs
import com.projectfocus.core.ui.R as UiR

class StatsHeadlineView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private val visual = StatsVisual(context)
    val periodLabel: TextView
    val comparisonLabel: TextView
    internal val focus: StatsNumberView
    internal val metrics: List<StatsNumberView>
    private data class NumbersKey(val account: HistoryAccount, val query: Long, val metrics: StatsOverviewMetrics)
    private var bound: NumbersKey? = null
    val isAnimating get() = focus.isAnimating || metrics.any { it.isAnimating }

    init {
        orientation = VERTICAL
        setPadding(visual.dp(18),visual.dp(18),visual.dp(18),visual.dp(18))
        background = visual.card(22)
        elevation = visual.dp(2).toFloat()
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        periodLabel = visual.text(13f,visual.secondaryText).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        comparisonLabel = visual.text(11f,visual.secondaryText,true).apply {
            setPadding(visual.dp(9),visual.dp(4),visual.dp(9),visual.dp(4))
            visibility = GONE
        }
        top.addView(periodLabel,LayoutParams(0,LayoutParams.WRAP_CONTENT,1f))
        top.addView(comparisonLabel)
        addView(top)
        val total = LinearLayout(context).apply { gravity = Gravity.BOTTOM }
        focus = StatsNumberView(context,38f).apply {
            id = R.id.statsFocus
            maxLines = 1
            setHorizontallyScrolling(false)
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this,24,38,1,TypedValue.COMPLEX_UNIT_SP)
        }
        total.addView(focus,LayoutParams(0,LayoutParams.WRAP_CONTENT,1f))
        total.addView(visual.text(12f,visual.tertiaryText,true).apply {
            setText(R.string.stats_headline_focus_total)
            setPadding(visual.dp(8),0,0,visual.dp(4))
        })
        addView(total,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT).apply { topMargin = visual.dp(11) })
        val panel = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(visual.dp(4),visual.dp(4),visual.dp(4),visual.dp(4))
            background = visual.rounded(16,intArrayOf(visual.mix(visual.secondarySurface,visual.border,0.13f),
                visual.secondarySurface,visual.mix(visual.secondarySurface,visual.surface,0.24f)),visual.alpha(visual.border,0.28f))
        }
        val labels = listOf(R.string.stats_completed_label,R.string.stats_active_days_label,R.string.stats_longest_session_label,R.string.stats_best_streak_label)
        val ids = listOf(R.id.statsFlights,R.id.statsActiveDays,R.id.statsLongestSession,R.id.statsBestStreak)
        val values = mutableListOf<StatsNumberView>()
        fun divider(vertical: Boolean) = View(context).apply { setBackgroundColor(visual.alpha(visual.border,if(vertical)0.34f else 0.28f)) }
        repeat(2) { row ->
            val line = LinearLayout(context)
            repeat(2) { column ->
                val index = row*2+column
                val cell = LinearLayout(context).apply {
                    orientation = VERTICAL
                    setPadding(visual.dp(11),visual.dp(9),visual.dp(8),visual.dp(9))
                }
                val number = StatsNumberView(context,20f).apply { id = ids[index] }
                values.add(number)
                cell.addView(number)
                cell.addView(visual.text(11f,visual.tertiaryText).apply { setText(labels[index]) },
                    LayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.WRAP_CONTENT).apply { topMargin = visual.dp(4) })
                line.addView(cell,LayoutParams(0,LayoutParams.WRAP_CONTENT,1f))
                if(column==0)line.addView(divider(true),LayoutParams(visual.dp(1),LayoutParams.MATCH_PARENT))
            }
            panel.addView(line)
            if(row==0)panel.addView(divider(false),LayoutParams(LayoutParams.MATCH_PARENT,visual.dp(1)))
        }
        metrics = values
        addView(panel,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT).apply { topMargin = visual.dp(14) })
        unavailable()
    }

    fun bind(state: StatsUiState, zone: ZoneId) {
        val account = state.account
        val overview = state.overview
        if (account?.isCurrent()!=true || state.isLoading || overview==null) { unavailable(); return }
        periodLabel.text = state.dates?.let { StatsDateLabels.label(context,it,zone,state.nowWallMs,false) }
            ?: context.getString(R.string.stats_placeholder)
        val key = NumbersKey(account,state.queryRevision,overview)
        if (bound != key) {
            bound = key
            focus.animateTo(overview.totalFocusMinutes) { StatsFocusText.rich(context,it,visual.secondaryText) }
            metrics[0].animateTo(overview.completedFlights.toDouble()) { Math.round(it).toString() }
            metrics[1].animateTo(overview.activeDays.toDouble()) { Math.round(it).toString() }
            metrics[2].animateTo(overview.longestFocusMinutes) { StatsFocusText.rich(context,it,visual.secondaryText) }
            metrics[3].animateTo(overview.bestStreakDays.toDouble()) { context.getString(R.string.stats_streak_days,Math.round(it)) }
        }
        val comparison = state.focusComparison
        comparisonLabel.visibility = if(comparison==null)GONE else VISIBLE
        if(comparison!=null) {
            val same = abs(comparison.deltaMinutes)<0.0001
            val text = when {
                same -> context.getString(R.string.stats_compare_same)
                comparison.ratio==null -> context.getString(R.string.stats_compare_new)
                else -> context.getString(if(comparison.deltaMinutes>=0)R.string.stats_compare_up else R.string.stats_compare_down,
                    Math.round(abs(comparison.ratio)*100).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
            val color = if(same)visual.secondaryText else visual.color(if(comparison.deltaMinutes>0)UiR.color.statistics_compare_up else UiR.color.statistics_compare_down)
            comparisonLabel.text = context.getString(R.string.stats_compare_previous,text)
            comparisonLabel.setTextColor(color)
            comparisonLabel.background = GradientDrawable().apply {
                cornerRadius = visual.dp(16).toFloat()
                setColor(visual.alpha(color,0.12f))
                setStroke(visual.dp(1),visual.alpha(color,0.28f))
            }
        }
    }

    private fun unavailable() {
        bound = null
        periodLabel.setText(R.string.stats_placeholder)
        comparisonLabel.visibility = GONE
        focus.showStatic(context.getString(R.string.stats_placeholder))
        metrics.forEach { it.showStatic(context.getString(R.string.stats_placeholder)) }
    }

    override fun draw(canvas: Canvas) {
        if(bound?.account?.isCurrent()==false)unavailable()
        super.draw(canvas)
    }
}
