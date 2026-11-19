package com.projectfocus.feature.stats

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import androidx.core.content.res.ResourcesCompat
import com.projectfocus.core.ui.R as UiR
import com.projectfocus.service.focus.history.HistoryAccount
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Formatter
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.roundToInt

class StatsYearHeatmapView(context: Context) : View(context) {
    private val visual = StatsVisual(context)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.5f) }
    private val month = text(10f, false)
    private val dateText = text(12f, true).apply { textAlign = Paint.Align.CENTER }
    private val valueText = text(12f, true).apply { textAlign = Paint.Align.CENTER }
    private val shape = StatsTooltipShape(visual)
    private var data: StatsAnnualHeatmap? = null
    private var owner: HistoryAccount? = null
    private var months = emptyList<String>()
    private val hide = Runnable { clearSelection() }
    var interactionAllowed: () -> Boolean = { true }
    var currentWallMs: () -> Long = System::currentTimeMillis
    var selectedDate: LocalDate? = null; private set
    internal var tooltipBounds: RectF? = null; private set

    init {
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.stats_heatmap_accessibility_hint)
        minimumWidth = dp(820f).toInt()
    }

    private fun dp(value: Float) = (value * resources.displayMetrics.density).toInt().toFloat()
    private fun space(value: Float) = value * resources.displayMetrics.density
    private fun text(size: Float, medium: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, resources.displayMetrics)
        typeface = ResourcesCompat.getFont(context, if (medium) UiR.font.harmonyos_sans_sc_medium else UiR.font.harmonyos_sans_sc_light)
    }

    internal fun bind(model: StatsAnnualHeatmap, account: HistoryAccount, resetSelection: Boolean) {
        if (resetSelection || data != model || owner != account) clearSelection()
        data = model
        owner = account
        val formatter = SimpleDateFormat(context.getString(R.string.stats_heatmap_month_pattern), resources.configuration.locales[0]).apply {
            timeZone = TimeZone.getTimeZone(model.zone)
        }
        months = (1..12).map { formatter.format(java.util.Date(LocalDate.of(model.year, it, 1).atStartOfDay(model.zone).toInstant().toEpochMilli())) }
        invalidate()
    }

    internal fun clear() { clearSelection(); data = null; owner = null; months = emptyList(); invalidate() }
    fun clearSelection() { removeCallbacks(hide); selectedDate = null; tooltipBounds = null; invalidate() }
    private fun current() = owner?.isCurrent() == true
    private fun cellSize() = max(dp(9f), (width - dp(4f) * 2f - 53f * dp(3f)) / 54f)
    internal fun cellBounds(date: LocalDate): RectF? {
        val model = data ?: return null
        val index = model.grid.indexOf(date)
        if (index < 0) return null
        return bounds(index)
    }
    private fun bounds(index: Int): RectF {
        val size = cellSize()
        val left = dp(4f) + (index / 7) * (size + dp(3f))
        val top = dp(24f) + (index % 7) * (size + dp(3f))
        return RectF(left, top, left + size, top + size)
    }
    internal fun scrollFor(date: LocalDate, viewport: Int): Int {
        val model = data ?: return 0
        val index = model.scrollIndex(date)
        val center = dp(4f) + (index / 7) * (cellSize() + dp(3f)) + cellSize() / 2f
        return (center - viewport / 2f).roundToInt().coerceIn(0, max(0, width - viewport))
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(max(suggestedMinimumWidth, MeasureSpec.getSize(widthMeasureSpec)), resolveSize(dp(136f).toInt(), heightMeasureSpec))
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val model = data ?: return
        if (!current()) { clear(); return }
        if (model.year <= 0) return
        val empty = visual.alpha(visual.tertiaryText, .12f)
        val low = visual.mix(empty, visual.accent, .24f)
        month.color = visual.tertiaryText
        stroke.color = visual.onAccent
        val now = currentWallMs()
        var previousMonth = -1
        model.grid.forEachIndexed { index, date ->
            if (!model.contains(date) || date.atStartOfDay(model.zone).toInstant().toEpochMilli() > now) return@forEachIndexed
            val rect = bounds(index)
            val minutes = model.minutes(date)
            fill.color = if (minutes <= 0.0) empty else visual.mix(low, visual.accent, (minutes / model.maximumMinutes).coerceAtMost(1.0).toFloat())
            canvas.drawRoundRect(rect, dp(2f), dp(2f), fill)
            if (date == selectedDate) canvas.drawRoundRect(rect, dp(2f), dp(2f), stroke)
            if (index % 7 == 0 && date.monthValue != previousMonth) {
                canvas.drawText(months[date.monthValue - 1], rect.left, dp(12f), month)
                previousMonth = date.monthValue
            }
        }
        selectedDate?.let { date -> cellBounds(date)?.let { drawTooltip(canvas, model, date, it) } }
    }
    internal fun dateLabel(date: LocalDate, flags: Int): String {
        val model = data ?: return ""
        val wall = date.atStartOfDay(model.zone).toInstant().toEpochMilli()
        return Formatter(StringBuilder(), resources.configuration.locales[0]).use {
            DateUtils.formatDateRange(context, it, wall, wall, flags, model.zone.id).toString()
        }
    }
    private fun duration(date: LocalDate): String = data?.minutes(date)?.takeIf { it > 0.0 }?.let {
        StatsTrendText.value(context, StatsTrendMetric.FOCUS, it)
    } ?: context.getString(R.string.stats_heatmap_no_data_short)

    private fun drawTooltip(canvas: Canvas, model: StatsAnnualHeatmap, date: LocalDate, cell: RectF) {
        if (!model.contains(date) || date.atStartOfDay(model.zone).toInstant().toEpochMilli() > currentWallMs()) { clearSelection(); return }
        val label = dateLabel(date, 65556)
        val value = duration(date)
        val pad = space(12f)
        val vertical = space(8f)
        val gap = dp(4f)
        val first = dateText.fontMetrics
        val second = valueText.fontMetrics
        val scroll = parent as? HorizontalScrollView
        val visibleLeft = scroll?.scrollX?.toFloat() ?: 0f
        val visibleWidth = scroll?.width?.toFloat() ?: width.toFloat()
        val w = (pad * 2 + max(dateText.measureText(label), valueText.measureText(value))).coerceAtMost(max(1f, visibleWidth - space(16f)))
        val h = vertical * 2 + first.descent - first.ascent + gap + second.descent - second.ascent + space(6f)
        val left = (cell.centerX() - w / 2).coerceIn(visibleLeft + space(8f), max(visibleLeft + space(8f), visibleLeft + visibleWidth - space(8f) - w))
        val top = max(space(8f), cell.centerY() - h - dp(8f))
        val rect = RectF(left, top, left + w, top + h)
        tooltipBounds = rect
        shape.draw(canvas, rect, cell.centerX())
        dateText.color = visual.color(UiR.color.statistics_tooltip_text_secondary)
        valueText.color = visual.color(UiR.color.statistics_tooltip_text_primary)
        val dateBaseline = top + vertical - first.ascent
        val available = max(1f, w - pad * 2)
        canvas.drawText(TextUtils.ellipsize(label, dateText, available, TextUtils.TruncateAt.END).toString(), rect.centerX(), dateBaseline, dateText)
        canvas.drawText(TextUtils.ellipsize(value, valueText, available, TextUtils.TruncateAt.END).toString(), rect.centerX(), dateBaseline + first.descent + gap - second.ascent, valueText)
    }
    private fun selectAt(x: Float, y: Float) {
        val model = data ?: return
        val step = cellSize() + dp(3f)
        val column = ((x - dp(4f)) / step).toInt()
        val row = ((y - dp(24f)) / step).toInt()
        if (column !in 0..53 || row !in 0..6 || model.year <= 0) { clearSelection(); return }
        val date = model.grid[column * 7 + row]
        val rect = cellBounds(date) ?: return
        if (x < rect.left || x > rect.right || y < rect.top || y > rect.bottom || !model.contains(date) ||
            date.atStartOfDay(model.zone).toInstant().toEpochMilli() > currentWallMs()) { clearSelection(); return }
        selectedDate = date
        invalidate()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!current() || data == null || !interactionAllowed()) { clearSelection(); return false }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { removeCallbacks(hide); selectAt(event.x, event.y) }
            MotionEvent.ACTION_MOVE -> selectAt(event.x, event.y)
            MotionEvent.ACTION_UP -> { performClick(); if (selectedDate != null) postDelayed(hide, 2200) }
            MotionEvent.ACTION_CANCEL -> clearSelection()
        }
        return true
    }
    override fun performClick(): Boolean {
        super.performClick()
        if (current() && interactionAllowed()) selectedDate?.let {
            announceForAccessibility(context.getString(R.string.stats_heatmap_selected_fmt, dateLabel(it, 20), duration(it)))
        }
        return true
    }
    override fun onDetachedFromWindow() { clearSelection(); super.onDetachedFromWindow() }
}
