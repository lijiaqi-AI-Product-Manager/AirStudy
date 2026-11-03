package com.projectfocus.feature.stats

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import java.util.Locale

class StatsCareerPanel @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private val visual = StatsVisual(context)
    private val mileage = number(26f, 18).apply { id = R.id.statsDistance }
    private val flights = number(22f, 16)
    private val onTime = number(22f, 16)
    private val streak = number(20f, 14)
    private val best = visual.text(11f, visual.secondaryText, true)
    private val cityCount = number(24f)
    val distanceValue: TextView get() = mileage
    val flightsValue: TextView get() = flights
    val onTimeValue: TextView get() = onTime
    val streakValue: TextView get() = streak
    val bestValue: TextView get() = best
    val cityCountValue: TextView get() = cityCount
    val cityRows = LinearLayout(context).apply { orientation = HORIZONTAL }
    val cityScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFillViewport = true
        addView(cityRows, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    val countryRows = LinearLayout(context).apply { id = R.id.statsCountryList; orientation = VERTICAL }
    val message = visual.text(13f, visual.tertiaryText).apply { id = R.id.statsCountryEmpty; gravity = Gravity.CENTER }
    private val geography = LinearLayout(context).apply { orientation = VERTICAL }
    private val citySection = LinearLayout(context).apply { orientation = VERTICAL }
    private val countrySection = LinearLayout(context).apply { orientation = VERTICAL }
    private val countryHighlight = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private var bound: StatsUiState? = null
    var interactionAllowed: () -> Boolean = { true }
    var onCountryRequested: ((String, StatsUiState) -> Unit)? = null
    var onRetryRequested: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        setPadding(visual.dp(16), visual.dp(16), visual.dp(16), visual.dp(16))
        background = visual.card(18)
        elevation = visual.dp(2).toFloat()
        addView(visual.text(17f, visual.primaryText, true).apply { setText(R.string.stats_career_title) })
        addView(visual.text(12f, visual.tertiaryText).apply { setText(R.string.stats_career_subtitle) }, full(4))
        val distanceCard = column(14, 13, true)
        distanceCard.addView(visual.text(12f, visual.secondaryText, true).apply { setText(R.string.stats_total_mileage_label) })
        distanceCard.addView(mileage, full(7))
        addView(distanceCard, full(13))
        val pair = LinearLayout(context)
        fun metric(value: StatsNumberView, label: Int, accent: Int) = column(12, 11, false, accent).apply {
            addView(value, full())
            addView(visual.text(11f, visual.tertiaryText).apply { setText(label) }, full(5))
        }
        pair.addView(metric(flights, R.string.stats_completed_flights_label, visual.accent), LayoutParams(0, -2, 1f))
        pair.addView(metric(onTime, R.string.stats_ontime_label, visual.mix(visual.accent, visual.secondaryText, .18f)), LayoutParams(0, -2, 1f).apply { leftMargin = visual.dp(8) })
        addView(pair, full(8))
        val current = column(12, 11, false).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val badge = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(visual.alpha(visual.accent, .12f))
                setStroke(visual.dp(1), visual.alpha(visual.accent, .22f))
            }
            addView(ImageView(context).apply { setImageResource(R.drawable.stats_ic_bonfire); setColorFilter(visual.accent) }, FrameLayout.LayoutParams(visual.dp(18), visual.dp(18), Gravity.CENTER))
        }
        current.addView(badge, LayoutParams(visual.dp(36), visual.dp(36)))
        current.addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(visual.text(11f, visual.tertiaryText).apply { setText(R.string.stats_current_streak_title) })
            addView(streak, full(3))
        }, LayoutParams(0, -2, 1f).apply { leftMargin = visual.dp(10) })
        best.setPadding(visual.dp(8), visual.dp(5), visual.dp(8), visual.dp(5))
        best.background = visual.secondary(10)
        current.addView(best)
        addView(current, full(8))
        geography.addView(View(context).apply { setBackgroundColor(visual.alpha(visual.border, .35f)) }, LayoutParams(-1, visual.dp(1)))
        citySection.addView(visual.text(13f, visual.secondaryText, true).apply { setText(R.string.stats_record_city_stats_title) })
        citySection.addView(cityCount, full(4))
        citySection.addView(cityScroll, full(10))
        geography.addView(citySection, full(14))
        countrySection.addView(visual.text(13f, visual.secondaryText, true).apply { setText(R.string.stats_record_top_countries_title) })
        countryHighlight.setPadding(visual.dp(14), visual.dp(13), visual.dp(14), visual.dp(13))
        countryHighlight.background = accentCard(visual.accent, true)
        countrySection.addView(countryHighlight, full(10))
        countrySection.addView(countryRows, full(10))
        geography.addView(countrySection, full(16))
        addView(geography, full(14))
        addView(message, full(14))
        bind(StatsUiState())
    }

    private fun full(top: Int = 0) = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = visual.dp(top) }
    private fun number(size: Float, minimum: Int? = null) = StatsNumberView(context, size).apply {
        typeface = visual.text(size, visual.primaryText, true).typeface
        setSingleLine(true)
        if (minimum != null) TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, minimum, size.toInt(), 1, android.util.TypedValue.COMPLEX_UNIT_SP)
    }
    private fun accentCard(accent: Int, strong: Boolean) = visual.rounded(14,
        intArrayOf(visual.mix(visual.surface, accent, if (strong) .1f else .045f),
            visual.mix(visual.secondarySurface, accent, if (strong) .07f else .025f)),
        visual.alpha(accent, if (strong) .2f else .1f), GradientDrawable.Orientation.LEFT_RIGHT)
    private fun column(horizontal: Int, vertical: Int, strong: Boolean, accent: Int = visual.accent) = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(visual.dp(horizontal), visual.dp(vertical), visual.dp(horizontal), visual.dp(vertical))
        background = accentCard(accent, strong)
    }
    private fun interactive(state: StatsUiState) = bound === state && state.account?.isCurrent() == true && !state.isLoading && interactionAllowed()
    private fun countryName(code: String): String = if (code in Locale.getISOCountries()) Locale("", code).getDisplayCountry(resources.configuration.locales[0]) else code
    private fun flag(width: Int, height: Int) = ImageView(context).apply {
        background = visual.rounded(if (width == 40) 4 else 3, intArrayOf(visual.alpha(visual.border, .4f), visual.alpha(visual.border, .4f)))
        layoutParams = LayoutParams(visual.dp(width), visual.dp(height))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun bind(state: StatsUiState) {
        val old = bound
        bound = state
        val current = state.account?.isCurrent() == true
        val ready = current && !state.isLoading
        val career = state.career.takeIf { ready }
        val placeholder = context.getString(R.string.stats_placeholder)
        fun show(view: StatsNumberView, value: Double?, format: (Double) -> CharSequence) {
            if (value == null) view.showStatic(placeholder)
            else if (old?.account != state.account || old?.career != state.career || old?.flightStreak != state.flightStreak) view.animateTo(value, format)
            else view.showStatic(format(value))
        }
        show(mileage, career?.distanceKm) { context.getString(R.string.stats_km_value_fmt, String.format(Locale.US, "%,d", Math.round(it))) }
        show(flights, career?.completedFlights?.toDouble()) { context.getString(R.string.stats_times_fmt, Math.round(it)) }
        show(onTime, career?.onTimeRate?.times(100)) { context.getString(R.string.stats_ontime_rate_value_fmt, Math.round(it)) }
        val run = state.flightStreak.takeIf { ready }
        show(streak, run?.currentDays?.toDouble()) { context.getString(R.string.stats_streak_days_fmt, Math.round(it)) }
        best.text = run?.let { context.getString(R.string.stats_streak_best_value_fmt, it.bestDays) } ?: placeholder
        best.contentDescription = best.text
        cityRows.removeAllViews(); countryRows.removeAllViews(); countryHighlight.removeAllViews()
        if (old?.career != state.career || old?.account != state.account) cityScroll.scrollTo(0, 0)
        geography.visibility = if (career != null && (career.cities.isNotEmpty() || career.countries.isNotEmpty())) VISIBLE else GONE
        citySection.visibility = if (career?.cities.isNullOrEmpty()) GONE else VISIBLE
        countrySection.visibility = if (career?.countries.isNullOrEmpty()) GONE else VISIBLE
        show(cityCount, career?.cityCount?.toDouble()) { Math.round(it).toString() }
        career?.cities?.take(5)?.forEachIndexed { index, city ->
            val tile = column(12, 11, false)
            tile.addView(number(20f, 12).apply { showStatic(city.arrivals.toString()) }, full())
            tile.addView(visual.text(11f, visual.tertiaryText).apply { text = city.name; setSingleLine(true); ellipsize = TextUtils.TruncateAt.END }, full(5))
            tile.contentDescription = city.name + " " + context.getString(R.string.stats_times_fmt, city.arrivals)
            cityRows.addView(tile, LayoutParams(0, -2, 1f).apply { if (index > 0) leftMargin = visual.dp(8) })
        }
        career?.countries?.firstOrNull()?.let { row ->
            countryHighlight.addView(flag(40, 28))
            countryHighlight.addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(visual.text(11f, visual.tertiaryText).apply { setText(R.string.stats_top_country_most_visited_label) })
                addView(visual.text(15f, visual.primaryText, true).apply { text = countryName(row.country); setSingleLine(true); ellipsize = TextUtils.TruncateAt.END }, full(2))
            }, LayoutParams(0, -2, 1f).apply { leftMargin = visual.dp(12) })
            countryHighlight.addView(number(20f).apply { showStatic(if (row.totalAirports > 0) "${row.visitedAirports}/${row.totalAirports}" else row.visitedAirports.toString()) })
        }
        val maximum = career?.countries?.maxOfOrNull { it.visitedAirports } ?: 0
        career?.countries?.take(5)?.forEachIndexed { index, row ->
            val container = LinearLayout(context).apply { orientation = VERTICAL; isFocusable = true; tag = row.country }
            val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            header.addView(flag(22, 15))
            header.addView(visual.text(13f).apply { text = countryName(row.country); setSingleLine(true); ellipsize = TextUtils.TruncateAt.END }, LayoutParams(0, -2, 1f).apply { leftMargin = visual.dp(8) })
            header.addView(visual.text(13f, visual.secondaryText, true).apply { text = row.visitedAirports.toString() })
            container.addView(header, full())
            val fraction = if (maximum > 0) maxOf(.04f, row.visitedAirports.toFloat() / maximum) else .04f
            val rail = LinearLayout(context)
            rail.addView(View(context).apply { background = visual.rounded(3, intArrayOf(visual.mix(visual.surface, visual.accent, .55f), visual.mix(visual.surface, visual.accent, .55f))) }, LayoutParams(0, visual.dp(6), fraction))
            if (fraction < 1f) rail.addView(View(context), LayoutParams(0, visual.dp(6), 1f - fraction))
            container.addView(rail, full(6))
            container.setOnClickListener { if (interactive(state)) onCountryRequested?.invoke(row.country, state) }
            countryRows.addView(container, full(if (index == 0) 0 else 10))
        }
        message.visibility = if (career == null) VISIBLE else GONE
        message.setText(if (state.readFailed && current) R.string.stats_read_failed else R.string.stats_placeholder)
        message.setOnClickListener {
            if (state.readFailed && bound === state && state.account?.isCurrent() == true && interactionAllowed()) onRetryRequested?.invoke()
        }
    }

    private fun clearObsolete() {
        val old = bound ?: return
        if (old.account != null && !old.account.isCurrent()) {
            bound = null
            listOf(mileage, flights, onTime, streak, cityCount).forEach { it.showStatic(context.getString(R.string.stats_placeholder)) }
            best.text = context.getString(R.string.stats_placeholder)
            best.contentDescription = best.text
            cityRows.removeAllViews(); countryRows.removeAllViews(); countryHighlight.removeAllViews()
            geography.visibility = GONE
            message.visibility = GONE
        }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val count = cityRows.childCount
        if (count > 0) {
            val available = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
            val gaps = visual.dp(8) * (count - 1)
            val scale = resources.configuration.fontScale.coerceAtLeast(1f)
            val scroll = (available - gaps) / count < visual.dp(56f * scale)
            val cell = visual.dp(80f * scale)
            val rowWidth = if (scroll) cell * count + gaps else available
            cityRows.layoutParams.width = rowWidth
            for (i in 0 until count) {
                (cityRows.getChildAt(i).layoutParams as LayoutParams).apply {
                    width = if (scroll) cell else 0
                    weight = if (scroll) 0f else 1f
                }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    override fun dispatchDraw(canvas: Canvas) { clearObsolete(); super.dispatchDraw(canvas) }
    fun release() {
        bind(StatsUiState())
        bound = null
        onCountryRequested = null; onRetryRequested = null; interactionAllowed = { false }
    }
}
