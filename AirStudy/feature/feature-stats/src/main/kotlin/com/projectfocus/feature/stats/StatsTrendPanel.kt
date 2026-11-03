package com.projectfocus.feature.stats

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import java.time.ZoneId
import java.util.TimeZone

class StatsTrendPanel @JvmOverloads constructor(context: Context,attrs: AttributeSet?=null) : LinearLayout(context,attrs) {
    private val visual=StatsVisual(context)
    private val switch=StatsTrendSwitch(context)
    val metricButtons get()=switch.buttons
    val lineButton=button(R.drawable.stats_ic_trend_line,R.string.stats_trend_mode_line)
    val barButton=button(R.drawable.stats_ic_trend_bar,R.string.stats_trend_mode_stacked_bar)
    val rangeLabel=visual.text(11f,visual.tertiaryText)
    private val totalNumber=StatsNumberView(context,20f)
    private val peakNumber=StatsNumberView(context,20f)
    val totalText: TextView get()=totalNumber
    val peakText: TextView get()=peakNumber
    val completedChart=StatsTrendChart(context,StatsTrendMetric.COMPLETED)
    val focusChart=StatsTrendChart(context,StatsTrendMetric.FOCUS)
    val completedScroll=scroll(completedChart)
    val focusScroll=scroll(focusChart)
    val activeChart get()=if(metric==StatsTrendMetric.COMPLETED)completedChart else focusChart
    val activeScroll get()=if(metric==StatsTrendMetric.COMPLETED)completedScroll else focusScroll
    var metric=StatsTrendMetric.COMPLETED;private set
    var mode=StatsTrendMode.LINE;private set
    var interactionAllowed: (()->Boolean)?=null
    private var bound: StatsUiState?=null
    private var animationRevision=0L
    private var summaryKey: List<Any?>?=null
    val isAnimating get()=totalNumber.isAnimating || peakNumber.isAnimating || activeChart.isAnimating

