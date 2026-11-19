package com.projectfocus.feature.flight.hud

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.View
import com.projectfocus.core.ui.R

/** Static focus dial. Only the actual countdown supplies an arc; elapsed time has no invented target. */
class SkyFocusDialView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private var progress: Float? = null
    private var gradient: SweepGradient? = null
    private val primary = context.getColor(R.color.sky_primary)
    private val muted = context.getColor(R.color.sky_selected)
    private val edge = context.getColor(R.color.sky_edge)

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun setProgress(value: Float?) {
        val next = value?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
        if (progress == next) return
        progress = next
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val inset = resources.displayMetrics.density * 14f
        oval.set(inset, inset, w - inset, h - inset)
        gradient = SweepGradient(w / 2f, h / 2f, intArrayOf(primary, 0xFF9D8DE8.toInt(), primary), null).apply {
            setLocalMatrix(Matrix().apply { setRotate(-90f, w / 2f, h / 2f) })
        }
    }

    override fun onDraw(canvas: Canvas) {
        val dp = resources.displayMetrics.density
        paint.style = Paint.Style.STROKE
        paint.shader = null
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 10f * dp
        paint.color = muted
        canvas.drawOval(oval, paint)
        progress?.let {
            paint.shader = gradient
            canvas.drawArc(oval, -90f, it * 360f, false, paint)
            paint.shader = null
        }
        paint.strokeWidth = dp
        paint.color = edge
        val inner = RectF(oval).apply { inset(12f * dp, 12f * dp) }
        canvas.drawOval(inner, paint)
        paint.color = primary
        paint.strokeWidth = 2f * dp
        canvas.drawLine(width / 2f - 5f * dp, oval.top - 1f * dp, width / 2f, oval.top - 5f * dp, paint)
        canvas.drawLine(width / 2f, oval.top - 5f * dp, width / 2f + 5f * dp, oval.top - 1f * dp, paint)
    }
}
