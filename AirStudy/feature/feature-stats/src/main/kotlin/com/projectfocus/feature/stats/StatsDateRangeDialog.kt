package com.projectfocus.feature.stats

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.util.TypedValue
import android.view.Window
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import androidx.core.widget.TextViewCompat

class StatsDateRangeDialog(
    context: Context,
    range: StatsDateRange,
    private val zone: ZoneId,
    maximum: LocalDate,
    private val canConfirm: () -> Boolean = { true },
    private val onSelected: (LocalDate, LocalDate) -> Unit,
) : Dialog(context) {
    private val v = StatsVisual(context)
    var selection: StatsCalendarSelection = StatsCalendarSelection.open(range, maximum)
        private set
    private lateinit var body: LinearLayout
    private lateinit var firstCard: LinearLayout
    private lateinit var lastCard: LinearLayout
    private lateinit var firstValue: TextView
    private lateinit var lastValue: TextView
    private lateinit var monthLabel: TextView
    private lateinit var nextMonth: ImageButton
    private lateinit var grid: GridLayout
    private lateinit var dayHint: TextView
    private val motion = PathInterpolator(0.2f, 0f, 0f, 1f)
    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(v.dp(18), v.dp(16), v.dp(18), v.dp(14))
            background = v.rounded(16, intArrayOf(v.mix(v.surface, Color.WHITE, 0.04f), v.mix(v.surface, v.secondarySurface, 0.08f)), v.alpha(v.border, 0.26f))
            elevation = v.dp(12).toFloat()
        }
        body.addView(v.text(19f, v.primaryText, true).apply { setText(R.string.stats_date_title) })
        val dates = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        firstCard = dateCard(true)
        lastCard = dateCard(false)
        dates.addView(firstCard, LinearLayout.LayoutParams(0, v.dp(58), 1f))
        dates.addView(v.text(14f, v.tertiaryText, true).apply { setText(R.string.stats_date_separator); gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(v.dp(24), v.dp(58)))
        dates.addView(lastCard, LinearLayout.LayoutParams(0, v.dp(58), 1f))
        body.addView(dates, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, v.dp(58)).apply { topMargin = v.dp(12) })
        val navigation = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val previous = arrow(R.drawable.stats_ic_previous, R.string.stats_previous_period, -1)
        nextMonth = arrow(R.drawable.stats_ic_next, R.string.stats_next_period, 1)
        monthLabel = v.text(16f, v.primaryText, true).apply { gravity = Gravity.CENTER }
        navigation.addView(previous, LinearLayout.LayoutParams(v.dp(40), v.dp(40)))
        navigation.addView(monthLabel, LinearLayout.LayoutParams(0, v.dp(40), 1f))
        navigation.addView(nextMonth, LinearLayout.LayoutParams(v.dp(40), v.dp(40)))
        body.addView(navigation, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, v.dp(40)).apply { topMargin = v.dp(10) })
        grid = GridLayout(context).apply { columnCount = 7; rowCount = 7 }
        body.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, v.dp(272)).apply { topMargin = v.dp(2) })
        dayHint = v.text(12f, v.tertiaryText).apply { gravity = Gravity.CENTER }
        body.addView(dayHint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, v.dp(26)))
        val actions = LinearLayout(context).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        val cancel = v.text(14f, v.secondaryText, true).apply {
            setText(R.string.stats_date_cancel); gravity = Gravity.CENTER
            background = v.secondary(8); tag = "stats-date-cancel"
            isFocusable = true
            setOnClickListener { dismiss() }
        }
        val confirm = v.text(14f, v.onAccent, true).apply {
            setText(R.string.stats_date_confirm); gravity = Gravity.CENTER
            background = v.confirmButton(); elevation = v.dp(2).toFloat(); tag = "stats-date-confirm"
            isFocusable = true
            setOnClickListener {
                if (!delivered && canConfirm()) {
                    delivered = true
                    onSelected(selection.first, selection.last)
                }
                dismiss()
            }
        }
        actions.addView(cancel, LinearLayout.LayoutParams(v.dp(86), v.dp(42)))
        actions.addView(confirm, LinearLayout.LayoutParams(v.dp(108), v.dp(42)).apply { leftMargin = v.dp(8) })
        body.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, v.dp(42)).apply { topMargin = v.dp(4) })
        val maximumHeight = minOf(v.dp(540), maxOf(v.dp(240), context.resources.displayMetrics.heightPixels - v.dp(48)))
        val scroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val permitted = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) maximumHeight
                    else minOf(maximumHeight, MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(permitted, MeasureSpec.AT_MOST))
            }
        }.apply { isFillViewport = false; overScrollMode = View.OVER_SCROLL_NEVER }
        scroll.addView(body, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)
        setCanceledOnTouchOutside(true)
        renderCards()
        renderMonth(0)
    }

    private fun dateCard(first: Boolean): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        tag = if (first) "stats-date-first" else "stats-date-last"
        isFocusable = true
        setPadding(v.dp(12), v.dp(6), v.dp(10), v.dp(6))
        addView(v.text(11f, v.tertiaryText, true).apply { setText(if (first) R.string.stats_date_first else R.string.stats_date_last) })
        val value = v.text(14f, v.primaryText, true).apply {
            maxLines = 1
            setHorizontallyScrolling(false)
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this,10,14,1,TypedValue.COMPLEX_UNIT_SP)
        }
        addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = v.dp(3) })
        if (first) firstValue = value else lastValue = value
        setOnClickListener { if (active()) { selection = selection.edit(first); renderCards(); renderMonth(0) } }
    }

    private fun arrow(icon: Int, label: Int, direction: Int) = ImageButton(context).apply {
        setImageResource(icon); setColorFilter(v.secondaryText); setBackgroundColor(Color.TRANSPARENT)
        setPadding(v.dp(10), v.dp(10), v.dp(10), v.dp(10)); contentDescription = context.getString(label)
        tag = if (direction < 0) "stats-date-previous-month" else "stats-date-next-month"
        setOnClickListener {
            if (active() && (direction < 0 || selection.canNextMonth)) {
                selection = selection.moveMonth(direction)
                renderMonth(direction)
            }
        }
    }

    private fun active(): Boolean = canConfirm().also { if (!it) dismiss() }
    private fun date(value: LocalDate) = Date(value.atStartOfDay(zone).toInstant().toEpochMilli())
    private fun formatter(style: Int) = DateFormat.getDateInstance(style, Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone(zone) }

    private fun renderCards() {
        firstValue.text = formatter(DateFormat.MEDIUM).format(date(selection.first))
        lastValue.text = formatter(DateFormat.MEDIUM).format(date(selection.last))
        fun style(card: LinearLayout, selected: Boolean) {
            card.isSelected = selected
            card.background = if (selected) v.rounded(8, intArrayOf(v.selectedSurface, v.selectedSurface), v.alpha(v.accent, 0.62f)) else v.secondary(8)
            if (ValueAnimator.areAnimatorsEnabled()) {
                card.animate().cancel(); card.scaleX = 0.985f; card.scaleY = 0.985f
                card.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(motion).start()
            }
        }
        style(firstCard, selection.editingFirst)
        style(lastCard, !selection.editingFirst)
        dayHint.text = context.getString(R.string.stats_date_days, selection.days)
    }

    private fun renderMonth(direction: Int) {
        grid.animate().cancel()
        grid.removeAllViews()
        monthLabel.text = SimpleDateFormat(context.getString(R.string.stats_date_month_pattern), Locale.getDefault())
            .apply { timeZone = TimeZone.getTimeZone(zone) }.format(date(selection.month.atDay(1)))
        nextMonth.isEnabled = selection.canNextMonth
        nextMonth.alpha = if (selection.canNextMonth) 1f else 0.28f
        val firstDay = Calendar.getInstance(TimeZone.getTimeZone(zone), Locale.getDefault()).firstDayOfWeek
        val weekdays = DateFormatSymbols(Locale.getDefault()).shortWeekdays
        for (column in 0..6) grid.addView(v.text(11f, v.tertiaryText, true).apply {
            text = weekdays[((firstDay - 1 + column) % 7) + 1]; gravity = Gravity.CENTER
        }, cell(0, column, 30))
        val monthFirst = selection.month.atDay(1)
        val calendarFirst = monthFirst.dayOfWeek.value % 7 + 1
        val offset = (calendarFirst - firstDay + 7) % 7
        repeat(42) { index ->
            val number = index - offset + 1
            val frame = FrameLayout(context)
            if (number in 1..selection.month.lengthOfMonth()) {
                val value = selection.month.atDay(number)
                val enabled = !value.isAfter(selection.maximum)
                val endpoint = value == selection.first || value == selection.last
                if (value >= selection.first && value <= selection.last && selection.first != selection.last) {
                    val band = LinearLayout(context).apply {
                        val tint = v.selectedSurface
                        addView(View(context).apply { if (value != selection.first) setBackgroundColor(tint) }, LinearLayout.LayoutParams(0, v.dp(32), 1f))
                        addView(View(context).apply { if (value != selection.last) setBackgroundColor(tint) }, LinearLayout.LayoutParams(0, v.dp(32), 1f))
                    }
                    frame.addView(band, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, v.dp(32), Gravity.CENTER))
                }
                val label = v.text(13f, if (enabled) v.primaryText else v.alpha(v.tertiaryText, 0.38f), endpoint).apply {
                    text = number.toString(); gravity = Gravity.CENTER
                    if (endpoint) { background = v.dayEndpoint(); setTextColor(v.onAccent); elevation = v.dp(2).toFloat() }
                    else if (value == selection.maximum) background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(v.dp(1), v.alpha(v.accent, 0.52f))
                    }
                }
                frame.addView(label, FrameLayout.LayoutParams(v.dp(if (endpoint) 42 else 34), v.dp(if (endpoint) 32 else 34), Gravity.CENTER))
                frame.tag = "stats-date:$value"
                frame.contentDescription = formatter(DateFormat.FULL).format(date(value))
                frame.isEnabled = enabled; frame.isClickable = enabled; frame.isFocusable = enabled
                frame.isSelected = endpoint
                if (enabled) frame.setOnClickListener {
                    if (active()) { selection = selection.select(value); renderCards(); renderMonth(0) }
                }
            }
            grid.addView(frame, cell(index / 7 + 1, index % 7, 40))
        }
        if (direction != 0 && ValueAnimator.areAnimatorsEnabled()) {
            grid.alpha = 0.38f; grid.translationX = v.dp(if (direction > 0) 14 else -14).toFloat()
            grid.animate().alpha(1f).translationX(0f).setDuration(190).setInterpolator(motion).start()
        } else { grid.alpha = 1f; grid.translationX = 0f }
    }

    private fun cell(row: Int, column: Int, height: Int) = GridLayout.LayoutParams(GridLayout.spec(row), GridLayout.spec(column, 1f)).apply {
        width = 0; this.height = v.dp(height); setGravity(Gravity.FILL)
    }

    override fun onStart() {
        super.onStart()
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            attributes = attributes.apply {
                width = minOf(maxOf(1, context.resources.displayMetrics.widthPixels - v.dp(32)), v.dp(420))
                height = ViewGroup.LayoutParams.WRAP_CONTENT; gravity = Gravity.CENTER; dimAmount = 0.34f
            }
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        if (ValueAnimator.areAnimatorsEnabled()) {
            body.alpha = 0f; body.scaleX = 0.965f; body.scaleY = 0.965f
            body.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(motion).start()
        }
    }

    override fun onStop() {
        if (::body.isInitialized) body.animate().cancel()
        if (::grid.isInitialized) grid.animate().cancel()
        if (::firstCard.isInitialized) firstCard.animate().cancel()
        if (::lastCard.isInitialized) lastCard.animate().cancel()
        super.onStop()
    }
}
