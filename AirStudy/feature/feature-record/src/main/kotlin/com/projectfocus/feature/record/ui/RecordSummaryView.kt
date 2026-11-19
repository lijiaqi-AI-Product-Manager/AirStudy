package com.projectfocus.feature.record.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import com.projectfocus.core.ui.R as UiR
import com.projectfocus.feature.record.list.RecordSummary





























class RecordSummaryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var summary: RecordSummary? = null

    fun bind(s: RecordSummary) {
        summary = s
        requestLayout()
        invalidate()
    }

    private val countPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(UiR.color.text_primary)
        textSize = sp(28f)
        isFakeBoldText = true
    }

    private val unitPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(UiR.color.text_secondary)
        textSize = sp(14f)
    }

    private val detailPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(UiR.color.text_tertiary)
        textSize = sp(12f)
    }

    init {
        setWillNotDraw(false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(200 + paddingLeft + paddingRight, widthMeasureSpec)
        val h = paddingTop +
            lineHeight(countPaint) +
            dp(4f) +
            lineHeight(detailPaint) +
            paddingBottom
        setMeasuredDimension(w, resolveSize(h.toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val s = summary ?: return
        val left = paddingLeft + dp(16f)

        // 「12 趟」：数字用大字，单位用小字且基线对齐
        val countText = s.flightCount.toString()
        val base = paddingTop + lineHeight(countPaint) * 0.8f
        canvas.drawText(countText, left, base, countPaint)

        val countW = countPaint.measureText(countText)

        //   小字会显得"浮在上面"。下移一点点才是排版上的正确做法
        //   （CSS 里叫 `baseline-shift`）。
        canvas.drawText("趟", left + countW + dp(4f), base + dp(4f), unitPaint)

        // 细节行
        val detailY = base + dp(4f) + lineHeight(detailPaint) * 1.2f
        val detail = buildString {
            append(s.totalHoursText)
            append("  ·  ")
            append(s.totalDistanceText)
            if (s.countryCount > 0) {
                append("  ·  ")
                append(s.countryCount).append(" 个国家")
            }
        }

        val save = canvas.save()
        canvas.clipRect(left, detailY - detailPaint.textSize * 1.6f, width.toFloat(), detailY + detailPaint.textSize)
        try {
            canvas.drawText(detail, left, detailY, detailPaint)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val s = summary
        info.contentDescription = if (s == null) null else buildString {
            append("共 ").append(s.flightCount).append(" 趟飞行，")
            append("累计 ").append(s.totalHoursText).append("，")
            append("总里程 ").append(s.totalDistanceText)
            if (s.countryCount > 0) append("，途经 ").append(s.countryCount).append(" 个国家")
        }

        info.isFocusable = false
    }

    private fun lineHeight(p: TextPaint): Float = p.fontMetrics.let { it.descent - it.ascent }
    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics,
    )
}