    init {
        orientation=VERTICAL
        setPadding(visual.dp(16),visual.dp(16),visual.dp(16),visual.dp(16))
        background=visual.card(18);elevation=visual.dp(2).toFloat()
        val header=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        header.addView(visual.text(17f,visual.primaryText,true).apply { setText(R.string.stats_dashboard_trend_title) },LayoutParams(0,LayoutParams.WRAP_CONTENT,1f))
        val modes=LinearLayout(context).apply {
            gravity=Gravity.CENTER_VERTICAL
            setPadding(visual.dp(2),visual.dp(2),visual.dp(2),visual.dp(2))
            background=visual.secondary(12)
            addView(lineButton,LayoutParams(visual.dp(34),visual.dp(34)))
            addView(barButton,LayoutParams(visual.dp(34),visual.dp(34)))
        }
        header.addView(modes,LayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.WRAP_CONTENT))
        addView(header,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT))
        addView(switch,LayoutParams(LayoutParams.MATCH_PARENT,visual.dp(40)).apply { topMargin=visual.dp(11) })
        rangeLabel.gravity=Gravity.END
        addView(rangeLabel,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT).apply { topMargin=visual.dp(8) })
        val metrics=LinearLayout(context).apply {
            gravity=Gravity.CENTER
            setPadding(visual.dp(5),visual.dp(8),visual.dp(5),visual.dp(8))
        }
        fun number(value: StatsNumberView,caption: Int)=LinearLayout(context).apply {
            orientation=VERTICAL;gravity=Gravity.CENTER
            value.gravity=Gravity.CENTER;value.maxLines=1
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(value,12,20,1,TypedValue.COMPLEX_UNIT_SP)
            addView(value,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT))
            addView(visual.text(12f,visual.tertiaryText).apply { setText(caption);gravity=Gravity.CENTER },
                LayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.WRAP_CONTENT).apply { topMargin=visual.dp(4) })
        }
        metrics.addView(number(totalNumber,R.string.stats_trend_total_label),LayoutParams(0,LayoutParams.WRAP_CONTENT,1f))
        metrics.addView(View(context).apply { setBackgroundColor(visual.alpha(visual.border,.28f)) },LayoutParams(visual.dp(1),visual.dp(38)))
        metrics.addView(number(peakNumber,R.string.stats_trend_peak_label),LayoutParams(0,LayoutParams.WRAP_CONTENT,1f))
        addView(metrics,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT).apply { topMargin=visual.dp(10) })
        val charts=FrameLayout(context).apply {
            addView(completedScroll,FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT))
            addView(focusScroll,FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT))
        }
        addView(charts,LayoutParams(LayoutParams.MATCH_PARENT,visual.dp(224)).apply { topMargin=visual.dp(8) })
        switch.requested={ next ->
            val state=bound
            if(state!=null && interactive(state) && next!=metric) {
                metric=next;switch.select(metric,true,true);showMetric(true);renderSummary(state)
            }
        }
        fun change(next: StatsTrendMode) {
            val state=bound
            if(state!=null && interactive(state) && next!=mode) {
                mode=next;completedChart.setMode(next);focusChart.setMode(next);styleModes(true)
            }
        }
        lineButton.setOnClickListener { change(StatsTrendMode.LINE) }
        barButton.setOnClickListener { change(StatsTrendMode.STACKED_BAR) }
        showMetric(false);styleModes(false)
    }
    private fun button(icon: Int,label: Int)=ImageButton(context).apply {
        setImageResource(icon);setColorFilter(visual.secondaryText);setBackgroundColor(Color.TRANSPARENT)
        setPadding(visual.dp(7),visual.dp(7),visual.dp(7),visual.dp(7))
        contentDescription=context.getString(label);tooltipText=contentDescription
    }
    private fun scroll(chart: StatsTrendChart)=HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled=false;isFillViewport=true;overScrollMode=OVER_SCROLL_NEVER
        addView(chart,FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT,visual.dp(224)))
    }
    private fun interactive(state: StatsUiState)=bound===state && state.account?.isCurrent()==true &&
        !state.isLoading && state.trend!=null && (interactionAllowed?.invoke() ?: true)
    fun bind(state: StatsUiState,zone: ZoneId) {
        val changed=bound?.account!=state.account
        if(changed) {
            metric=StatsTrendMetric.COMPLETED;mode=StatsTrendMode.LINE;summaryKey=null
            completedScroll.scrollTo(0,0);focusScroll.scrollTo(0,0)
        }
        bound=state
        val ready=!state.isLoading && state.account?.isCurrent()==true && state.trend!=null
        val timeZone=TimeZone.getTimeZone(zone)
        completedChart.bind(if(ready)state.trend else null,state.account,timeZone)
        focusChart.bind(if(ready)state.trend else null,state.account,timeZone)
        completedChart.setMode(mode);focusChart.setMode(mode)
        switch.select(metric,ready,false);styleModes(ready)
        rangeLabel.text=if(state.account?.isCurrent()==true && state.dates!=null)
            StatsDateLabels.label(context,state.dates,zone,state.nowWallMs,false) else context.getString(R.string.stats_placeholder)
        showMetric(false);renderSummary(state)
    }
    private fun renderSummary(state: StatsUiState) {
        val ready=!state.isLoading && state.account?.isCurrent()==true && state.trend!=null && state.overview!=null
        if(!ready) {
            summaryKey=null
            totalNumber.showStatic(context.getString(R.string.stats_placeholder));peakNumber.showStatic(context.getString(R.string.stats_placeholder))
            return
        }
        val total=if(metric==StatsTrendMetric.COMPLETED)state.overview!!.completedFlights.toDouble() else state.overview!!.totalFocusMinutes
        val peak=state.trend!!.peak(metric)
        val key=listOf(state.account,state.queryRevision,metric,total,peak)
        if(key==summaryKey)return
        summaryKey=key
        fun format(value: Double)=if(metric==StatsTrendMetric.COMPLETED)context.getString(R.string.stats_times_fmt,Math.round(value))
            else StatsFocusText.rich(context,value,visual.secondaryText).toString()
        totalNumber.animateTo(total,::format);peakNumber.animateTo(peak,::format)
    }
    private fun styleModes(enabled: Boolean) {
        listOf(lineButton,barButton).forEachIndexed { index,button ->
            val selected=mode.ordinal==index
            button.isEnabled=enabled;button.isSelected=selected;button.isActivated=selected
            button.setColorFilter(if(selected)visual.accent else visual.secondaryText)
            button.background=if(selected)visual.rounded(10,intArrayOf(visual.alpha(visual.accent,.08f),visual.alpha(visual.accent,.08f))) else null
            button.alpha=if(selected)1f else .58f;button.scaleX=if(selected)1f else .92f;button.scaleY=button.scaleX
        }
    }
    private fun showMetric(animate: Boolean) {
        val expected=++animationRevision
        val incoming=activeScroll
        val outgoing=if(metric==StatsTrendMetric.COMPLETED)focusScroll else completedScroll
        incoming.animate().cancel();outgoing.animate().cancel()
        if(animate && isShown && isLaidOut && ValueAnimator.areAnimatorsEnabled()) {
            incoming.visibility=VISIBLE;incoming.alpha=0f;incoming.scaleX=.985f;incoming.scaleY=.985f
            incoming.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240).start()
            outgoing.animate().alpha(0f).scaleX(.99f).scaleY(.99f).setDuration(150).withEndAction {
                if(expected==animationRevision)outgoing.visibility=GONE
            }.start()
        } else {
            incoming.visibility=VISIBLE;incoming.alpha=1f;incoming.scaleX=1f;incoming.scaleY=1f
            outgoing.visibility=GONE;outgoing.alpha=1f;outgoing.scaleX=1f;outgoing.scaleY=1f
        }
    }
    override fun dispatchDraw(canvas: Canvas) {
        if(bound?.account?.isCurrent()==false) {
            bound=null;summaryKey=null;metric=StatsTrendMetric.COMPLETED;mode=StatsTrendMode.LINE
            completedChart.clear();focusChart.clear()
            totalNumber.showStatic(context.getString(R.string.stats_placeholder));peakNumber.showStatic(context.getString(R.string.stats_placeholder))
            rangeLabel.text=context.getString(R.string.stats_placeholder)
            switch.select(metric,false,false);styleModes(false);showMetric(false)
        }
        super.dispatchDraw(canvas)
    }
    override fun onDetachedFromWindow() {
        animationRevision++;completedScroll.animate().cancel();focusScroll.animate().cancel()
        totalNumber.finishAnimation();peakNumber.finishAnimation();super.onDetachedFromWindow()
    }
}
