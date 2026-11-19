package com.projectfocus.feature.record.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import com.projectfocus.core.ui.R as UiR















class RecordDayHeaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var title: String = ""
    private var summary: String = ""

    fun bind(title: String, summary: String) {
        this.title = title
        this.summary = summary

        //   十个字的宽度差 5 倍，不重新测量会被裁掉。
        requestLayout()
        invalidate()
    }

    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(UiR.color.text_primary)
        textSize = sp(14f)
        isFakeBoldText = true
    }

    private val summaryPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(UiR.color.text_tertiary)
        textSize = sp(12f)
    }

    init {
        setWillNotDraw(false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(200, widthMeasureSpec)
        val h = paddingTop + dp(28f) + paddingBottom
        setMeasuredDimension(w, resolveSize(h.toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val padH = dp(16f)
        val left = paddingLeft + padH
        val right = width - paddingRight - padH
        val base = height / 2f - (titlePaint.fontMetrics.let { it.ascent + it.descent }) / 2f

        canvas.drawText(title, left, base, titlePaint)

        if (summary.isNotEmpty()) {
            val sw = summaryPaint.measureText(summary)

            //   放不下时宁可不画 —— 挤压成两行或覆盖标题都比"没有副标题"更糟。
            val titleW = titlePaint.measureText(title)
            if (left + titleW + dp(12f) + sw <= right) {
                canvas.drawText(summary, right - sw, base, summaryPaint)
            }
        }
    }

    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.contentDescription = if (summary.isEmpty()) title else "$title，$summary"

        //   设成 `android.widget.TextView` 让 TalkBack 把它当静态文本念，
        //   而不是当成一个未知类型的盒子（后者会被跳过）。
        info.className = "android.widget.TextView"
        info.isFocusable = false
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics,
    )
}
