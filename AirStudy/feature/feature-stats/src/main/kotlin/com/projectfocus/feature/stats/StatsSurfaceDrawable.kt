package com.projectfocus.feature.stats

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

internal class StatsSurfaceDrawable(private val visual: StatsVisual, radius: Float) : Drawable() {
    private val density = visual.context.resources.displayMetrics.density
    private val radiusPx = density * radius
    private val area = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = maxOf(1f,0.8f*density) }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = maxOf(1f,density) }
    private var paintAlpha = 255
    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        area.set(bounds.left+density*0.5f,bounds.top+density*0.5f,bounds.right-density*0.5f,bounds.bottom-density*0.5f)
        if (area.height()<=0) return
        fill.shader = LinearGradient(0f,area.top,0f,area.bottom,
            intArrayOf(visual.mix(visual.secondarySurface,visual.border,0.13f),visual.secondarySurface,visual.mix(visual.secondarySurface,visual.surface,0.24f)),
            floatArrayOf(0f,0.42f,1f),Shader.TileMode.CLAMP)
        inner.shader = LinearGradient(0f,area.top,0f,area.bottom,
            intArrayOf(visual.alpha(visual.border,0.2f),Color.TRANSPARENT,visual.alpha(visual.surface,0.78f)),
            floatArrayOf(0f,0.5f,1f),Shader.TileMode.CLAMP)
        edge.color = visual.alpha(visual.border,0.28f)
    }
    override fun draw(canvas: Canvas) {
        fill.alpha=paintAlpha;edge.alpha=paintAlpha;inner.alpha=paintAlpha
        canvas.drawRoundRect(area,radiusPx,radiusPx,fill)
        canvas.drawRoundRect(area,radiusPx,radiusPx,edge)
        val inset=1.35f*density
        val inside=RectF(area).apply { inset(inset,inset) }
        canvas.drawRoundRect(inside,maxOf(0f,radiusPx-inset),maxOf(0f,radiusPx-inset),inner)
    }
    override fun setAlpha(alpha: Int) { paintAlpha=alpha.coerceIn(0,255);invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { fill.colorFilter=filter;edge.colorFilter=filter;inner.colorFilter=filter;invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
