package com.projectfocus.app.freeflight

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.min
import kotlin.math.roundToInt

/** Opaque information surface; only its selected accent changes during a transition. */
internal class LandingAirportCardDrawable(
    private val density: Float,
    surfaceColor: Int,
    canvasColor: Int,
    private val edgeColor: Int,
    private val accentColor: Int,
    private val night: Boolean,
) : Drawable() {
    private val surface = surfaceColor or 0xFF000000.toInt()
    private val canvas = canvasColor or 0xFF000000.toInt()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val face = RectF()
    private val edge = RectF()
    private var radius = 0f
    private var opacity = 255
    var selectionFraction = 0f
        set(value) {
            field = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
            invalidateSelf()
        }

    override fun onBoundsChange(bounds: Rect) {
        face.set(bounds)
        radius = min(24f * density, min(face.width(), face.height()) * .5f)
        edge.set(face);edge.inset(density, density)
        fill.shader = LinearGradient(face.left, face.top, face.right, face.bottom,
            blend(surface, Color.WHITE, if (night) .035f else .12f), blend(surface, canvas, .08f), Shader.TileMode.CLAMP)
        tint.shader = LinearGradient(face.left, face.top, face.right, face.bottom,
            alpha(accentColor, if (night) 58 else 20), alpha(accentColor, if (night) 24 else 8), Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        if (face.isEmpty || opacity == 0) return
        fill.alpha = opacity
        canvas.drawRoundRect(face, radius, radius, fill)
        if (selectionFraction > 0f) {
            tint.alpha = (opacity * selectionFraction).roundToInt()
            canvas.drawRoundRect(face, radius, radius, tint)
        }
        val color = blend(edgeColor, accentColor, selectionFraction)
        border.color = color
        border.alpha = Color.alpha(color) * opacity / 255
        border.strokeWidth = density * (1f + selectionFraction * .5f)
        canvas.drawRoundRect(edge, (radius - density).coerceAtLeast(0f), (radius - density).coerceAtLeast(0f), border)
        highlight.color = Color.WHITE
        highlight.alpha = (if (night) 16 else 170) * opacity / 255
        highlight.strokeWidth = density * .7f
        val top = edge.top + density
        canvas.drawLine(face.left + radius, top, face.right - radius, top, highlight)
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, radius)
        outline.alpha = opacity / 255f
    }

    override fun setAlpha(alpha: Int) { opacity = alpha.coerceIn(0, 255);invalidateSelf() }
    override fun getAlpha(): Int = opacity
    override fun setColorFilter(colorFilter: ColorFilter?) {
        for (paint in arrayOf(fill, tint, border, highlight)) paint.colorFilter = colorFilter
        invalidateSelf()
    }
    @Deprecated("Drawable opacity is retained for older platform callers")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun alpha(color: Int, value: Int): Int = (color and 0x00FFFFFF) or (value shl 24)
    private fun blend(first: Int, second: Int, fraction: Float): Int {
        val p = fraction.coerceIn(0f, 1f)
        fun channel(a: Int, b: Int) = (a + (b - a) * p).roundToInt()
        return Color.argb(channel(Color.alpha(first), Color.alpha(second)),
            channel(Color.red(first), Color.red(second)), channel(Color.green(first), Color.green(second)),
            channel(Color.blue(first), Color.blue(second)))
    }
}
