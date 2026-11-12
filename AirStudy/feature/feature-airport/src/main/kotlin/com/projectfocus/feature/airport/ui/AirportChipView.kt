package com.projectfocus.feature.airport.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.projectfocus.core.ui.R as UiR














































class AirportChipView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // ─────────────────────────────────────────────────────────
    // 绘制资源
    // ─────────────────────────────────────────────────────────

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {

        //   直接写 `textSize = 13f` 是 px，在 3x 屏上是 4sp，肉眼几乎看不见。
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, TEXT_SP, resources.displayMetrics,
        )
        typeface = android.graphics.Typeface.create(
            "sans-serif-medium", android.graphics.Typeface.NORMAL,
        )
    }

    /** 三字码单独一份 paint，只改颜色/字号，其余继承 */
    private val codePaint = TextPaint(textPaint).apply {
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, CODE_SP, resources.displayMetrics,
        )
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val rect = RectF()

    /** 文字部分（不含左右 padding） */
    private var title: String = ""
    private var code: String = ""

    /** 按下时的高亮进度 0..1 —— 见类注释「96ms 渐变」 */
    private var pressProgress: Float = 0f

    private val density = resources.displayMetrics.density
    private val padH = H_PAD_DP * density
    private val padV = V_PAD_DP * density
    private val strokeW = 1f * density
    private val gap = CODE_GAP_DP * density















    private var cBgDefault = 0
    private var cBgPress = 0
    private var cStrokeDefault = 0
    private var cStrokePress = 0
    private var cTextDefault = 0
    private var cTextPress = 0
    private var cCodeDefault = 0
    private var cCodePress = 0
    private var colorsLoaded = false

    init {

        //   View 默认 WILL_NOT_DRAW = true（当它没有背景时），

        //   但完全看不见" —— 而 layout 边界是对的，用 Layout Inspector
        //   也看不出来，因为问题不在布局。
        setWillNotDraw(false)

        isClickable = true
        isFocusable = true

        //   自绘 View 不会自动获得这些，必须显式打开。
        isSoundEffectsEnabled = true
        strokePaint.strokeWidth = strokeW
    }

    // ─────────────────────────────────────────────────────────
    // 数据
    // ─────────────────────────────────────────────────────────

    /** 这个 chip 代表的机场 id。用于 RecyclerView 之外的场景（推荐区是静态铺开的） */
    var airportId: Long = -1L
        private set

    /**
     * 绑定。
     *
     * @param label 完整文案，形如 `北京 PEK`（由 [com.projectfocus.feature.airport.search.IdleRecommender.chipLabel] 生成）。
     *              **本 View 不自己拼文案** —— 拼文案的规则属于投影层，见那边的 KDoc。
     */
    fun bind(airportId: Long, label: String) {
        this.airportId = airportId

        //   英文会（`New York`）。所以不能 `split(" ")` 取第一段 ——
        //   `New York JFK` 会被切错。
        //   正确做法：从**右端**找最后一个空格，右边是三字码。

        val sep = label.lastIndexOf(' ')
        val maybeCode = if (sep >= 0) label.substring(sep + 1) else ""
        if (maybeCode.length == 3 && maybeCode.all { it in 'A'..'Z' }) {
            title = label.substring(0, sep)
            code = maybeCode
        } else {
            title = label
            code = ""
        }
        contentDescription = label
        invalidate()
        requestLayout()
    }

    // ─────────────────────────────────────────────────────────
    // 测量
    // ─────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availW = MeasureSpec.getSize(widthMeasureSpec).let {
            if (it == 0) resources.displayMetrics.widthPixels else it
        }

        val maxW = minOf(availW * MAX_WIDTH_RATIO, MAX_WIDTH_DP * density)

        val padding = paddingLeft + paddingRight + padH * 2
        val titleW = if (title.isEmpty()) 0f else textPaint.measureText(title)
        val codeW = if (code.isEmpty()) 0f else codePaint.measureText(code)
        val sepW = if (code.isEmpty() || title.isEmpty()) 0f else gap

        var contentW = titleW + sepW + codeW
        if (contentW + padding > maxW) {

            //   用户认出这个 chip 靠的是三字码（唯一、短），
            //   而不是被截成 `乌鲁木齐…` 的城市名。
            val room = (maxW - padding - sepW - codeW).coerceAtLeast(textPaint.textSize * 2)
            val ellipsized = android.text.TextUtils.ellipsize(
                title, textPaint, room, android.text.TextUtils.TruncateAt.END,
            )
            title = ellipsized.toString()
            contentW = textPaint.measureText(title) + sepW + codeW
        }

        val w = (contentW + padding).toInt().coerceAtLeast((MIN_WIDTH_DP * density).toInt())
        val h = (textPaint.textSize + codePaint.textSize * 0.2f + padV * 2 + strokeW * 2).toInt()
            .coerceAtLeast((CHIP_HEIGHT_DP * density).toInt())

        setMeasuredDimension(
            resolveSize(w, widthMeasureSpec),

            //   否则横向 LinearLayout 在空间不足时会把 chip 压扁到 20dp，
            //   文字直接溢出。
            resolveSize(h, heightMeasureSpec),
        )
    }

    // ─────────────────────────────────────────────────────────
    // 绘制
    // ─────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        loadColorsIfNeeded()

        val r = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())

        // 背景：默认色 → press 色的线性插值
        bgPaint.color = lerpColor(cBgDefault, cBgPress, pressProgress)
        canvas.drawRoundRect(rect, r, r, bgPaint)

        // 描边：默认描边色 → 高亮描边色
        strokePaint.color = lerpColor(cStrokeDefault, cStrokePress, pressProgress)

        //   看起来"只有左上两条边有框"。
        rect.inset(strokeW / 2f, strokeW / 2f)
        canvas.drawRoundRect(rect, r, r, strokePaint)
        rect.inset(-strokeW / 2f, -strokeW / 2f)

        if (title.isEmpty()) return

        // 文字整体垂直居中：用 fontMetrics 而不是 textSize/2 ——
        // 汉字与拉丁字母的基线不同，用 textSize/2 会让中文看起来偏下 1~2dp。
        val fm = textPaint.fontMetrics
        val baseline = height / 2f - (fm.ascent + fm.descent) / 2f

        val padding = paddingLeft + padH
        textPaint.color = lerpColor(cTextDefault, cTextPress, pressProgress)
        canvas.drawText(title, padding, baseline, textPaint)

        if (code.isNotEmpty()) {
            val x = padding + textPaint.measureText(title) + gap
            codePaint.color = lerpColor(cCodeDefault, cCodePress, pressProgress)
            canvas.drawText(code, x, baseline, codePaint)
        }
    }

    private fun loadColorsIfNeeded() {
        if (colorsLoaded) return
        val ctx = context
        cBgDefault = ctx.getColor(UiR.color.sky_card_solid)
        cBgPress = ctx.getColor(UiR.color.sky_selected)
        cStrokeDefault = ctx.getColor(UiR.color.sky_divider)
        cStrokePress = ctx.getColor(UiR.color.sky_primary)
        cTextDefault = ctx.getColor(UiR.color.sky_ink)
        cTextPress = ctx.getColor(UiR.color.sky_ink)
        cCodeDefault = ctx.getColor(UiR.color.sky_ink_secondary)
        cCodePress = ctx.getColor(UiR.color.sky_primary)
        colorsLoaded = true
    }

    // ─────────────────────────────────────────────────────────
    // 按压状态
    // ─────────────────────────────────────────────────────────





















    private var pressAnimator: android.animation.ValueAnimator? = null

    override fun setPressed(pressed: Boolean) {
        if (isPressed == pressed) return
        super.setPressed(pressed)

        pressAnimator?.cancel()
        val target = if (pressed) 1f else 0f
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) {
            pressProgress = target
            pressAnimator = null
            invalidate()
            return
        }

        //   用户在动画中途松手时，从"当前进度"回弹，而不是先从 1 跳到 0 再动。
        val animator = android.animation.ValueAnimator.ofFloat(pressProgress, target)
        pressAnimator = animator
        animator.duration = PRESS_ANIM_MS
        animator.interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        animator.addUpdateListener {
            pressProgress = it.animatedValue as Float
            invalidate()
        }
        animator.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()

        //   动画持有 View 引用直到结束。单次 96ms 不致命，但推荐区
        //   每次状态切换都会重建 12 个 chip —— 累积起来就是"滚一下就有

        pressAnimator?.cancel()
        pressAnimator = null
    }

    // ─────────────────────────────────────────────────────────
    // 无障碍
    // ─────────────────────────────────────────────────────────

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)

        //   会念成"什么都没"；设成 Button 后它会念"北京 PEK，按钮"，
        //   用户才知道可以点。
        info.className = "android.widget.Button"
        info.text = contentDescription
        info.isClickable = true
    }

    // ─────────────────────────────────────────────────────────
    // 工具
    // ─────────────────────────────────────────────────────────

    private fun lerpColor(from: Int, to: Int, t: Float): Int {
        if (t <= 0f) return from
        if (t >= 1f) return to
        val a = (android.graphics.Color.alpha(from) +
            (android.graphics.Color.alpha(to) - android.graphics.Color.alpha(from)) * t).toInt()
        val r = (android.graphics.Color.red(from) +
            (android.graphics.Color.red(to) - android.graphics.Color.red(from)) * t).toInt()
        val g = (android.graphics.Color.green(from) +
            (android.graphics.Color.green(to) - android.graphics.Color.green(from)) * t).toInt()
        val b = (android.graphics.Color.blue(from) +
            (android.graphics.Color.blue(to) - android.graphics.Color.blue(from)) * t).toInt()
        return android.graphics.Color.argb(a, r, g, b)
    }

    private companion object {
        const val TEXT_SP = 13f
        const val CODE_SP = 11f
        const val H_PAD_DP = 14f
        const val V_PAD_DP = 9f
        const val CODE_GAP_DP = 5f
        const val MIN_WIDTH_DP = 56f
        const val CHIP_HEIGHT_DP = 34f
        const val MAX_WIDTH_DP = 168f
        const val MAX_WIDTH_RATIO = 0.4f
        const val PRESS_ANIM_MS = 96L
    }
}
