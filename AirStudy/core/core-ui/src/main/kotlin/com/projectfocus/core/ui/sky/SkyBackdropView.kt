package com.projectfocus.core.ui.sky

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.projectfocus.core.ui.R

/** Static, resolution independent ambient light: no blur buffers or frame loop. */
class SkyBackdropView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val blue = Paint(Paint.ANTI_ALIAS_FLAG)
    private val violet = Paint(Paint.ANTI_ALIAS_FLAG)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        val base = context.getColor(R.color.sky_canvas)
        blue.shader = RadialGradient(w * .9f, h * .09f, w * .85f,
            context.getColor(R.color.sky_glow_blue), base, Shader.TileMode.CLAMP)
        violet.shader = RadialGradient(w * .05f, h * .35f, w * .75f,
            intArrayOf(context.getColor(R.color.sky_glow_violet), 0x00000000), null, Shader.TileMode.CLAMP)
    }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(context.getColor(R.color.sky_canvas))
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), blue)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), violet)
    }
}
