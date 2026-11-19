package com.projectfocus.feature.flight.hud

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

internal class AmbientTrackIcon(track: Int, color: Int) : Drawable() {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val drawing = Path().apply {
        when (track) {
            0 -> {
                moveTo(5f, 12f)
                cubicTo(1f, 12f, 1f, 6f, 5.5f, 6f)
                cubicTo(7f, 2.5f, 13f, 3f, 14.5f, 6.4f)
                cubicTo(20.5f, 5.7f, 22.5f, 12f, 17f, 12f)
                lineTo(5f, 12f)
                moveTo(8f, 14.5f); lineTo(6f, 18.5f)
                moveTo(13f, 14.5f); lineTo(11f, 18.5f)
                moveTo(18f, 14.5f); lineTo(16f, 18.5f)
            }
            1 -> {
                moveTo(7f, 20f)
                cubicTo(1f, 15f, 7f, 11f, 10f, 8f)
                cubicTo(12f, 6f, 13f, 4f, 13f, 2f)
                cubicTo(20f, 8f, 23f, 15f, 17f, 20f)
                cubicTo(13f, 23f, 10f, 22f, 7f, 20f)
                moveTo(11f, 20f)
                cubicTo(8f, 18f, 11f, 14f, 13f, 12f)
                cubicTo(16f, 15f, 18f, 18f, 14f, 21f)
            }
            2 -> {
                moveTo(12f, 2f); lineTo(5f, 10f); lineTo(8f, 10f)
                lineTo(3f, 17f); lineTo(21f, 17f); lineTo(16f, 10f)
                lineTo(19f, 10f); close()
                moveTo(12f, 17f); lineTo(12f, 21f)
            }
            3 -> {
                moveTo(2f, 10f)
                cubicTo(5f, 10f, 5f, 6f, 9f, 6f)
                cubicTo(13f, 6f, 13f, 11f, 17f, 11f)
                cubicTo(19f, 11f, 20f, 9f, 22f, 8f)
                moveTo(2f, 17f)
                cubicTo(5f, 17f, 5f, 13f, 9f, 13f)
                cubicTo(13f, 13f, 13f, 18f, 17f, 18f)
                cubicTo(19f, 18f, 20f, 16f, 22f, 15f)
            }
        }
    }

    override fun draw(canvas: Canvas) {
        val saved = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        canvas.drawPath(drawing, ink)
        canvas.restoreToCount(saved)
    }

    override fun setAlpha(alpha: Int) {
        ink.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        ink.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Opacity is handled by the renderer")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
