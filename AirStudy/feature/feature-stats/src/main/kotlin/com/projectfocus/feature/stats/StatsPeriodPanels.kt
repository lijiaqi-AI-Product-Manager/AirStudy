package com.projectfocus.feature.stats

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.time.Instant
import java.time.ZoneId
import java.util.Formatter
import java.util.Locale

private class StatsDetailViews(val context: Context) {
    val visual = StatsVisual(context)
    fun surface(color: Int, strong: Boolean = false) = visual.rounded(14,
        intArrayOf(visual.mix(visual.surface,color,if(strong).1f else .045f),
            visual.mix(visual.secondarySurface,color,if(strong).07f else .025f)),
        visual.alpha(color,if(strong).2f else .1f),GradientDrawable.Orientation.LEFT_RIGHT)
    fun icon(resource: Int, color: Int, side: Int, glyph: Int) = FrameLayout(context).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        background = GradientDrawable().apply {
            setColor(visual.alpha(color,.13f));cornerRadius = visual.dp(maxOf(10,side/3)).toFloat()
        }
        addView(ImageView(context).apply { setImageResource(resource);setColorFilter(color) },
            FrameLayout.LayoutParams(visual.dp(glyph),visual.dp(glyph),Gravity.CENTER))
    }
    fun append(parent: LinearLayout, row: View) {
        parent.addView(row,LinearLayout.LayoutParams(-1,-2).apply { if(parent.childCount>0)topMargin=visual.dp(8) })
    }
    fun prepare(panel: LinearLayout, title: Int, rows: LinearLayout, message: TextView) {
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(visual.dp(16),visual.dp(16),visual.dp(16),visual.dp(16))
        panel.background = visual.card(18);panel.elevation = visual.dp(2).toFloat()
        panel.addView(visual.text(17f,visual.primaryText,true).apply { setText(title) })
        panel.addView(rows,LinearLayout.LayoutParams(-1,-2).apply { topMargin=visual.dp(10) })
        panel.addView(message,LinearLayout.LayoutParams(-1,-2).apply { topMargin=visual.dp(10) })
        message.gravity = Gravity.CENTER;message.setPadding(0,visual.dp(20),0,visual.dp(20))
    }
    fun date(wall: Long, flags: Int, zone: ZoneId): String = DateUtils.formatDateRange(context,
        Formatter(StringBuilder(),Locale.getDefault()),wall,wall,flags,zone.id).toString()
    fun duration(minutes: Double) = StatsTrendText.value(context,StatsTrendMetric.FOCUS,minutes)
}

class StatsInsightsPanel @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context,attrs) {
    private val style = StatsDetailViews(context)
    private val visual = style.visual
    private val labels = StatsSceneLabels(context)
    val rows = LinearLayout(context).apply { orientation=VERTICAL }
    val message = visual.text(13f,visual.tertiaryText)
    private var bound: StatsUiState? = null
    var interactionAllowed: () -> Boolean = { true }
    var onRetryRequested: (() -> Unit)? = null
    init { style.prepare(this,R.string.stats_dashboard_insights_title,rows,message);bind(StatsUiState(),ZoneId.systemDefault()) }
    fun bind(state: StatsUiState, zone: ZoneId) {
        bound = state;rows.removeAllViews()
        val current = state.account?.isCurrent()==true
        val data = state.periodDetails.takeIf { current && !state.isLoading }
        rows.visibility = if(data==null)GONE else VISIBLE
        message.visibility = if(data==null)VISIBLE else GONE
        message.setText(if(state.readFailed && current)R.string.stats_read_failed else R.string.stats_placeholder)
        message.setOnClickListener {
            if(bound===state && state.readFailed && state.account?.isCurrent()==true && interactionAllowed())onRetryRequested?.invoke()
        }
        if(data==null)return
        val empty = context.getString(R.string.stats_insight_no_data_short)
        val best = data.bestDay?.let {
            val wall=it.date.atStartOfDay(zone).toInstant().toEpochMilli()
            context.getString(R.string.stats_insight_day_value_fmt,style.date(wall,20,zone),style.duration(it.focusMinutes))
        } ?: if (!state.coverage.periodFocus) context.getString(R.string.stats_placeholder) else empty
        append("stats-insight-best-day",R.drawable.stats_ic_best_day,visual.color(R.color.stats_insight_day_tint),R.string.stats_insight_best_day,best)
        val scene = data.topScene
        val sceneKey = scene?.iconKey ?: "study"
        val sceneValue = scene?.let { context.getString(R.string.stats_insight_scenario_value_fmt,
            labels.label(it.key,it.iconKey),style.duration(it.focusMinutes)) } ?: if (!state.coverage.periodScenes) context.getString(R.string.stats_placeholder) else empty
        append("stats-insight-top-scene",labels.icon(sceneKey),labels.color(sceneKey),R.string.stats_insight_top_scenario,sceneValue)
        val part = data.topPeriod?.let { context.getString(when(it) {
            StatsDayPart.DAWN -> R.string.stats_period_dawn
            StatsDayPart.MORNING -> R.string.stats_period_morning
            StatsDayPart.AFTERNOON -> R.string.stats_period_afternoon
            StatsDayPart.EVENING -> R.string.stats_period_evening
        }) } ?: if (!state.coverage.hours) context.getString(R.string.stats_placeholder) else empty
        append("stats-insight-top-period",R.drawable.stats_ic_period,visual.mix(visual.accent,visual.secondaryText,.14f),R.string.stats_insight_top_period,part)
    }
    private fun append(key: String, icon: Int, tint: Int, caption: Int, value: String) {
        val row=LinearLayout(context).apply {
            tag=key;gravity=Gravity.CENTER_VERTICAL
            setPadding(visual.dp(12),visual.dp(11),visual.dp(12),visual.dp(11));background=style.surface(tint)
        }
        row.addView(style.icon(icon,tint,40,22),LayoutParams(visual.dp(40),visual.dp(40)))
        row.addView(LinearLayout(context).apply {
            orientation=VERTICAL
            addView(visual.text(11f,visual.secondaryText).apply { setText(caption) })
            addView(visual.text(15f,visual.primaryText,true).apply {
                tag="$key-value";text=value;setSingleLine(false);ellipsize=null
            },LayoutParams(-1,-2).apply { topMargin=visual.dp(3) })
        },LayoutParams(0,-2,1f).apply { leftMargin=visual.dp(12) })
        style.append(rows,row)
    }
    override fun dispatchDraw(canvas: Canvas) {
        if(bound?.account?.isCurrent()==false)bind(StatsUiState(),ZoneId.systemDefault())
        super.dispatchDraw(canvas)
    }
    fun release() { bind(StatsUiState(),ZoneId.systemDefault());bound=null;onRetryRequested=null;interactionAllowed={false} }
}

