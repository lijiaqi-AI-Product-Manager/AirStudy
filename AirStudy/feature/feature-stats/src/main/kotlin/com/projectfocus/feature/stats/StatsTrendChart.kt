package com.projectfocus.feature.stats

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.HorizontalScrollView
import androidx.core.content.res.ResourcesCompat
import com.projectfocus.service.focus.history.HistoryAccount
import java.util.Locale
import java.util.TimeZone
import kotlin.math.*
import com.projectfocus.core.ui.R as UiR

class StatsTrendChart(context: Context, val metric: StatsTrendMetric) : View(context) {
    private val visual=StatsVisual(context)
    private val labels=StatsSceneLabels(context)
    private val shape=StatsTooltipShape(visual)
    private val light=ResourcesCompat.getFont(context,UiR.font.harmonyos_sans_sc_light)
    private val medium=ResourcesCompat.getFont(context,UiR.font.harmonyos_sans_sc_medium)
    private val grid=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE;strokeWidth=px(.5f) }
    private val line=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE;strokeWidth=px(2.5f);strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND }
    private val area=Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill=Paint(Paint.ANTI_ALIAS_FLAG)
    private val selection=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE;strokeWidth=px(1.2f);pathEffect=DashPathEffect(floatArrayOf(px(4f),px(4f)),0f) }
    private val point=Paint(Paint.ANTI_ALIAS_FLAG)
    private val zeroPoint=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE;strokeWidth=px(1.2f) }
    private val axisLabel=textPaint(10f,false).apply { textAlign=Paint.Align.CENTER }
    private val axisValue=textPaint(10f,false).apply { textAlign=Paint.Align.RIGHT }
    private val tooltipDate=textPaint(12f,true).apply { textAlign=Paint.Align.CENTER }
    private val tooltipValue=textPaint(13f,true).apply { textAlign=Paint.Align.CENTER }
    private val tooltipCaption=textPaint(11f,false)
    private val tooltipRow=TextPaint(textPaint(11f,false))
    private val tooltipDivider=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE;strokeWidth=px(.5f) }
    private var animator: ValueAnimator?=null
    private var revision=0L
    private var progress=1f
    private var owner: HistoryAccount?=null
    private var timeZone=TimeZone.getDefault()
    private var series: StatsTrendSeries?=null
    var mode=StatsTrendMode.LINE;private set
    var selectedIndex=-1;private set
    val isAnimating get()=animator?.isRunning==true
    val bucketCount get()=series?.buckets?.size ?: 0
    internal var tooltipBounds: RectF?=null;private set
    private val hide=Runnable { clearSelection() }

    init {
        isClickable=true;isFocusable=true
        contentDescription=context.getString(if(metric==StatsTrendMetric.COMPLETED)R.string.stats_completed_trend_accessibility_hint else R.string.stats_focus_trend_accessibility_hint)
    }
    private fun px(value: Float)=(value*resources.displayMetrics.density).toInt().toFloat()
    private fun space(value: Float)=value*resources.displayMetrics.density
    private fun sp(value: Float)=TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,value,resources.displayMetrics)
    private fun textPaint(size: Float,weight: Boolean)=Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize=sp(size);typeface=if(weight)medium else light }
    internal fun bind(value: StatsTrendSeries?,account: HistoryAccount?,zone: TimeZone) {
        val actual=value.takeIf { account?.isCurrent()==true }
        val changed=actual!=series || owner!=account || timeZone.id!=zone.id
        owner=account;timeZone=zone
        if(!changed)return
        series=actual
        clearSelection()
        minimumWidth=px(max(300f,(actual?.buckets?.size ?: 0)*48f)).toInt()
        requestLayout();animateData()
    }
    internal fun setMode(value: StatsTrendMode) {
        if(mode==value)return
        mode=value;clearSelection();animateData()
    }
    private fun animateData() {
        val expected=++revision
        stopAnimation()
        if(series?.buckets.isNullOrEmpty() || !ValueAnimator.areAnimatorsEnabled()) { progress=1f;invalidate();return }
        progress=0f
        animator=ValueAnimator.ofFloat(0f,1f).apply {
            duration=520;interpolator=PathInterpolator(.2f,0f,0f,1f)
            addUpdateListener { if(revision==expected){progress=it.animatedValue as Float;invalidate()} }
            addListener(object:AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { if(animator===animation){animator=null;progress=1f;invalidate()} }
            })
            start()
        }
    }
    private fun stopAnimation() { val previous=animator;animator=null;previous?.removeAllListeners();previous?.removeAllUpdateListeners();previous?.cancel() }
    internal fun clear() { revision++;stopAnimation();removeCallbacks(hide);selectedIndex=-1;series=null;owner=null;tooltipBounds=null;progress=1f;invalidate() }
    private fun clearSelection() { removeCallbacks(hide);selectedIndex=-1;tooltipBounds=null;invalidate() }
    override fun onDetachedFromWindow() { revision++;stopAnimation();progress=1f;clearSelection();super.onDetachedFromWindow() }
    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        setMeasuredDimension(max(suggestedMinimumWidth,MeasureSpec.getSize(widthMeasureSpec)),resolveSize(px(210f).toInt(),heightMeasureSpec))
    }
    private fun x(index: Int,left: Float,right: Float,bar: Float,count: Int): Float {
        if(count<=1)return (left+right)/2f
        val inset=if(bar>0)bar/2f+px(6f) else 0f
        return left+inset+(right-left-inset*2)*index/(count-1f)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if(owner?.isCurrent()!=true){if(series!=null)clear();return}
        val data=series ?: return
        val rows=data.buckets
        if(rows.isEmpty())return
        val accent=visual.accent
        val secondary=visual.secondaryText
        val tertiary=visual.tertiaryText
        grid.color=visual.alpha(tertiary,.16f);line.color=accent;fill.color=accent
        selection.color=visual.alpha(accent,.48f);axisLabel.color=tertiary;axisValue.color=secondary
        zeroPoint.color=visual.alpha(tertiary,.58f)
        val left=px(34f);val right=width-px(14f);val top=px(18f);val bottom=height-px(30f)
        val maximum=max(2.0,ceil(max(1.0,rows.maxOf { it.value(metric) })/2)*2)
        for(i in 0..2) {
            val y=top+(bottom-top)*i/2
            canvas.drawLine(left,y,right,y,grid)
            val value=maximum-i*maximum/2
            val label=if(abs(value-Math.rint(value))<.001)Math.round(value).toString() else String.format(Locale.getDefault(),"%.1f",value)
            canvas.drawText(label,left-px(7f),y+sp(3f),axisValue)
        }
        val spacing=if(rows.size>1)(right-left)/(rows.size-1) else right-left
        val bar=if(mode==StatsTrendMode.STACKED_BAR)min(px(30f),max(px(7f),spacing*.56f)) else 0f
        fun y(index: Int)=bottom-(bottom-top)*rows[index].value(metric).toFloat()*progress/maximum.toFloat()
        if(mode==StatsTrendMode.STACKED_BAR) {
            rows.forEachIndexed { index,bucket ->
                if(bucket.value(metric)<=0)return@forEachIndexed
                val center=x(index,left,right,bar,rows.size)
                val rect=RectF(center-bar/2,y(index),center+bar/2,bottom)
                if(selectedIndex==index) {
                    fill.color=visual.alpha(accent,.14f)
                    roundedTop(canvas,RectF(rect.left-px(3f),rect.top-px(3f),rect.right+px(3f),bottom),px(7f),fill)
                }
                fill.color=accent
                val scenes=bucket.scenes.filter { if(metric==StatsTrendMetric.COMPLETED)it.completedFlights>0 else it.focusMinutes>0 }
                val radius=min(px(6f),rect.height()/2)
                val sum=scenes.sumOf { sceneValue(it) }
                if(sum<=0.0)roundedTop(canvas,rect,radius,fill)
                else {
                    val clip=Path().apply { addRoundRect(rect,floatArrayOf(radius,radius,radius,radius,0f,0f,0f,0f),Path.Direction.CW) }
                    canvas.save();canvas.clipPath(clip)
                    var edge=bottom
                    scenes.forEachIndexed { i,scene ->
                        val next=if(i==scenes.lastIndex)rect.top else edge-rect.height()*(sceneValue(scene)/sum).toFloat()
                        fill.color=labels.color(scene.iconKey.ifEmpty { scene.key })
                        canvas.drawRect(rect.left,next,rect.right,edge,fill);edge=next
                    }
                    canvas.restore()
                }
            }
        } else {
            val stroke=Path();val shaded=Path()
            if(rows.size==1) {
                val center=x(0,left,right,0f,1)
                val spread=min(px(48f),(right-left)*.16f)
                stroke.moveTo(center-spread,bottom);stroke.lineTo(center,y(0));stroke.lineTo(center+spread,bottom)
                shaded.addPath(stroke);shaded.close()
            } else {
                stroke.moveTo(left,y(0));shaded.moveTo(left,bottom);shaded.lineTo(left,y(0))
                for(i in 1 until rows.size){val center=x(i,left,right,0f,rows.size);stroke.lineTo(center,y(i));shaded.lineTo(center,y(i))}
                shaded.lineTo(right,bottom);shaded.close()
            }
            area.shader=LinearGradient(0f,top,0f,bottom,visual.alpha(accent,.22f),visual.alpha(accent,.015f),Shader.TileMode.CLAMP)
            canvas.drawPath(shaded,area);canvas.drawPath(stroke,line)
        }
        if(selectedIndex in rows.indices) {
            val center=x(selectedIndex,left,right,bar,rows.size)
            canvas.drawLine(center,top,center,bottom,selection)
        }
        val labelStep=max(1,ceil(rows.size/7.0).toInt())
        rows.forEachIndexed { index,bucket ->
            val center=x(index,left,right,bar,rows.size)
            if(mode==StatsTrendMode.LINE) {
                val selected=index==selectedIndex
                if(bucket.value(metric)<=0)canvas.drawCircle(center,bottom,px(if(selected)4.5f else 2.6f),zeroPoint)
                else {
                    point.color=visual.alpha(accent,if(selected).22f else .14f)
                    canvas.drawCircle(center,y(index),px(if(selected)7.5f else 6f),point)
                    point.color=accent;canvas.drawCircle(center,y(index),px(if(selected)4.5f else 3.2f),point)
                }
            }
            if(index%labelStep==0 || index==rows.lastIndex)
                canvas.drawText(StatsTrendText.axis(context,data.granularity,bucket.fromWallMs,timeZone),center,height-px(8f),axisLabel)
        }
        rows.getOrNull(selectedIndex)?.let { bucket ->
            drawTooltip(canvas,bucket,StatsTrendText.axis(context,data.granularity,bucket.fromWallMs,timeZone),x(selectedIndex,left,right,bar,rows.size),top)
        }
    }
    private fun sceneValue(scene: StatsSceneEntry)=if(metric==StatsTrendMetric.COMPLETED)scene.completedFlights.toDouble() else scene.focusMinutes
    private fun roundedTop(canvas: Canvas,rect: RectF,radius: Float,paint: Paint) {
        val path=Path().apply { addRoundRect(rect,floatArrayOf(radius,radius,radius,radius,0f,0f,0f,0f),Path.Direction.CW) }
        canvas.drawPath(path,paint)
    }
    private fun drawTooltip(canvas: Canvas,bucket: StatsTrendBucket,date: String,anchor: Float,top: Float) {
        val stacked=mode==StatsTrendMode.STACKED_BAR && bucket.scenes.any { sceneValue(it)>0 }
        val scenes=if(stacked)bucket.scenes.filter { sceneValue(it)>0 }.sortedByDescending(::sceneValue) else emptyList()
        val shown=scenes.take(5)
        val additional=scenes.drop(5)
        val total=scenes.sumOf(::sceneValue)
        val value=StatsTrendText.value(context,metric,bucket.value(metric))
        val primary=visual.color(UiR.color.statistics_tooltip_text_primary)
        val secondary=visual.color(UiR.color.statistics_tooltip_text_secondary)
        tooltipDate.color=secondary;tooltipValue.color=primary;tooltipCaption.color=secondary;tooltipRow.color=primary
        val padding=space(12f);val vertical=space(8f);val gap=px(14f);val dot=px(12f)
        val caption=context.getString(R.string.stats_trend_total_label)
        fun detail(amount: Double)=context.getString(R.string.stats_scene_time_pct_fmt,
            StatsTrendText.value(context,metric,amount),context.getString(R.string.stats_percent_value_fmt,if(total>0)Math.round(amount/total*100) else 0L))
        var w=if(stacked)max(tooltipDate.measureText(date),tooltipCaption.measureText(caption)+gap+tooltipValue.measureText(value))
            else max(tooltipDate.measureText(date),tooltipValue.measureText(value))
        shown.forEach { w=max(w,dot+tooltipRow.measureText(labels.trendLabel(it.key,it.iconKey))+gap+tooltipRow.measureText(detail(sceneValue(it)))) }
        val more=if(additional.isEmpty())"" else context.getString(R.string.stats_trend_detail_more_scenes,additional.size)
        if(more.isNotEmpty())w=max(w,tooltipRow.measureText(more)+gap+tooltipRow.measureText(detail(additional.sumOf(::sceneValue))))
        val viewport=parent as? HorizontalScrollView
        val viewportLeft=(viewport?.scrollX ?: 0).toFloat()
        val viewportWidth=(viewport?.width ?: width).coerceAtLeast(1).toFloat()
        w=min(w+padding*2,viewportWidth-space(16f)).coerceAtLeast(space(40f))
        val rowCount=shown.size+if(additional.isEmpty())0 else 1
        val h=vertical*2+px(44f)+if(stacked)rowCount*px(18f)+space(6f) else space(6f)
        val minX=viewportLeft+space(8f)
        val maxX=max(minX,viewportLeft+viewportWidth-space(8f)-w)
        val left=(anchor-w/2).coerceIn(minX,maxX)
        val y=max(space(8f),top-px(10f))
        val rect=RectF(left,y,left+w,y+h)
        tooltipBounds=RectF(rect)
        shape.draw(canvas,rect,anchor)
        var baseline=y+vertical+px(18f)
        canvas.drawText(date,rect.centerX(),baseline,tooltipDate)
        baseline+=px(26f)
        if(!stacked){canvas.drawText(value,rect.centerX(),baseline,tooltipValue);return}
        val contentLeft=rect.left+padding;val contentRight=rect.right-padding
        canvas.drawText(caption,contentLeft,baseline,tooltipCaption)
        tooltipValue.textAlign=Paint.Align.RIGHT;canvas.drawText(value,contentRight,baseline,tooltipValue);tooltipValue.textAlign=Paint.Align.CENTER
        tooltipDivider.color=visual.alpha(secondary,.16f)
        canvas.drawLine(contentLeft,baseline+px(5f),contentRight,baseline+px(5f),tooltipDivider)
        shown.forEach { scene ->
            baseline+=px(18f)
            point.color=labels.color(scene.iconKey.ifEmpty { scene.key })
            canvas.drawCircle(contentLeft+px(3f),baseline-px(3.5f),px(3f),point)
            val details=detail(sceneValue(scene))
            val available=max(0f,contentRight-tooltipRow.measureText(details)-gap-(contentLeft+dot))
            val label=TextUtils.ellipsize(labels.trendLabel(scene.key,scene.iconKey),tooltipRow,available,TextUtils.TruncateAt.END).toString()
            canvas.drawText(label,contentLeft+dot,baseline,tooltipRow)
            tooltipRow.textAlign=Paint.Align.RIGHT;canvas.drawText(details,contentRight,baseline,tooltipRow);tooltipRow.textAlign=Paint.Align.LEFT
        }
        if(additional.isNotEmpty()) {
            baseline+=px(18f);tooltipRow.color=secondary
            canvas.drawText(more,contentLeft,baseline,tooltipRow)
            tooltipRow.textAlign=Paint.Align.RIGHT;canvas.drawText(detail(additional.sumOf(::sceneValue)),contentRight,baseline,tooltipRow);tooltipRow.textAlign=Paint.Align.LEFT
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val buckets=series?.buckets ?: return false
        if(buckets.isEmpty() || owner?.isCurrent()!=true)return false
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE -> {
                if(event.actionMasked==MotionEvent.ACTION_DOWN)removeCallbacks(hide)
                val fraction=((event.x-px(34f))/max(1f,width-px(14f)-px(34f))).coerceIn(0f,1f)
                val index=if(buckets.size==1)0 else (fraction*(buckets.size-1)).roundToInt()
                if(index!=selectedIndex || event.actionMasked==MotionEvent.ACTION_DOWN)performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                selectedIndex=index;invalidate();return true
            }
            MotionEvent.ACTION_UP -> { performClick();if(selectedIndex>=0){removeCallbacks(hide);postDelayed(hide,2200)};return true }
            MotionEvent.ACTION_CANCEL -> {clearSelection();return true}
        }
        return true
    }
    override fun performClick(): Boolean {
        super.performClick()
        val data=series ?: return true
        val selected=data.buckets.getOrNull(selectedIndex) ?: return true
        val date=StatsTrendText.axis(context,data.granularity,selected.fromWallMs,timeZone)
        announceForAccessibility(if(metric==StatsTrendMetric.COMPLETED)context.getString(R.string.stats_completed_trend_selected_fmt,date,selected.completedFlights.toLong())
            else context.getString(R.string.stats_trend_chart_selected_fmt,date,StatsTrendText.value(context,metric,selected.focusMinutes)))
        return true
    }
}
