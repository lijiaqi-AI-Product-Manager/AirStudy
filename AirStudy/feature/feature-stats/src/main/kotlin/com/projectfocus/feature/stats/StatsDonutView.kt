package com.projectfocus.feature.stats

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.res.ResourcesCompat
import com.projectfocus.core.ui.R as UiR
import kotlin.math.*

class StatsDonutView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    internal data class Slice(val key: String, val label: String, val color: Int, val minutes: Double, val start: Float, val sweep: Float)
    private data class Anchor(val slice: Slice, val right: Boolean, val ringX: Float, val ringY: Float,
        val x: Float, val y: Float, var adjustedY: Float = y)
    private val visual = StatsVisual(context)
    private val labels = StatsSceneLabels(context)
    private val medium = ResourcesCompat.getFont(context, UiR.font.harmonyos_sans_sc_medium)
    private val heavy = ResourcesCompat.getFont(context, UiR.font.harmonyos_sans_sc_black) ?: medium
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val separator = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = visual.color(UiR.color.statistics_ring_separator); style = Paint.Style.STROKE; strokeWidth = dp(.35f); strokeCap = Paint.Cap.ROUND
    }
    private val number = textPaint(22f, UiR.color.statistics_text_primary).apply { typeface = heavy }
    private val unit = textPaint(11f, UiR.color.statistics_text_secondary)
    private val scope = textPaint(11f, UiR.color.statistics_text_secondary).apply { textAlign = Paint.Align.CENTER }
    private val labelPaint = textPaint(10f, UiR.color.statistics_text_primary)
    private val percentPaint = textPaint(9f, UiR.color.statistics_text_primary)
    private val connector = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = dp(.8f) }
    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tooltipTitle = textPaint(13f, UiR.color.statistics_tooltip_text_primary)
    private val tooltipDetail = textPaint(12f, UiR.color.statistics_tooltip_text_secondary)
    private val area = RectF()
    private var centerX = 0f
    private var centerY = 0f
    private var outer = 0f
    private var inner = 0f
    private var ringWidth = 0f
    internal var slices: List<Slice> = emptyList(); private set
    internal var totalMinutes = 0.0; private set
    private var scopeLabel = ""
    private var loaded = false
    private var progress = 1f
    private var labelProgress = 1f
    private var highlightProgress = 1f
    private var highlighted = emptySet<String>()
    private val previousAlpha = mutableMapOf<String, Float>()
    private var drawAnimator: ValueAnimator? = null
    private var labelAnimator: ValueAnimator? = null
    private var highlightAnimator: ValueAnimator? = null
    private var revision = 0L
    internal var touchedIndex = -1; private set
    private var tooltipX = 0f
    private var tooltipY = 0f
    private val hideTooltip = Runnable { clearTooltip() }
    internal val isAnimating get() = listOf(drawAnimator, labelAnimator, highlightAnimator).any { it?.isRunning == true }

    init { isClickable = true; isFocusable = true }
    private fun dp(value: Float) = value * resources.displayMetrics.density
    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,value,resources.displayMetrics)
    private fun textPaint(size: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(size); this.color = visual.color(color); typeface = medium
    }

    internal fun bind(rows: List<StatsSceneEntry>, label: String, selectedKey: String, ready: Boolean) {
        val total = rows.sumOf { it.focusMinutes }
        var angle = -90f
        val next = if (!ready || total <= 0.0) emptyList() else rows.mapNotNull { row ->
            val sweep = (row.focusMinutes / total * 360.0).toFloat()
            if (sweep <= 0f) null else Slice(row.key, shortLabel(labels.label(row.key, row.iconKey)), labels.color(row.iconKey),
                row.focusMinutes, angle, sweep).also { angle += sweep }
        }
        val changed = next != slices || total != totalMinutes || scopeLabel != label || loaded != ready
        totalMinutes = total; scopeLabel = label; loaded = ready
        if (changed) {
            cancelAnimations()
            slices = next
            clearTooltip()
            progress = 0f; labelProgress = 0f
            val expected = ++revision
            if (ready && ValueAnimator.areAnimatorsEnabled()) {
                drawAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 520; interpolator = DecelerateInterpolator(1.5f)
                    addUpdateListener { if (expected == revision) { progress = it.animatedValue as Float; invalidate() } }
                    addListener(object : AnimatorListenerAdapter() {
                        private var cancelled = false
                        override fun onAnimationCancel(animation: Animator) { cancelled = true }
                        override fun onAnimationEnd(animation: Animator) {
                            if (cancelled || expected != revision) return
                            labelAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                                duration = 850; interpolator = DecelerateInterpolator(1.2f)
                                addUpdateListener { if (expected == revision) { labelProgress = it.animatedValue as Float; invalidate() } }
                                start()
                            }
                        }
                    })
                    start()
                }
            } else { progress = 1f; labelProgress = 1f }
        }
        highlight(if (selectedKey.isEmpty()) emptySet() else setOf(selectedKey))
        invalidate()
    }

    private fun alpha(slice: Slice): Float {
        val target = if (highlighted.isNotEmpty() && slice.key !in highlighted) .28f else 1f
        val old = previousAlpha[slice.key]
        return if (old == null || highlightProgress >= 1f) target else old + (target - old) * highlightProgress
    }
    private fun highlight(keys: Set<String>) {
        if (highlighted == keys) return
        val old = slices.associate { it.key to alpha(it) }
        highlightAnimator?.cancel()
        previousAlpha.clear(); previousAlpha.putAll(old); highlighted = keys
        highlightProgress = 0f
        if (ValueAnimator.areAnimatorsEnabled()) highlightAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180; interpolator = DecelerateInterpolator()
            addUpdateListener { highlightProgress = it.animatedValue as Float; invalidate() }
            start()
        } else highlightProgress = 1f
    }
    private fun cancelAnimations() {
        revision++
        drawAnimator?.cancel(); labelAnimator?.cancel(); highlightAnimator?.cancel()
        drawAnimator = null; labelAnimator = null; highlightAnimator = null
        progress = 1f; labelProgress = 1f; highlightProgress = 1f; previousAlpha.clear()
    }
    private fun clearTooltip() { removeCallbacks(hideTooltip); touchedIndex = -1; invalidate() }
    internal fun clear() { cancelAnimations(); slices = emptyList(); totalMinutes = 0.0; loaded = false; highlighted = emptySet(); clearTooltip() }
    override fun onDetachedFromWindow() { cancelAnimations(); clearTooltip(); super.onDetachedFromWindow() }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(dp(260f).roundToInt(), widthMeasureSpec)
        setMeasuredDimension(width, resolveSize(width, heightMeasureSpec))
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f; centerY = h / 2f
        outer = max(dp(24f), min(w, h) / 2f - max(dp(8f), min(dp(42f), min(w, h) * .16f)))
        ringWidth = outer * .26f; inner = outer - ringWidth
        val radius = outer - ringWidth / 2f
        area.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!loaded) return
        arcPaint.strokeWidth = ringWidth
        if (slices.isEmpty()) {
            arcPaint.color = visual.color(UiR.color.statistics_ring_empty)
            canvas.drawArc(area, -90f, 360f, false, arcPaint)
        } else {
            slices.forEachIndexed { index, slice ->
                val one = slices.size == 1 && slice.sweep >= 359.5f
                val gap = if (slices.size > 1) .35f else 0f
                val sweep = (if (one) 360f else slice.sweep) * progress - gap
                if (sweep > 0f) {
                    arcPaint.color = visual.alpha(slice.color, alpha(slice))
                    arcPaint.strokeWidth = ringWidth + if (touchedIndex == index) dp(1.5f) else 0f
                    canvas.drawArc(area, if (one) -90f else slice.start + gap / 2f, sweep, false, arcPaint)
                }
            }
            if (slices.size > 1) slices.forEach { slice ->
                val radians = Math.toRadians(slice.start.toDouble())
                val x = cos(radians).toFloat(); val y = sin(radians).toFloat()
                canvas.drawLine(centerX + inner*x, centerY + inner*y, centerX + outer*x, centerY + outer*y, separator)
            }
            if (labelProgress > 0f) drawLabels(canvas)
        }
        drawCenter(canvas)
        slices.getOrNull(touchedIndex)?.let { drawTooltip(canvas, it) }
    }
    private fun drawCenter(canvas: Canvas) {
        val rounded = Math.round(totalMinutes).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        val hours = rounded / 60; val minutes = rounded % 60
        val first = if (hours > 0) hours.toString() else ""
        val second = if (minutes > 0 || hours == 0) minutes.toString() else ""
        val hour = context.getString(R.string.stats_compact_hour); val minute = context.getString(R.string.stats_compact_minute)
        number.textSize = sp(22f); unit.textSize = sp(11f)
        val gap = if (first.isNotEmpty() && second.isNotEmpty()) dp(2f) else 0f
        fun measure() = (if (first.isNotEmpty()) number.measureText(first)+unit.measureText(hour) else 0f) +
            (if (second.isNotEmpty()) number.measureText(second)+unit.measureText(minute) else 0f)
        val max = max(dp(24f), inner*1.72f)
        val scale = min(1f, max/(measure()+gap).coerceAtLeast(1f))
        number.textSize *= scale; unit.textSize *= scale
        var x = centerX - (measure()+gap*scale)/2f
        val baseline = if (scopeLabel.isEmpty()) centerY - (number.fontMetrics.ascent+number.fontMetrics.descent)/2f else centerY-dp(4f)
        if (first.isNotEmpty()) {
            canvas.drawText(first,x,baseline,number); x += number.measureText(first)
            canvas.drawText(hour,x,baseline,unit); x += unit.measureText(hour)+gap*scale
        }
        if (second.isNotEmpty()) {
            canvas.drawText(second,x,baseline,number); x += number.measureText(second)
            canvas.drawText(minute,x,baseline,unit)
        }
        if (scopeLabel.isNotEmpty()) canvas.drawText(scopeLabel,centerX,centerY+dp(16f)-(scope.fontMetrics.ascent+scope.fontMetrics.descent)/2f,scope)
    }
    private fun clampAngle(preferred: Float, slice: Slice): Float {
        val margin = min(6f, slice.sweep/4f)
        val lower = slice.start+margin; val upper = slice.start+slice.sweep-margin
        var best = lower; var distance = Float.MAX_VALUE
        for (turn in -1..1) {
            val angle = turn*360f+preferred
            val candidate = angle.coerceIn(lower,upper)
            if (abs(angle-candidate)<distance) { distance=abs(angle-candidate);best=candidate }
        }
        return best
    }
    private fun drawLabels(canvas: Canvas) {
        val eligible = slices.filter { it.sweep/360f*100f>=3f && it.label.isNotEmpty() }.take(8)
        val anchors = eligible.map { slice ->
            val angle = if (eligible.size != 1) slice.start+slice.sweep/2f else {
                val right=clampAngle(-55f,slice); val left=clampAngle(-125f,slice)
                fun distance(a: Float,b: Float): Float { val d=abs(a-b)%360f;return min(d,360f-d) }
                if(distance(right,-55f)>distance(left,-125f))left else right
            }
            val radians=Math.toRadians(angle.toDouble());val x=cos(radians).toFloat();val y=sin(radians).toFloat()
            Anchor(slice,x>=0f,centerX+(outer+dp(1f))*x,centerY+(outer+dp(1f))*y,
                centerX+(outer+dp(10f))*x,centerY+(outer+dp(10f))*y)
        }
        listOf(false,true).forEach { right ->
            val side=anchors.filter { it.right==right }.sortedBy { it.y }
            var next=dp(18f)
            side.forEach { it.adjustedY=max(it.y,next);next=it.adjustedY+dp(25f) }
            val overflow=(side.lastOrNull()?.adjustedY ?: 0f)-(height-dp(22f))
            if(overflow>0)side.forEach { it.adjustedY-=overflow }
            for(i in side.size-2 downTo 0)side[i].adjustedY=min(side[i].adjustedY,side[i+1].adjustedY-dp(25f))
            side.forEach { drawLabel(canvas,it) }
        }
    }
    private fun drawLabel(canvas: Canvas, anchor: Anchor) {
        val opacity=labelProgress*alpha(anchor.slice)
        labelPaint.color=visual.alpha(anchor.slice.color,opacity)
        percentPaint.color=visual.alpha(anchor.slice.color,opacity*.78f)
        connector.color=visual.alpha(anchor.slice.color,opacity*.72f)
        val align=if(anchor.right)Paint.Align.LEFT else Paint.Align.RIGHT
        labelPaint.textAlign=align;percentPaint.textAlign=align
        val percent=context.getString(R.string.stats_percent_value_fmt,Math.round(anchor.slice.sweep/360f*100f))
        val maxWidth=max(labelPaint.measureText(anchor.slice.label),percentPaint.measureText(percent))
        val reach=dp(12f+(1f-abs((anchor.x-centerX)/(dp(10f)+outer)))*8f)
        val x=if(anchor.right)min(anchor.x+reach+dp(5f),width-dp(2f)-maxWidth) else max(anchor.x-reach-dp(5f),dp(2f)+maxWidth)
        val path=Path().apply {
            moveTo(anchor.ringX,anchor.ringY);quadTo(anchor.x,anchor.y,anchor.x,anchor.adjustedY)
            lineTo(x+if(anchor.right)-dp(5f) else dp(5f),anchor.adjustedY)
        }
        canvas.drawPath(path,connector)
        canvas.drawText(anchor.slice.label,x,anchor.adjustedY-dp(2f),labelPaint)
        canvas.drawText(percent,x,anchor.adjustedY+dp(9f),percentPaint)
    }
    internal fun hit(x: Float,y: Float): Int {
        val dx=x-centerX;val dy=y-centerY;val radius=sqrt(dx*dx+dy*dy)
        if(radius<inner || radius>outer)return -1
        val degrees=(Math.toDegrees(atan2(dy,dx).toDouble()).toFloat()+360f)%360f
        return slices.indexOfFirst { val start=(it.start+360f)%360f;val end=start+it.sweep
            if(end>360f)degrees>=start || degrees<end-360f else degrees>=start && degrees<end }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(!loaded)return false
        if(slices.isEmpty())return true
        fun show(index: Int) { touchedIndex=index;tooltipX=event.x;tooltipY=event.y;invalidate() }
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val index=hit(event.x,event.y)
                if(index<0){clearTooltip();return false}
                removeCallbacks(hideTooltip);parent?.requestDisallowInterceptTouchEvent(true);show(index);return true
            }
            MotionEvent.ACTION_MOVE -> {
                if(touchedIndex<0)return false
                parent?.requestDisallowInterceptTouchEvent(true)
                val index=hit(event.x,event.y);if(index>=0)show(index)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val active=touchedIndex>=0
                if(active){val index=hit(event.x,event.y);if(index>=0)show(index);removeCallbacks(hideTooltip);postDelayed(hideTooltip,2200);performClick()}
                parent?.requestDisallowInterceptTouchEvent(false);return active
            }
            MotionEvent.ACTION_CANCEL -> {clearTooltip();parent?.requestDisallowInterceptTouchEvent(false);return true}
        }
        return super.onTouchEvent(event)
    }
    override fun performClick(): Boolean { super.performClick();return true }
    private fun drawTooltip(canvas: Canvas,slice: Slice) {
        if(slice.label.isEmpty())return
        val detail=StatsFocusText.rich(context,slice.minutes,unit.color).toString()
        val padding=dp(10f);val gap=dp(3f);val arrow=dp(6f);val margin=dp(8f)
        val titleHeight=tooltipTitle.fontMetrics.descent-tooltipTitle.fontMetrics.ascent
        val detailHeight=tooltipDetail.fontMetrics.descent-tooltipDetail.fontMetrics.ascent
        val w=max(tooltipTitle.measureText(slice.label),tooltipDetail.measureText(detail))+padding*2
        val h=titleHeight+gap+detailHeight+padding*2+arrow
        val x=if(w>=width-2*margin)margin else (tooltipX-w/2).coerceIn(margin,width-margin-w)
        val y=max(margin,min(height-margin-h,max(margin,tooltipY-h-dp(8f))))
        val rect=RectF(x,y,x+w,y+h)
        val tip=tooltipX.coerceIn(rect.left+dp(16f),rect.right-dp(16f))
        val path=Path().apply {
            addRoundRect(RectF(rect.left,rect.top,rect.right,rect.bottom-arrow),dp(10f),dp(10f),Path.Direction.CW)
            moveTo(tip-dp(6f),rect.bottom-arrow);lineTo(tip,rect.bottom);lineTo(tip+dp(6f),rect.bottom-arrow);close()
        }
        bubble.style=Paint.Style.FILL;bubble.color=visual.alpha(Color.BLACK,28f/255f)
        canvas.save();canvas.translate(0f,dp(2f));canvas.drawPath(path,bubble);canvas.restore()
        bubble.color=visual.color(UiR.color.statistics_tooltip_bg);canvas.drawPath(path,bubble)
        bubble.style=Paint.Style.STROKE;bubble.strokeWidth=dp(1f);bubble.color=visual.color(UiR.color.statistics_tooltip_stroke);canvas.drawPath(path,bubble)
        val baseline=y+padding-tooltipTitle.fontMetrics.ascent
        canvas.drawText(slice.label,x+padding,baseline,tooltipTitle)
        canvas.drawText(detail,x+padding,baseline+titleHeight+gap,tooltipDetail)
    }
    internal companion object {
        fun shortLabel(value: String): String {
            val text=value.trim { it<=' ' }
            val points=text.codePoints().toArray()
            val limit=if(points.any { it in 13312..19903 || it in 19968..40959 || it in 63744..64255 || it in 12352..12543 || it in 44032..55215 })8 else 16
            return if(points.size<=limit)text else text.substring(0,text.offsetByCodePoints(0,limit-1))+"…"
        }
    }
}