class StatsHighlightsPanel @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context,attrs) {
    private val style = StatsDetailViews(context)
    private val visual = style.visual
    private val labels = StatsSceneLabels(context)
    val rows = LinearLayout(context).apply { orientation=VERTICAL }
    val message = visual.text(13f,visual.tertiaryText)
    private var bound: StatsUiState? = null
    var interactionAllowed: () -> Boolean = { true }
    var onRetryRequested: (() -> Unit)? = null
    init { style.prepare(this,R.string.stats_highlights_title,rows,message);bind(StatsUiState(),ZoneId.systemDefault()) }
    fun bind(state: StatsUiState, zone: ZoneId) {
        bound=state;rows.removeAllViews()
        val current=state.account?.isCurrent()==true
        val data=state.periodDetails.takeIf { current && !state.isLoading }
        val values=data?.highlights.orEmpty()
        rows.visibility=if(values.isEmpty())GONE else VISIBLE
        message.visibility=if(values.isEmpty())VISIBLE else GONE
        message.setText(when {
            state.readFailed && current -> R.string.stats_read_failed
            data==null -> R.string.stats_placeholder
            !state.coverage.periodFocus -> R.string.stats_history_incomplete
            else -> R.string.stats_insight_no_data_short
        })
        message.setOnClickListener {
            if(bound===state && state.readFailed && state.account?.isCurrent()==true && interactionAllowed())onRetryRequested?.invoke()
        }
        val today=Instant.ofEpochMilli(state.nowWallMs).atZone(zone).year
        values.forEachIndexed { index,item ->
            val row=LinearLayout(context).apply {
                tag=item.key;gravity=Gravity.CENTER_VERTICAL
                setPadding(visual.dp(10),visual.dp(10),visual.dp(12),visual.dp(10));background=style.surface(visual.accent,index==0)
            }
            val rank=StatsNumberView(context,if(index==0)15f else 14f).apply {
                showStatic(context.getString(R.string.stats_highlight_rank_value_fmt,index+1));gravity=Gravity.CENTER
                setTextColor(if(index==0)visual.onAccent else visual.secondaryText)
                background=GradientDrawable().apply { shape=GradientDrawable.OVAL;setColor(if(index==0)visual.accent else visual.mix(visual.secondarySurface,visual.border,.2f)) }
            }
            row.addView(rank,LayoutParams(visual.dp(38),visual.dp(38)))
            val scene=item.scene;val category=scene?.iconKey ?: "study"
            row.addView(style.icon(labels.icon(category),labels.color(category),34,18),LayoutParams(visual.dp(34),visual.dp(34)).apply { leftMargin=visual.dp(9) })
            val details=LinearLayout(context).apply {
                orientation=VERTICAL
                addView(visual.text(14f,visual.primaryText,true).apply {
                    tag="stats-highlight-scene";text=labels.label(scene?.key ?: "study",category);setSingleLine(false);ellipsize=null
                })
                addView(visual.text(11f,visual.tertiaryText).apply {
                    tag="stats-highlight-date"
                    text=if(item.startedAtWallMs<=0)context.getString(R.string.stats_placeholder) else {
                        val year=Instant.ofEpochMilli(item.startedAtWallMs).atZone(zone).year
                        style.date(item.startedAtWallMs,if(year==today)65552 else 65556,zone)
                    }
                })
            }
            row.addView(details,LayoutParams(0,-2,1f).apply { leftMargin=visual.dp(10) })
            val duration=visual.text(15f,visual.accent,true).apply { tag="stats-highlight-duration";text=style.duration(item.focusMinutes) }
            if(resources.configuration.fontScale>=1.3f || resources.configuration.screenWidthDp<360) {
                details.addView(duration,LayoutParams(-1,-2).apply { topMargin=visual.dp(4) })
            } else row.addView(duration)
            style.append(rows,row)
        }
    }
    override fun dispatchDraw(canvas: Canvas) {
        if(bound?.account?.isCurrent()==false)bind(StatsUiState(),ZoneId.systemDefault())
        super.dispatchDraw(canvas)
    }
    fun release() { bind(StatsUiState(),ZoneId.systemDefault());bound=null;onRetryRequested=null;interactionAllowed={false} }
}
