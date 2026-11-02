package com.projectfocus.feature.record.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.projectfocus.core.ui.R as UiR
import com.projectfocus.feature.record.list.FlightStatus
import com.projectfocus.feature.record.list.RecordItem














































class RecordCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // ─────────────────────────────────────────────────────────
    // 数据
    // ─────────────────────────────────────────────────────────

    private var item: RecordItem? = null

    /** 是否画下分割线（分组内最后一张不画） */
    var showDivider: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    fun bind(item: RecordItem?) {
        this.item = item

        //   不 invalidate 屏幕上还是旧内容（只在滚动超过一屏时暴露）。
        invalidate()

        //   父容器如果缓存了旧的 measuredHeight 会裁掉最后一行。
        requestLayout()
    }

    // ─────────────────────────────────────────────────────────
    // Paint
    // ─────────────────────────────────────────────────────────

    private val codePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(UiR.color.text_primary)
        textSize = sp(22f)
        isFakeBoldText = true
        letterSpacing = 0.06f

        //   但不是每个字体都有等宽数字族，所以不设 typeface —— 只用字距。
    }

    private val cityPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(UiR.color.text_secondary)
        textSize = sp(12f)
    }

    private val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(UiR.color.text_tertiary)
        textSize = sp(12f)
    }

    private val statusPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(11f)
        isFakeBoldText = true
    }

    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(UiR.color.text_tertiary)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        strokeCap = Paint.Cap.ROUND

        //   否则抛 IllegalArgumentException（`interval 0 must be > 0`）。
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f)
    }

    private val barTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(UiR.color.divider)
    }

    private val barFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(UiR.color.divider)
    }

    private val statusBarPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ─────────────────────────────────────────────────────────
    // 几何缓存（避免 onDraw 里分配）
    // ─────────────────────────────────────────────────────────

    private val routePath = Path()
    private val planePath = Path()
    private val tmpRect = RectF()

    init {

        //   `View.WILL_NOT_DRAW` 置位时，`View.draw()` 在调 `onDraw()`
        //   之前就 return 了。
        setWillNotDraw(false)

        //   处理），但要能被 TalkBack 聚焦 —— focusable 即可，不用 clickable。
        isFocusable = true
    }

    // ─────────────────────────────────────────────────────────
    // 测量
    // ─────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(MIN_WIDTH + paddingLeft + paddingRight, widthMeasureSpec)


        val codeH = lineHeight(codePaint)
        val cityH = lineHeight(cityPaint)
        val metaH = lineHeight(metaPaint)
        val barH = dp(6f)


        //   用户把字体调到 160% 时 22sp 会变成 35sp，硬编码高度会裁掉码。
        val desired = paddingTop + codeH + dp(2f) + cityH + dp(10f) + metaH + dp(8f) + barH + paddingBottom
        setMeasuredDimension(w, resolveSize(desired.toInt(), heightMeasureSpec))
    }

    // ─────────────────────────────────────────────────────────
    // 绘制
    // ─────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val it = item ?: return
        val padH = dp(16f)
        val contentLeft = paddingLeft + padH
        val contentRight = width - paddingRight - padH

        drawStatusBar(canvas, it, contentLeft, padH)
        drawCodes(canvas, it, contentLeft, contentRight)
        drawMeta(canvas, it, contentLeft, contentRight)
        drawProgress(canvas, it, contentLeft, contentRight)
        drawDivider(canvas, contentLeft, contentRight)
    }









    private fun drawStatusBar(canvas: Canvas, it: RecordItem, contentLeft: Float, padH: Float) {
        statusBarPaint.color = context.getColor(
            when (it.status) {
                FlightStatus.ONGOING -> UiR.color.accent_primary
                FlightStatus.COMPLETED -> UiR.color.text_tertiary
                FlightStatus.CANCELLED -> UiR.color.semantic_error
            },
        )
        val barW = dp(3f)

        val inset = dp(12f)
        val top = paddingTop + inset
        val bottom = height - paddingBottom - inset
        if (bottom > top) {
            tmpRect.set(contentLeft - padH, top, contentLeft - padH + barW, bottom)
            val r = barW / 2
            canvas.drawRoundRect(tmpRect, r, r, statusBarPaint)
        }
    }














    private fun drawCodes(canvas: Canvas, it: RecordItem, left: Float, right: Float) {
        val topY = paddingTop + lineHeight(codePaint) * 0.8f
        canvas.drawText(it.fromCode, left, topY, codePaint)

        val toW = codePaint.measureText(it.toCode)
        canvas.drawText(it.toCode, right - toW, topY, codePaint)

        val fromW = codePaint.measureText(it.fromCode)
        val routeLeft = left + fromW + dp(10f)
        val routeRight = right - toW - dp(10f)
        if (routeRight > routeLeft + dp(24f)) {
            drawRoute(canvas, routeLeft, routeRight, topY - codePaint.textSize * 0.32f)
        }

        // 城市名（与码左对齐 / 右对齐）
        val cityTop = topY + dp(2f) + lineHeight(cityPaint) * 0.9f
        canvas.drawText(it.fromCity, left, cityTop, cityPaint)
        val toCityW = cityPaint.measureText(it.toCity)
        canvas.drawText(it.toCity, right - toCityW, cityTop, cityPaint)
    }

    private fun drawRoute(canvas: Canvas, left: Float, right: Float, y: Float) {
        val midX = (left + right) / 2f
        val sag = (right - left) * 0.08f

        routePath.reset()
        routePath.moveTo(left, y)
        routePath.quadTo(midX, y + sag * 2f, right, y)
        canvas.drawPath(routePath, routePaint)

        // 机头三角：画在弧线中点
        val planeY = y + sag
        val s = dp(4.5f)
        planePath.reset()
        planePath.moveTo(midX + s, planeY)
        planePath.lineTo(midX - s, planeY - s * 0.8f)
        planePath.lineTo(midX - s, planeY + s * 0.8f)
        planePath.close()
        canvas.drawPath(planePath, barFillPaintOf(UiR.color.text_secondary))
    }

    /** 元信息行：`08:30 起飞 · 2h 15m · 1,800km` */
    private fun drawMeta(canvas: Canvas, it: RecordItem, left: Float, right: Float) {
        val y = height - paddingBottom - dp(8f) - dp(6f) - lineHeight(metaPaint) * 0.8f
        val text = "${it.startTime} 起飞 · ${it.durationText} · ${it.distanceText}"


        //   长里程（如 "12,345km"）+ 大字体时会溢出到右边缘。
        val save = canvas.save()
        canvas.clipRect(left, y - metaPaint.textSize * 1.6f, right, y + metaPaint.textSize * 0.6f)
        try {
            canvas.drawText(text, left, y, metaPaint)

            // 状态文字右对齐
            statusPaint.color = context.getColor(
                when (it.status) {
                    FlightStatus.ONGOING -> UiR.color.accent_primary
                    FlightStatus.COMPLETED -> UiR.color.text_tertiary
                    FlightStatus.CANCELLED -> UiR.color.semantic_error
                },
            )
            val sw = statusPaint.measureText(it.status.label)
            canvas.drawText(it.status.label, right - sw, y, statusPaint)
        } finally {
            canvas.restoreToCount(save)
        }
    }








    private fun drawProgress(canvas: Canvas, it: RecordItem, left: Float, right: Float) {
        val ratio = it.completionRatio.coerceIn(0f, 1f)
        if (ratio >= 1f && it.status != FlightStatus.ONGOING) return

        val h = dp(6f)
        val bottom = height - paddingBottom - dp(8f) - lineHeight(metaPaint) - dp(8f)
        val top = bottom - h
        val r = h / 2

        tmpRect.set(left, top, right, bottom)
        canvas.drawRoundRect(tmpRect, r, r, barTrackPaint)

        val fillRight = left + (right - left) * ratio
        if (fillRight > left + h) {
            tmpRect.set(left, top, fillRight, bottom)
            canvas.drawRoundRect(tmpRect, r, r, barFillPaintOf(UiR.color.accent_primary))
        }
    }

    private fun drawDivider(canvas: Canvas, left: Float, right: Float) {
        if (!showDivider) return
        canvas.drawRect(left, height.toFloat() - 1f, right, height.toFloat(), dividerPaint)
    }

    // ─────────────────────────────────────────────────────────
    // 无障碍
    // ─────────────────────────────────────────────────────────

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val it = item
        if (it == null) {
            info.contentDescription = null
            return
        }

        //   自绘 View 对 TalkBack 是一个盒子，不讲清楚就什么都没有。
        //

        //   TalkBack 会把全大写的 3 字母当成一个词念（"pek"），
        //   中文语音下听起来像"佩克"。逐字母拼才会念成"P、E、K"。
        val from = it.fromCode.toCharArray().joinToString(" ")
        val to = it.toCode.toCharArray().joinToString(" ")
        info.contentDescription = buildString {
            append(it.fromCity).append(" 到 ").append(it.toCity)
            append("，").append(from).append(" 飞 ").append(to)
            append("，").append(it.startTime).append(" 起飞")
            append("，计划 ").append(it.durationText)
            append("，").append(it.status.label)
            if (it.status == FlightStatus.ONGOING) {
                append("，已完成 ").append((it.completionRatio * 100).toInt()).append("%")
            }
        }

        //   但要能被单独聚焦朗读，所以声明 focusable。
        info.isFocusable = isFocusable
    }

    // ─────────────────────────────────────────────────────────
    // 工具
    // ─────────────────────────────────────────────────────────

    private fun barFillPaintOf(colorRes: Int): Paint =
        barFillPaint.apply { color = context.getColor(colorRes) }








    private fun lineHeight(p: TextPaint): Float = p.fontMetrics.let { it.descent - it.ascent }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density








    private fun sp(v: Float): Float = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics,
    )

    private companion object {
        /** `wrap_content` 时的最小宽度 —— 只是为了不在布局里塌成 0 */
        const val MIN_WIDTH = 200
    }
}
