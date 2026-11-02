package com.projectfocus.app.freeflight

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.core.content.res.ResourcesCompat
import com.projectfocus.app.R
import com.projectfocus.core.ui.R as CoreUiR

/** Actual source custom drawing/touch/150ms scale; no Material button substituted. */
class FreeFlightDepartureButton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE;style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK;textSize = 18f * resources.displayMetrics.density;textAlign = Paint.Align.CENTER
        try { typeface = ResourcesCompat.getFont(context, CoreUiR.font.harmonyos_sans_sc_bold) }
        catch (_: Exception) { isFakeBoldText = true }
    }
    private val bounds = RectF()
    private val label = context.getString(R.string.pf_ff_departure_button_text)
    private var scaleFactor = 1f
    private var animator: ValueAnimator? = null
    private var pressedInside = false
    init { isClickable = true;isFocusable = true;contentDescription = label }

    fun applyNight(night: Boolean) {
        body.color = if (night) Color.WHITE else Color.BLACK;text.color = if (night) Color.BLACK else Color.WHITE;invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { bounds.set(0f, 0f, w.toFloat(), h.toFloat()) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas);val save = canvas.save();canvas.scale(scaleFactor, scaleFactor, width / 2f, height / 2f)
        canvas.drawRoundRect(bounds, height / 2f, height / 2f, body)
        val metrics = text.fontMetrics;canvas.drawText(label, width / 2f, (height - metrics.ascent - metrics.descent) / 2f, text)
        canvas.restoreToCount(save)
    }

    private fun animateScale(target: Float) {
        animator?.takeIf { it.isRunning }?.cancel()
        animator = ValueAnimator.ofFloat(scaleFactor, target).apply {
            duration = 150L;interpolator = OvershootInterpolator()
            addUpdateListener { scaleFactor = it.animatedValue as Float;invalidate() };start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = when (event.action) {
        MotionEvent.ACTION_DOWN -> { pressedInside = true;animateScale(.9f);true }
        MotionEvent.ACTION_UP -> { if (pressedInside) { pressedInside = false;animateScale(1f);super.performClick() };true }
        MotionEvent.ACTION_CANCEL -> { pressedInside = false;animateScale(1f);true }
        else -> super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() { animator?.cancel();super.onDetachedFromWindow() }
}
