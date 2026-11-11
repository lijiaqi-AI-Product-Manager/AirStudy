package com.projectfocus.app.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatImageView
import com.projectfocus.app.R
import kotlin.math.sin

/** Decorative flight object. It never represents a live aircraft or route position. */
class FlightHeroView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : AppCompatImageView(context, attrs) {
    private var floatAnimator: ValueAnimator? = null
    private val path = Path()
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.3f
        color = context.getColor(com.projectfocus.core.ui.R.color.sky_primary)
        alpha = 85
        pathEffect = DashPathEffect(floatArrayOf(5f, 7f).map { it * resources.displayMetrics.density }.toFloatArray(), 0f)
    }
    init {
        setImageResource(R.drawable.sky_flight_hero)
        scaleType = ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        path.reset()
        path.moveTo(w * .03f, h * .75f)
        path.cubicTo(w * .2f, h * .97f, w * .25f, h * .72f, w * .16f, h * .7f)
        path.cubicTo(w * .01f, h * .66f, w * .2f, h * .52f, w * .46f, h * .54f)
    }
    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(path, ink)
        super.onDraw(canvas)
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); reconcileMotion() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); reconcileMotion() }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) { super.onWindowFocusChanged(hasWindowFocus); reconcileMotion() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); reconcileMotion() }
    override fun onDetachedFromWindow() { stopMotion(); super.onDetachedFromWindow() }
    private fun reconcileMotion() {
        if (!isAttachedToWindow || !isShown || !hasWindowFocus() || windowVisibility != VISIBLE || !ValueAnimator.areAnimatorsEnabled()) {
            stopMotion(); return
        }
        if (floatAnimator != null) return
        floatAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 6_000L; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener {
                val wave = sin((it.animatedValue as Float) * Math.PI * 2).toFloat()
                translationY = wave * resources.displayMetrics.density * 3f
            }
            start()
        }
    }
    private fun stopMotion() {
        floatAnimator?.cancel(); floatAnimator = null; translationY = 0f
    }
}
