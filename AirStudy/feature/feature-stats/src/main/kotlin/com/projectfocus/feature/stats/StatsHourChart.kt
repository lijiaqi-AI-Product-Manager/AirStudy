package com.projectfocus.feature.stats

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.content.res.ResourcesCompat
import com.projectfocus.core.ui.R as UiR
import com.projectfocus.service.focus.history.HistoryAccount
import java.util.Locale
import kotlin.math.max

class StatsHourChart(context: Context) : View(context) {
    private val visual = StatsVisual(context)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val axis = text(10f, false)
    private val label = text(12f, true)
    private val value = text(12f, true)
    private val shape = StatsTooltipShape(visual)
    private var model: StatsHourHabits? = null
    private var owner: HistoryAccount? = null
    private var animation: ValueAnimator? = null
    private var fraction = 1f
    private var anchorY = 0f
    private val hide = Runnable { clearSelection() }
    var selectedHour: Int? = null; private set
    internal var tooltipBounds: RectF? = null; private set
    var interactionAllowed: () -> Boolean = { true }

    init {
        isClickable = true; isFocusable = true
        contentDescription = context.getString(R.string.stats_hour_chart_accessibility_hint)
    }
    private fun dp(n: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, n, resources.displayMetrics)
    private fun space(n: Float) = n * resources.displayMetrics.density
    private fun sp(n: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, n, resources.displayMetrics)
    private fun text(size: Float, medium: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(size); textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, if (medium) UiR.font.harmonyos_sans_sc_medium else UiR.font.harmonyos_sans_sc_light)
    }
    private fun current() = owner?.isCurrent() == true
    internal fun bind(data: StatsHourHabits, account: HistoryAccount) {
        if (model == data && owner == account) return
        clearSelection(); finishAnimation()
        model = data; owner = account
        fraction = if (ValueAnimator.areAnimatorsEnabled() && isAttachedToWindow && isShown) 0f else 1f
        if (fraction == 0f) {
            animation = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 650; interpolator = PathInterpolator(.4f, 0f, .2f, 1f)
                addUpdateListener { animator ->
                    if (!current()) clear() else { fraction = animator.animatedValue as Float; invalidate() }
                }
                start()
            }
        }
        invalidate()
    }
    internal fun clear() { finishAnimation(); clearSelection(); model = null; owner = null; invalidate() }
    private fun finishAnimation() {
        animation?.removeAllUpdateListeners(); animation?.cancel(); animation = null; fraction = 1f
    }
    fun clearSelection() { removeCallbacks(hide); selectedHour = null; tooltipBounds = null; parent?.requestDisallowInterceptTouchEvent(false); invalidate() }
    private fun bottom() = height - sp(14f)
    internal fun barBounds(hour: Int): RectF {
        require(hour in 0..23)
        val bar = (width - 23 * dp(2f)) / 24f
        val left = hour * (bar + dp(2f))
        return RectF(left, dp(4f), left + bar, bottom())
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = model ?: return
        if (!current()) { clear(); return }
        if (width <= 0 || bottom() <= dp(4f)) return
        val maximum = data.minutes.maxOrNull()?.takeIf { it > 0.0 } ?: 1.0
        for (hour in 0..23) {
            val track = barBounds(hour)
            val radius = track.width() / 2f
            fill.color = visual.alpha(visual.tertiaryText, .1f)
            canvas.drawRoundRect(track, radius, radius, fill)
            if (data.minutes[hour] > 0.0) {
                val h = max(0f, (bottom() - dp(4f)) * (data.minutes[hour] / maximum).toFloat() * fraction)
                fill.color = if (selectedHour == hour) visual.mix(visual.accent, visual.onAccent, .22f) else visual.accent
                canvas.drawRoundRect(RectF(track.left, bottom() - h, track.right, bottom()), radius, radius, fill)
            }
        }
        axis.color = visual.tertiaryText
        for (hour in intArrayOf(0, 6, 12, 18)) {
            val text = String.format(Locale.US, "%02d", hour)
            val half = axis.measureText(text) / 2f
            canvas.drawText(text, barBounds(hour).centerX().coerceIn(half, max(half, width - half)), height - sp(1f), axis)
        }
        selectedHour?.let { drawTooltip(canvas, data, it) }
    }
    private fun hourLabel(hour: Int) = context.getString(R.string.stats_hour_value_fmt, hour)
    private fun duration(data: StatsHourHabits, hour: Int) = if (data.minutes[hour] <= 0.0) context.getString(R.string.stats_heatmap_no_data_short)
        else StatsTrendText.value(context, StatsTrendMetric.FOCUS, data.minutes[hour])
    private fun drawTooltip(canvas: Canvas, data: StatsHourHabits, hour: Int) {
        val date = hourLabel(hour); val amount = duration(data, hour)
        val horizontal = space(12f); val vertical = space(8f); val gap = dp(4f)
        val first = label.fontMetrics; val second = value.fontMetrics
        val w = (horizontal * 2 + max(label.measureText(date), value.measureText(amount))).coerceAtMost(max(1f, width - space(16f)))
        val h = vertical * 2 + first.descent - first.ascent + second.descent - second.ascent + gap + space(6f)
        val anchor = barBounds(hour).centerX()
        val left = (anchor - w / 2).coerceIn(space(8f), max(space(8f), width - space(8f) - w))
        val top = (anchorY - h - dp(8f)).coerceIn(space(8f), max(space(8f), height - space(8f) - h))
        val bounds = RectF(left, top, left + w, top + h)
        tooltipBounds = bounds; shape.draw(canvas, bounds, anchor)
        label.color = visual.color(UiR.color.statistics_tooltip_text_secondary)
        value.color = visual.color(UiR.color.statistics_tooltip_text_primary)
        val baseline = top + vertical - first.ascent
        val available = max(1f, w - horizontal * 2)
        canvas.drawText(TextUtils.ellipsize(date, label, available, TextUtils.TruncateAt.END).toString(), bounds.centerX(), baseline, label)
        canvas.drawText(TextUtils.ellipsize(amount, value, available, TextUtils.TruncateAt.END).toString(), bounds.centerX(), baseline + first.descent + gap - second.ascent, value)
    }
    private fun select(x: Float, y: Float) {
        if (width <= 0 || y < 0 || y > bottom() || x < 0 || x > width) { clearSelection(); return }
        val stride = (width - 23 * dp(2f)) / 24f + dp(2f)
        val hour = (x / stride).toInt()
        if (hour !in 0..23) { clearSelection(); return }
        selectedHour = hour; anchorY = max(dp(6f), y); invalidate()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!current() || model == null || !interactionAllowed()) { clearSelection(); return false }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { removeCallbacks(hide); parent?.requestDisallowInterceptTouchEvent(true); select(event.x, event.y) }
            MotionEvent.ACTION_MOVE -> select(event.x, event.y)
            MotionEvent.ACTION_UP -> { performClick(); if (selectedHour != null) postDelayed(hide, 2200); parent?.requestDisallowInterceptTouchEvent(false) }
            MotionEvent.ACTION_CANCEL -> { clearSelection(); parent?.requestDisallowInterceptTouchEvent(false) }
        }
        return true
    }
    override fun performClick(): Boolean {
        super.performClick()
        if (current() && interactionAllowed()) model?.let { data -> selectedHour?.let { hour ->
            announceForAccessibility(context.getString(R.string.stats_hour_chart_selected_fmt, hourLabel(hour), duration(data, hour)))
        } }
        return true
    }
    override fun onDetachedFromWindow() { finishAnimation(); clearSelection(); super.onDetachedFromWindow() }
}
