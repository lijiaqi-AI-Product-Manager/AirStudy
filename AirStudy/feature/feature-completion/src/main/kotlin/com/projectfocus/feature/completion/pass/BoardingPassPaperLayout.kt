package com.projectfocus.feature.completion.pass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.FrameLayout


class BoardingPassPaperLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        // 软件层才能稳定画 setShadowLayer；只在纸面这一层开，子 View 不受影响。
        setShadowLayer(dp(8f), 0f, dp(3f), 0x33000000)
    }

    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x22000000
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
    }

    private val paperPath = Path()
    private val paperRect = RectF()
    private var tearX = 0f

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        val inset = dp(10f)
        if (w <= inset * 2f || h <= inset * 2f) return

        paperRect.set(inset, inset, w - inset, h - inset)
        tearX = paperRect.left + paperRect.width() * TEAR_RATIO

        paperPath.reset()
        paperPath.fillType = Path.FillType.EVEN_ODD
        paperPath.addRoundRect(paperRect, dp(16f), dp(16f), Path.Direction.CW)
        // 上下两个半圆缺口：圆心落在纸面边缘，even-odd 会把圆内区域挖掉。
        paperPath.addCircle(tearX, paperRect.top, dp(10f), Path.Direction.CW)
        paperPath.addCircle(tearX, paperRect.bottom, dp(10f), Path.Direction.CW)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (paperRect.width() <= 0f || paperRect.height() <= 0f) return

        canvas.drawPath(paperPath, paperPaint)
        canvas.drawLine(
            tearX,
            paperRect.top + dp(10f),
            tearX,
            paperRect.bottom - dp(10f),
            dashPaint,
        )
    }

    private fun dp(value: Float): Float = value * density

    companion object {

        const val TEAR_RATIO = 0.64f
    }
}
