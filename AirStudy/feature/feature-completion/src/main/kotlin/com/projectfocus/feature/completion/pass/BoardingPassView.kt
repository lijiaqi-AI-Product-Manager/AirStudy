package com.projectfocus.feature.completion.pass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.withRotation
import com.projectfocus.core.ui.R as UiR






































class BoardingPassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    init {





















        setWillNotDraw(false)
    }

    // ─────────────────────────────────────────────────────────
    // 数据
    // ─────────────────────────────────────────────────────────








    var data: BoardingPassData? = null
        set(value) {
            if (field == value) return
            field = value

            //   （比如城市名从 2 字变 4 字，会换行）
            requestLayout()
            invalidate()
        }

    // ─────────────────────────────────────────────────────────
    // 画笔（构造期建好，onDraw 只改属性）
    // ─────────────────────────────────────────────────────────

    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = color(UiR.color.bg_elevated)
    }

    private val notchPath = Path()
    private val paperPath = Path()
    private val bodyRect = RectF()

    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = color(UiR.color.divider)
    }

    private val stampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
        letterSpacing = 0.12f
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.MONOSPACE,
            android.graphics.Typeface.BOLD,
        )
    }

    // 文字画笔：每种字号一个，避免每次改 textSize
    private val codePaint = textPaint(34f, UiR.color.text_primary, bold = true)
    private val cityPaint = textPaint(12f, UiR.color.text_secondary)
    private val timePaint = textPaint(15f, UiR.color.text_primary, bold = true)
    private val labelPaint = textPaint(9f, UiR.color.text_tertiary)
    private val valuePaint = textPaint(13f, UiR.color.text_primary, bold = true)
    private val headerPaint = textPaint(10f, UiR.color.text_tertiary).apply {
        letterSpacing = 0.24f
    }

    private fun textPaint(sizeSp: Float, colorRes: Int, bold: Boolean = false) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sp(sizeSp)
            color = color(colorRes)
            if (bold) {
                typeface = android.graphics.Typeface.create(
                    android.graphics.Typeface.DEFAULT,
                    android.graphics.Typeface.BOLD,
                )
            }
        }

    private fun color(res: Int) = ContextCompat.getColor(context, res)

    private fun dp(v: Float) = v * resources.displayMetrics.density

















    private fun sp(v: Float) = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_SP,
        v,
        resources.displayMetrics,
    )

    // ─────────────────────────────────────────────────────────
    // 尺寸
    // ─────────────────────────────────────────────────────────

    /** 撕票口半圆半径 */
    private val notchRadius = dp(10f)

    /** 内容内边距 */
    private val inner = dp(20f)








    private val tearRatio = 0.64f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {

        //   高度 = 内边距 + 航空公司行 + 主区 + 信息条 + 底部
        val desiredW = dp(340f).toInt()
        val desiredH = (
            inner * 2 +            // 上下内边距
                dp(24f) +          // 顶栏 "BOARDING PASS"
                dp(12f) +          // 间距
                dp(58f) +          // 主区（三字码大字 + 城市 + 时间）
                dp(20f) +          // 间距
                dp(44f) +          // 信息条（两行标签+值）
                dp(20f) +          // 间距
                dp(36f)          // 底部（条码占位）
            ).toInt()

        setMeasuredDimension(
            resolveSize(desiredW, widthMeasureSpec),

            //   表现是"底部信息条被压掉一半"。
            resolveSize(desiredH + paddingTop + paddingBottom + dp(12f).toInt(), heightMeasureSpec),
        )
    }






















    private val contentRect: RectF
        get() {
            val inset = dp(6f)
            return RectF(
                paddingLeft + inset,
                paddingTop + inset,
                width - paddingRight - inset,
                height - paddingBottom - inset,
            )
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = data ?: return

        val cr = contentRect
        if (cr.width() <= 0f || cr.height() <= 0f) return

        drawPaper(canvas, cr)
        drawTearLine(canvas, cr)

        val tearX = cr.left + cr.width() * tearRatio
        drawHeader(canvas, cr)
        drawRoute(canvas, cr, d, tearX)
        drawInfoRow(canvas, cr, d)
        drawStub(canvas, cr, d, tearX)
        drawStamp(canvas, cr, d)
    }

    // ─────────────────────────────────────────────────────────
    // ① 纸面 + 撕票口
    // ─────────────────────────────────────────────────────────















    private fun drawPaper(canvas: Canvas, cr: RectF) {
        val radius = dp(16f)
        val tearX = cr.left + cr.width() * tearRatio

        paperPath.reset()
        paperPath.addRoundRect(cr, radius, radius, Path.Direction.CW)

        // 上方缺口
        notchPath.reset()
        notchPath.addCircle(tearX, cr.top, notchRadius, Path.Direction.CW)
        // 下方缺口
        notchPath.addCircle(tearX, cr.bottom, notchRadius, Path.Direction.CW)

        if (!paperPath.op(notchPath, Path.Op.DIFFERENCE)) {

            //   宁可少两个缺口，也不要画出个空白。
            paperPath.reset()
            paperPath.addRoundRect(cr, radius, radius, Path.Direction.CW)
        }

        canvas.drawPath(paperPath, paperPaint)
    }







    private fun drawTearLine(canvas: Canvas, cr: RectF) {
        val tearX = cr.left + cr.width() * tearRatio
        dashPaint.pathEffect = android.graphics.DashPathEffect(
            floatArrayOf(dp(4f), dp(4f)),
            0f,
        )
        canvas.drawLine(
            tearX,
            cr.top + notchRadius + dp(4f),
            tearX,
            cr.bottom - notchRadius - dp(4f),
            dashPaint,
        )
    }

    // ─────────────────────────────────────────────────────────
    // ② 顶栏
    // ─────────────────────────────────────────────────────────

    private fun drawHeader(canvas: Canvas, cr: RectF) {
        var y = cr.top + inner + headerPaint.textSize
        canvas.drawText("BOARDING PASS", cr.left + inner, y, headerPaint)

        // 右侧小字：日期
        data?.let { d ->
            val w = headerPaint.measureText(d.date)
            canvas.drawText(d.date, cr.right - inner - w, y, headerPaint)
        }
        y += dp(6f)
        // 一条极淡的分隔线，把顶栏和主区分开
        dashPaint.pathEffect = null
        dashPaint.color = color(UiR.color.divider)
        canvas.drawLine(cr.left + inner, y, cr.right - inner, y, dashPaint)
    }

    // ─────────────────────────────────────────────────────────
    // ③ 主区：出发 → 到达
    // ─────────────────────────────────────────────────────────








    private fun drawRoute(canvas: Canvas, cr: RectF, d: BoardingPassData, tearX: Float) {
        val baseY = cr.top + inner + dp(24f) + dp(12f) + codePaint.textSize + dp(10f)
        val leftX = cr.left + inner
        val rightX = tearX - inner

        // 出发
        canvas.drawText(d.departureCode, leftX, baseY, codePaint)
        canvas.drawText(d.departureCity, leftX, baseY + dp(16f), cityPaint)
        canvas.drawText(d.departureTime, leftX, baseY + dp(34f), timePaint)

        // 到达（右对齐到撕口线左侧）
        val arrW = codePaint.measureText(d.arrivalCode)
        canvas.drawText(d.arrivalCode, rightX - arrW, baseY, codePaint)
        val cityW = cityPaint.measureText(d.arrivalCity)
        canvas.drawText(d.arrivalCity, rightX - cityW, baseY + dp(16f), cityPaint)
        val timeW = timePaint.measureText(d.arrivalTime)
        canvas.drawText(d.arrivalTime, rightX - timeW, baseY + dp(34f), timePaint)

        // 中间一条水平虚线 + 小飞机占位（用 "✈" 字符，不引图标资源）
        dashPaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f)
        dashPaint.color = color(UiR.color.stroke_subtle)
        val midY = baseY - codePaint.textSize / 2.2f
        canvas.drawLine(leftX + dp(46f), midY, rightX - dp(46f), midY, dashPaint)
    }

    // ─────────────────────────────────────────────────────────
    // ④ 信息条：航班号 / 座位 / 里程 / 专注
    // ─────────────────────────────────────────────────────────










    private fun drawInfoRow(canvas: Canvas, cr: RectF, d: BoardingPassData) {
        val tearX = cr.left + cr.width() * tearRatio
        val top = cr.top + inner + dp(24f) + dp(12f) + dp(58f) + dp(20f)
        val leftX = cr.left + inner
        val usableW = tearX - inner - leftX
        val colW = usableW / 4f

        val cols = listOf(
            "FLIGHT" to d.flightNo,
            "SEAT" to d.seat,
            "DISTANCE" to d.distanceText,
            "FOCUS" to d.focusDurationText,
        )
        cols.forEachIndexed { i, (label, value) ->
            val x = leftX + colW * i
            canvas.drawText(label, x, top + labelPaint.textSize, labelPaint)
            canvas.drawText(value, x, top + labelPaint.textSize + dp(18f), valuePaint)
        }
    }

    // ─────────────────────────────────────────────────────────
    // ⑤ 存根（撕口右侧）
    // ─────────────────────────────────────────────────────────









    private fun drawStub(canvas: Canvas, cr: RectF, d: BoardingPassData, tearX: Float) {
        val stubLeft = tearX + inner * 0.6f
        val stubRight = cr.right - inner
        val centerX = (stubLeft + stubRight) / 2f

        // 竖排的航班号（旋转 90°）
        val flightW = valuePaint.measureText(d.flightNo)
        canvas.withRotation(90f, centerX, cr.centerY()) {
            drawText(
                d.flightNo,
                centerX - flightW / 2f,
                cr.centerY() + valuePaint.textSize / 3f,
                valuePaint,
            )
        }
    }

    // ─────────────────────────────────────────────────────────
    // ⑥ 印章
    // ─────────────────────────────────────────────────────────


    private fun drawStamp(canvas: Canvas, cr: RectF, d: BoardingPassData) {
        val tearX = cr.left + cr.width() * tearRatio
        val stubCenterX = (tearX + cr.right) / 2f

        stampPaint.color = color(
            if (d.abandoned) UiR.color.semantic_warning else UiR.color.accent_primary,
        )
        stampPaint.alpha = (d.completionRatio * 255 * 0.55f + 255 * 0.25f).toInt().coerceIn(60, 255)

        // 以存根中心为轴旋转 12°。为什么是 12°：小于 8° 看着像"歪了"，
        // 大于 16° 会顶到登机牌边缘。
        val textW = stampPaint.measureText(d.stampText)
        canvas.withRotation(12f, stubCenterX, cr.centerY()) {
            drawText(
                d.stampText,
                stubCenterX - textW / 2f,
                cr.centerY() + stampPaint.textSize / 3f,
                stampPaint,
            )


            //
            //   原来的写法是 `canvas.save()` → 画字 → `canvas.restore()` →
            //   画框。也就是**框没跟着转**。
            //   结果是：文字斜 12°，外框正着 —— 看起来像"章盖歪了而且框是别人画的"，
            //   在真实的印章视觉里是完全说不通的。
            //

            //     `canvas.restore()` 之后画的框就落在**未旋转**的坐标系里，
            //     位置还算不出来（它的坐标是按旋转后的字宽推的）。

            //     要确认这段代码里"到底哪些绘制属于这个变换"。**
            //     机械替换会悄悄改变图层归属。
            val pad = dp(6f)
            val frameH = stampPaint.textSize + pad * 2
            val frameW = textW + pad * 3
            drawRoundRect(
                stubCenterX - frameW / 2f,
                cr.centerY() - frameH / 2f + stampPaint.textSize / 3f - pad / 2f,
                stubCenterX + frameW / 2f,
                cr.centerY() + frameH / 2f + stampPaint.textSize / 3f - pad / 2f,
                dp(4f),
                dp(4f),
                stampPaint,
            )
        }
    }

    /** 供无障碍读屏用：整块登机牌作为一句话朗读 */
    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        data?.let { d ->

            //   所以必须手动拼出可读文本，否则视障用户完全无法知道登机牌内容。
            info.contentDescription = buildString {
                append(d.departureCity.ifEmpty { d.departureCode })
                append(" 到 ")
                append(d.arrivalCity.ifEmpty { d.arrivalCode })
                append("，航班 ")
                append(d.flightNo)
                append("，")
                append(d.date)
                append(" ")
                append(d.departureTime)
                append(" 起飞，")
                append("座位 ")
                append(d.seat)
                append("，飞行 ")
                append(d.distanceText)
                append("，专注 ")
                append(d.focusDurationText)
                append(if (d.abandoned) "，已取消" else "，已完成")
            }
        }
    }
}
