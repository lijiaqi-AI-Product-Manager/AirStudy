package com.projectfocus.feature.airport.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.projectfocus.core.ui.R as UiR
import kotlin.math.sin

/** A small decorative flight sketch. Motion is limited to an actual loading state. */
class AirportSkyStateView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = context.getColor(UiR.color.sky_primary)
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(120f, 82f, 78f,
            intArrayOf(context.getColor(UiR.color.sky_glow_blue), 0x00000000), null, Shader.TileMode.CLAMP)
    }
    private val trail = Path().apply {
        moveTo(30f, 100f); cubicTo(90f, 135f, 114f, 90f, 75f, 87f)
        cubicTo(35f, 82f, 75f, 55f, 123f, 60f)
    }
    private val plane = Path().apply {
        moveTo(89f, 67f); lineTo(165f, 42f); lineTo(140f, 111f)
        lineTo(123f, 83f); close()
        moveTo(123f, 83f); lineTo(165f, 42f)
    }
    private val cloud = Path().apply {
        moveTo(33f, 48f); cubicTo(18f, 49f, 18f, 32f, 30f, 31f)
        cubicTo(27f, 14f, 52f, 8f, 60f, 24f)
        cubicTo(76f, 17f, 88f, 38f, 73f, 45f)
        cubicTo(63f, 49f, 43f, 48f, 33f, 48f); close()
    }
    private val dash = DashPathEffect(floatArrayOf(3f, 6f), 0f)
    private var busy = false
    private var phase = 0f
    private var animator: ValueAnimator? = null
    internal val isAnimating: Boolean get() = animator != null

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun setLoading(loading: Boolean) {
        if (busy == loading) return
        busy = loading; reconcileMotion(); invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val save = canvas.save()
        val scale = minOf(width / 240f, height / 160f)
        canvas.translate((width - scale * 240f) / 2f, (height - scale * 160f) / 2f)
        canvas.scale(scale, scale)
        canvas.drawCircle(120f, 80f, 78f, glow)
        line.alpha = 100; line.pathEffect = dash
        canvas.drawPath(trail, line)
        line.pathEffect = null; line.alpha = 255
        fill.color = context.getColor(UiR.color.sky_card_solid)
        canvas.drawPath(cloud, fill)
        line.alpha = 65
        canvas.drawPath(cloud, line)
        line.alpha = 255
        val planeSave = canvas.save()
        if (busy) canvas.translate(0f, sin(phase * Math.PI * 2).toFloat() * 3f)
        fill.color = context.getColor(UiR.color.sky_selected)
        canvas.drawPath(plane, fill); canvas.drawPath(plane, line)
        canvas.restoreToCount(planeSave)
        fill.color = context.getColor(UiR.color.sky_spark)
        canvas.drawCircle(183f, 66f, 4f, fill)
        line.alpha = 120
        canvas.drawLine(185f, 92f, 185f, 104f, line)
        canvas.drawLine(179f, 98f, 191f, 98f, line)
        line.alpha = 255
        for (index in 0..2) {
            fill.color = context.getColor(UiR.color.sky_primary)
            fill.alpha = if (busy) (80 + 175 * (.5f + .5f * sin(phase * Math.PI * 2 - index * .9).toFloat())).toInt() else 90
            canvas.drawCircle(108f + index * 12f, 139f, 2.6f, fill)
        }
        fill.alpha = 255
        canvas.restoreToCount(save)
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); reconcileMotion() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); reconcileMotion() }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) { super.onWindowFocusChanged(hasWindowFocus); reconcileMotion() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); reconcileMotion() }
    override fun onDetachedFromWindow() { stopMotion(); super.onDetachedFromWindow() }
    private fun reconcileMotion() {
        if (!busy || !isAttachedToWindow || !isShown || !hasWindowFocus() || windowVisibility != VISIBLE || !ValueAnimator.areAnimatorsEnabled()) {
            stopMotion(); return
        }
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1_600L; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }
    private fun stopMotion() { animator?.cancel(); animator = null; phase = 0f; invalidate() }
}
