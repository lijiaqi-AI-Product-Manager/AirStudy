package com.projectfocus.feature.stats

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.projectfocus.core.ui.R as UiR

internal class StatsTooltipShape(private val visual: StatsVisual) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    fun draw(canvas: Canvas,rect: RectF,anchor: Float) {
        val density=visual.context.resources.displayMetrics.density
        val arrow=6*density
        val radius=10*density
        val tip=anchor.coerceIn(rect.left+radius+arrow,rect.right-radius-arrow)
        val path=Path().apply {
            addRoundRect(RectF(rect.left,rect.top,rect.right,rect.bottom-arrow),radius,radius,Path.Direction.CW)
            moveTo(tip-arrow,rect.bottom-arrow);lineTo(tip,rect.bottom);lineTo(tip+arrow,rect.bottom-arrow);close()
        }
        paint.style=Paint.Style.FILL;paint.color=visual.alpha(Color.BLACK,28f/255f)
        canvas.save();canvas.translate(0f,2*density);canvas.drawPath(path,paint);canvas.restore()
        paint.color=visual.color(UiR.color.statistics_tooltip_bg);canvas.drawPath(path,paint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=density;paint.color=visual.color(UiR.color.statistics_tooltip_stroke)
        canvas.drawPath(path,paint)
    }
}
