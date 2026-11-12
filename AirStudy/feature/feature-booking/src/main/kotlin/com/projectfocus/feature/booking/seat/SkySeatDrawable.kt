package com.projectfocus.feature.booking.seat

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils

/** A small raised chair inside a larger touch target; no bitmap or animation loop. */
internal class SkySeatDrawable(
    private val light: Boolean,
    private val selected: Boolean,
    private val occupied: Boolean,
    private val accent: Int = Color.rgb(44, 100, 205),
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pressed = false
    private var opacity = 255
    private var filter: ColorFilter? = null
    private var back: Shader? = null
    private var cushion: Shader? = null
    private val fill = when {
        occupied -> if (light) Color.rgb(178, 187, 201) else Color.rgb(60, 71, 92)
        selected -> accent
        light -> Color.rgb(218, 231, 249)
        else -> Color.rgb(71, 93, 128)
    }

    override fun onBoundsChange(bounds: Rect) {
        back = LinearGradient(0f, 4f, 0f, 29f,
            ColorUtils.blendARGB(fill, Color.WHITE, if (light) .48f else .12f), fill, Shader.TileMode.CLAMP)
        cushion = LinearGradient(0f, 27f, 0f, 42f,
            ColorUtils.blendARGB(fill, Color.WHITE, .22f),
            ColorUtils.blendARGB(fill, Color.BLACK, .13f), Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val saved = if (opacity == 255) canvas.save() else canvas.saveLayerAlpha(
            bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), opacity)
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 48f, bounds.height() / 48f)
        if (pressed) { canvas.translate(0f, 1f);canvas.scale(.975f, .975f, 24f, 24f) }
        paint.alpha = 255;paint.colorFilter = filter;paint.style = Paint.Style.FILL;paint.shader = null
        paint.color = if (light) 0x18445D8B else 0x35000000
        canvas.drawRoundRect(7f, 13f, 42f, 44f, 8f, 8f, paint)
        paint.alpha = 255;paint.shader = back
        canvas.drawRoundRect(8f, 5f, 40f, 32f, 8f, 8f, paint)
        paint.shader = null;paint.style = Paint.Style.STROKE;paint.strokeWidth = .8f
        paint.color = if (light) 0xC7FFFFFF.toInt() else 0x407FA0CD
        canvas.drawRoundRect(8f, 5f, 40f, 32f, 8f, 8f, paint)
        paint.style = Paint.Style.FILL;paint.alpha = 255;paint.shader = cushion
        canvas.drawRoundRect(9f, 26f, 39f, 41f, 5f, 5f, paint)
        paint.shader = null;paint.color = ColorUtils.blendARGB(fill, Color.BLACK, .09f)
        canvas.drawRoundRect(4f, 22f, 10f, 38f, 3f, 3f, paint)
        canvas.drawRoundRect(38f, 22f, 44f, 38f, 3f, 3f, paint)
        paint.color = if (selected) 0xB3FFFFFF.toInt() else if (light) 0xA3FFFFFF.toInt() else 0x458FB4E7
        paint.strokeWidth = .8f
        canvas.drawLine(13f, 11f, 35f, 11f, paint)
        if (occupied) {
            paint.color = if (light) 0xFF6E7D94.toInt() else 0xFFA9B6CB.toInt()
            paint.strokeWidth = 1.7f;paint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(21f, 16f, 27f, 22f, paint);canvas.drawLine(27f, 16f, 21f, 22f, paint)
            paint.strokeCap = Paint.Cap.BUTT
        }
        canvas.restoreToCount(saved)
    }

    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean {
        val next = android.R.attr.state_pressed in state
        if (next == pressed) return false
        pressed = next;invalidateSelf();return true
    }
    override fun setAlpha(alpha: Int) { opacity = alpha;invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { filter = colorFilter;invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
