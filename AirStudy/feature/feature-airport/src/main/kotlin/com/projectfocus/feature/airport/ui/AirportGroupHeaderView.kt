package com.projectfocus.feature.airport.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.projectfocus.feature.airport.R













class AirportGroupHeaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var title: String = ""
    private var count: Int = 0

    private val titlePaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(com.projectfocus.core.ui.R.color.text_tertiary)
        textSize = sp(12f)
        letterSpacing = 0.04f
    }
    private val countPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(com.projectfocus.core.ui.R.color.text_tertiary)
        textSize = sp(12f)
    }

    private val padH = dp(16f)
    private val padV = dp(10f)

    init {

        setWillNotDraw(false)
    }

    fun bind(title: String, count: Int) {
        this.title = title
        this.count = count

        //   不 requestLayout() 的话换一个更长的标题会被裁掉。
        requestLayout()
        invalidate()


        //
        //   `AirportGroupHeaderView` 是**完全自绘**的：它没有子 View，也没有
        //   `text` 属性（标题存在自己的 `title` 字段里，onDraw 时画）。
        //   对 TalkBack 来说这就是**一个空白控件** ——
        //   它会念"未加标签的按钮/项目"，或者干脆跳过。
        //
        //   后果：读屏用户搜出 3 组结果，却听不到"最近""热门""全部"这些分组名，

        //
        //   → 用 `airport_group_desc`（"%1$s，%2$d 个结果"）拼一句完整的话。
        //     顺带把"这一组有多少条"也念出来，用户就知道这一组到哪里结束。
        contentDescription = context.getString(
            R.string.airport_group_desc,
            title,
            count,
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(SUGGESTED_MIN_WIDTH, widthMeasureSpec)
        val textH = titlePaint.fontMetrics.let { it.descent - it.ascent }
        val h = resolveSize((textH + padV * 2).toInt(), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val fm = titlePaint.fontMetrics
        val baseline = paddingTop + padV - fm.ascent

        val label = if (count > 0) "$title$SEP$count" else title
        canvas.drawText(label, paddingLeft + padH, baseline, titlePaint)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)

        info.contentDescription = if (count > 0) "$title，共 $count 项" else title
        info.className = "android.widget.TextView"
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun sp(v: Float): Float = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics,
    )

    private companion object {
        const val SEP = " · "
        const val SUGGESTED_MIN_WIDTH = 120
    }
}
