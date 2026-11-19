package com.projectfocus.app.auth

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import com.projectfocus.core.ui.sky.ShareArtworkFrame
import com.projectfocus.core.ui.R as UiR

/** The cover's paper plane and sky also work when the daily photo is unavailable. */
internal class AuthSkyDrawable(private val context: Context, private val cover: Boolean = false) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES
    private val canvasColor = context.getColor(UiR.color.sky_canvas)
    private val blue = context.getColor(UiR.color.sky_primary)
    private val selected = context.getColor(UiR.color.sky_selected)
    private val violet = context.getColor(UiR.color.sky_glow_violet)
    private val cloudColor = ColorUtils.blendARGB(canvasColor, Color.WHITE, if (night) 0.14f else 0.8f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trail = Path()
    private var gradients = emptyList<Shader>()
    private var layerAlpha = 255
    private var filter: ColorFilter? = null

    override fun onBoundsChange(bounds: Rect) {
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        if (width <= 0 || height <= 0) return
        val top = if (cover) ColorUtils.blendARGB(canvasColor, blue, if (night) 0.29f else 0.2f) else canvasColor
        gradients = listOf(
            LinearGradient(0f, 0f, width, height, intArrayOf(top, selected, canvasColor), null, Shader.TileMode.CLAMP),
            glow(width * 0.88f, height * 0.1f, width * 0.8f, blue, if (cover) 104 else 38),
            glow(width * 0.03f, height * 0.66f, width * 0.92f, violet, if (cover) 155 else 77),
        )
        trail.reset()
        trail.moveTo(width * 0.12f, height * 0.62f)
        trail.cubicTo(width * 0.32f, height * 0.73f, width * 0.42f, height * 0.22f, width * 0.69f, height * 0.38f)
    }

    override fun draw(canvas: Canvas) {
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        if (width <= 0 || height <= 0) return
        val layer = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        paint.colorFilter = filter
        paint.alpha = layerAlpha
        gradients.forEach { shader ->
            paint.shader = shader
            canvas.drawRect(0f, 0f, width, height, paint)
        }
        paint.shader = null
        if (cover) drawCover(canvas, width, height)
        canvas.restoreToCount(layer)
    }

    private fun drawCover(canvas: Canvas, width: Float, height: Float) {
        paint.color = cloudColor
        paint.alpha = layerAlpha * 3 / 4
        cloud(canvas, width * 0.05f, height * 0.49f, minOf(24 * density, width / 14f))
        cloud(canvas, width * 0.69f, height * 0.62f, minOf(19 * density, width / 16f))

        paint.color = blue
        paint.alpha = layerAlpha * 95 / 255
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        canvas.drawPath(trail, paint)
        paint.style = Paint.Style.FILL

        val plane = canvas.save()
        canvas.translate(width * 0.69f, height * 0.38f)
        canvas.rotate(-8f)
        val scale = (width / (100 * density)).coerceIn(1.45f, 3.3f)
        canvas.scale(scale, scale)
        ShareArtworkFrame.drawPlane(canvas, context, 0f, 0f, ColorUtils.setAlphaComponent(blue, layerAlpha))
        canvas.restoreToCount(plane)
        paint.color = Color.WHITE
        paint.alpha = layerAlpha * 3 / 4
        paint.strokeWidth = 1.3f * density
        glint(canvas, width * 0.88f, height * 0.18f)
        glint(canvas, width * 0.43f, height * 0.23f)
    }

    private fun cloud(canvas: Canvas, x: Float, y: Float, r: Float) {
        canvas.drawCircle(x + r, y, r, paint)
        canvas.drawCircle(x + r * 2.1f, y - r * 0.45f, r * 1.1f, paint)
        canvas.drawCircle(x + r * 3f, y, r * 0.8f, paint)
        canvas.drawRoundRect(x, y - r * 0.1f, x + r * 3.8f, y + r * 0.8f, r * 0.6f, r * 0.6f, paint)
    }

    private fun glint(canvas: Canvas, x: Float, y: Float) {
        val size = 3 * density
        canvas.drawLine(x - size, y, x + size, y, paint)
        canvas.drawLine(x, y - size, x, y + size, paint)
    }

    private fun glow(x: Float, y: Float, radius: Float, color: Int, alpha: Int): Shader =
        RadialGradient(x, y, radius, ColorUtils.setAlphaComponent(color, alpha), Color.TRANSPARENT, Shader.TileMode.CLAMP)

    override fun getOutline(outline: Outline) {
        if (cover) outline.setRoundRect(bounds, 24f * density) else outline.setRect(bounds)
        outline.alpha = layerAlpha / 255f
    }

    override fun setAlpha(alpha: Int) {
        layerAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        filter = colorFilter
        invalidateSelf()
    }

    @Suppress("DEPRECATION")
    override fun getOpacity() = if (layerAlpha == 255) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT
}
