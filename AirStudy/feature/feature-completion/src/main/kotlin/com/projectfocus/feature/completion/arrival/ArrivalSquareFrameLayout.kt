package com.projectfocus.feature.completion.arrival

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.util.AttributeSet
import android.widget.FrameLayout


class ArrivalSquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val contentClip = Path()
    private var contentCornerRadius = 0f

    fun setContentCornerRadius(radius: Float) {
        require(radius.isFinite() && radius >= 0f)
        contentCornerRadius = radius
        updateContentClip()
        invalidate()
    }

    private fun updateContentClip() {
        contentClip.reset()
        val radius = minOf(contentCornerRadius, minOf(width, height) / 2f)
        contentClip.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, Path.Direction.CW)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateContentClip()
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (contentCornerRadius == 0f) { super.dispatchDraw(canvas);return }
        val saved = canvas.save()
        canvas.clipPath(contentClip)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(saved)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 先按“宽度 = 父容器给的最大宽度”量一遍，拿到最终边长；
        // 再用同一个边长作为高度约束，避免先按 wrap_content 量成 0。
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
        val side = measuredWidth
        setMeasuredDimension(side, side)
    }
}
