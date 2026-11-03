package com.projectfocus.feature.stats

import android.animation.ValueAnimator
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat

internal class StatsTrendSwitch(context: Context) : FrameLayout(context) {
    private val visual=StatsVisual(context)
    private val indicator=View(context)
    private val track=LinearLayout(context)
    private var selected=0
    private var colors: ValueAnimator?=null
    val buttons: List<TextView>
    var requested: ((StatsTrendMetric)->Unit)?=null
    private val motion=PathInterpolator(.2f,0f,0f,1f)
    init {
        background=visual.secondary(20)
        indicator.background=visual.selected(17);indicator.elevation=visual.dp(2).toFloat()
        addView(indicator,LayoutParams(0,visual.dp(34)).apply { leftMargin=visual.dp(3);topMargin=visual.dp(3) })
        track.gravity=Gravity.CENTER_VERTICAL
        track.elevation=visual.dp(2).toFloat()
        buttons=listOf(R.string.stats_trend_completed,R.string.stats_trend_focus_time).mapIndexed { index,label ->
            visual.text(12f,if(index==0)visual.onAccent else visual.secondaryText,true).apply {
                setText(label);gravity=Gravity.CENTER;maxLines=1;isFocusable=true
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this,10,12,1,TypedValue.COMPLEX_UNIT_SP)
                setOnClickListener { if(isEnabled)requested?.invoke(StatsTrendMetric.entries[index]) }
                track.addView(this,LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.MATCH_PARENT,1f))
            }
        }
        addView(track,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT).apply { setMargins(visual.dp(3),visual.dp(3),visual.dp(3),visual.dp(3)) })
    }
    fun select(metric: StatsTrendMetric,enabled: Boolean,animate: Boolean) {
        val index=metric.ordinal
        val changed=index!=selected
        selected=index
        buttons.forEachIndexed { i,button -> button.isSelected=i==index;button.isEnabled=enabled }
        if(changed) {
            colors?.cancel()
            val from=buttons.map { it.currentTextColor }
            val to=buttons.indices.map { if(it==index)visual.onAccent else visual.secondaryText }
            if(animate && isLaidOut && ValueAnimator.areAnimatorsEnabled())colors=ValueAnimator.ofFloat(0f,1f).apply {
                duration=300;interpolator=motion
                addUpdateListener { val amount=it.animatedValue as Float;buttons.forEachIndexed { i,view ->view.setTextColor(visual.mix(from[i],to[i],amount)) } }
                start()
            } else buttons.forEachIndexed { i,view ->view.setTextColor(to[i]) }
        }
        position(changed && animate)
    }
    private fun position(animate: Boolean) {
        val cell=buttons[selected]
        if(cell.width==0)return
        val params=indicator.layoutParams as LayoutParams
        if(params.width!=cell.width || params.height!=visual.dp(34))
            indicator.layoutParams=params.apply { width=cell.width;height=visual.dp(34) }
        indicator.layout(visual.dp(3),visual.dp(3),visual.dp(3)+cell.width,height-visual.dp(3))
        indicator.animate().cancel()
        if(animate && ValueAnimator.areAnimatorsEnabled())indicator.animate().translationX(cell.left.toFloat()).setDuration(300).setInterpolator(motion).start()
        else indicator.translationX=cell.left.toFloat()
    }
    override fun onLayout(changed: Boolean,left: Int,top: Int,right: Int,bottom: Int) {
        super.onLayout(changed,left,top,right,bottom)
        position(false)
    }
    override fun onDetachedFromWindow() { indicator.animate().cancel();colors?.cancel();super.onDetachedFromWindow() }
}
