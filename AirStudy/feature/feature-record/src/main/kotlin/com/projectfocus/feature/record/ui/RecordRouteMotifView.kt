package com.projectfocus.feature.record.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.projectfocus.core.ui.R as UiR

/** A static route detail; it never competes with the recorded airports or actions. */
class RecordRouteMotifView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = context.getColor(UiR.color.sky_primary)
        alpha = 130
        pathEffect = DashPathEffect(floatArrayOf(3f * density, 4f * density), 0f)
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = context.getColor(UiR.color.sky_primary)
    }
    private val plane = Path()
    private var middle = 0f
    private var center = 0f
    private var half = 0f

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        val usable = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        center = paddingLeft + usable / 2f
        middle = height / 2f
        half = minOf(8f * density, usable / 4f)
        plane.rewind()
        plane.moveTo(center - half, middle - half * .65f)
        plane.lineTo(center + half, middle - half)
        plane.lineTo(center + half * .55f, middle + half)
        plane.lineTo(center - half * .05f, middle + half * .05f)
        plane.close()
        plane.moveTo(center - half * .05f, middle + half * .05f)
        plane.lineTo(center + half, middle - half)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width - paddingLeft - paddingRight > 64f * density) {
            canvas.drawLine(paddingLeft.toFloat(), middle, center - half - 8f * density, middle, line)
            canvas.drawLine(center + half + 8f * density, middle, (width - paddingRight).toFloat(), middle, line)
        }
        canvas.drawPath(plane, outline)
    }
}
