package com.projectfocus.app.freeflight

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.projectfocus.app.R
import com.projectfocus.core.ui.sky.SkyPressFeedback
import com.projectfocus.core.ui.sky.SkySurfaces
import com.projectfocus.feature.airport.ui.AirportSkyStateView
import com.projectfocus.core.ui.R as UiR
import java.util.Locale


class FreeFlightLandingSurface(
    context: Context,
    feedback: FreeFlightRulerFeedback,
    private val night: Boolean,
    private val cardWidthStyle: String,
    private val arrivalMarkStyle: String,
    onRange: (Int) -> Unit,
    onSelect: (FreeFlightLandingOption) -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
) : FrameLayout(context) {
    private val ambience = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    })
    val ruler = FreeFlightLandingRulerView(context, feedback, night)
    val cards = RecyclerView(context)
    val confirm = MaterialButton(context)
    private val status = TextView(context)
    private val querySketch = AirportSkyStateView(ambience).apply {
        tag = "landing-query-sketch"; visibility = GONE
    }
    private val panel = LinearLayout(context)
    private val cardFrame = FrameLayout(context)
    private val adapter = CardsAdapter(onSelect)
    private var options: List<FreeFlightLandingOption> = emptyList()
    private var selectedCode: String? = null
    private var queryGeneration = 0L
    private val dp: (Int) -> Int = { (it * resources.displayMetrics.density).toInt() }

    init {
        clipChildren = false; clipToPadding = false
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            applySafeInsets(insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()))
            insets
        }
        panel.apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
            setPadding(0, dp(16), 0, dp(28)); elevation = dp(10).toFloat()
            background = SkySurfaces.shape(ambience, radius = 30f, solid = true)
        }
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        ruler.id = R.id.pf_ff_land_ruler
        panel.addView(ruler, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(93)))
        ruler.onTimeChanged = onRange
        panel.addView(cardFrame, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(120)).apply { topMargin = dp(8) })
        cards.id = R.id.pf_ff_land_cards
        cards.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        cards.adapter = adapter; cards.clipToPadding = false
        cardFrame.addView(cards, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        status.apply {
            id = R.id.pf_ff_land_query_status; gravity = Gravity.CENTER; textSize = 14f
            minimumHeight = dp(48); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(dp(16), 0, dp(16), 0)
            setTextColor(ambience.getColor(UiR.color.sky_ink_secondary)); setOnClickListener { if (status.isClickable) onRetry() }
        }
        cardFrame.addView(querySketch, LayoutParams(dp(96), dp(64), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        cardFrame.addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        confirm.apply {
            id = R.id.pf_ff_land_confirm; textSize = 16f; isAllCaps = false; includeFontPadding = false
            typeface = androidx.core.content.res.ResourcesCompat.getFont(context, com.projectfocus.core.ui.R.font.harmonyos_sans_sc_medium)
            setTextColor(Color.WHITE); backgroundTintList = null
            cornerRadius = dp(28); insetTop = 0; insetBottom = 0
            minimumHeight = dp(56); maxLines = 2
            setPadding(dp(20), dp(12), dp(20), dp(12)); setOnClickListener { if (confirm.isEnabled) onConfirm() }
            SkySurfaces.action(this); SkyPressFeedback.install(this)
        }
        panel.addView(confirm, LinearLayout.LayoutParams(dp(300), LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8); bottomMargin = dp(8); marginStart = dp(16); marginEnd = dp(16)
        })
        updateConfirm(false)
    }

    fun render(minutes: Int, selected: String?, busy: Boolean) {
        if (ruler.currentTime() != minutes) ruler.setCurrentTime(minutes)
        if (selectedCode != selected) { selectedCode = selected; adapter.notifyDataSetChanged() }
        ruler.isEnabled = !busy; cards.isEnabled = !busy
        updateConfirm(!busy && selected != null && options.any { it.airport.code == selected }, busy)
    }

    fun loading() {
        queryGeneration++
        options = emptyList(); selectedCode = null; adapter.notifyDataSetChanged()
        cards.animate().cancel(); cards.visibility = GONE; cards.alpha = 1f; cards.translationX = 0f
        showQuery(loading = true, message = R.string.pf_ff_land_loading, retry = false)
        updateConfirm(false)
    }
    fun failed() {
        queryGeneration++
        cards.visibility = GONE
        showQuery(loading = false, message = R.string.pf_ff_land_retry, retry = true)
        updateConfirm(false)
    }
    fun showOptions(values: List<FreeFlightLandingOption>) {
        val generation = ++queryGeneration
        options = values.toList(); adapter.notifyDataSetChanged()
        querySketch.setLoading(false); querySketch.visibility = GONE
        status.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        status.visibility = if (values.isEmpty()) View.VISIBLE else View.GONE
        status.setText(R.string.pf_ff_land_empty); status.isClickable = false; status.background = null
        status.setTextColor(ambience.getColor(UiR.color.sky_ink_secondary))
        cards.visibility = if (values.isEmpty()) GONE else VISIBLE
        if (values.isNotEmpty()) cards.post { centerFirstCard(0, generation) }
    }

    private fun showQuery(loading: Boolean, message: Int, retry: Boolean) {
        querySketch.visibility = if (loading) VISIBLE else GONE
        querySketch.setLoading(loading)
        status.visibility = VISIBLE; status.setText(message); status.isClickable = retry
        status.setTextColor(ambience.getColor(if (retry) UiR.color.sky_primary else UiR.color.sky_ink_secondary))
        status.background = if (retry) SkySurfaces.shape(ambience, radius = 18f, solid = true) else null
        status.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT,
            if (loading) dp(48) else LayoutParams.MATCH_PARENT,
            Gravity.CENTER_HORIZONTAL or if (loading) Gravity.BOTTOM else Gravity.CENTER).apply {
            if (loading) bottomMargin = dp(4)
        }
    }

    private fun centerFirstCard(attempt: Int, generation: Long) {
        if (generation != queryGeneration) return
        if (!isAttachedToWindow || adapter.itemCount == 0) return
        val first = cards.findViewHolderForAdapterPosition(0)?.itemView
        if (cards.width <= 0 || first == null || first.width <= 0) {
            if (attempt < 3) cards.post { centerFirstCard(attempt + 1, generation) }
            return
        }
        val padding = ((cards.width - first.width) / 2).coerceAtLeast(0)
        cards.setPaddingRelative(padding, 0, padding, 0); cards.scrollToPosition(0)
        cards.animate().cancel()
        if (ValueAnimator.areAnimatorsEnabled() && isShown && windowVisibility == VISIBLE) {
            cards.alpha = 0f; cards.translationX = dp(24).toFloat()
            cards.animate().alpha(1f).translationX(0f).setDuration(300)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        } else { cards.alpha = 1f; cards.translationX = 0f }
        // e4.e(0) centres/reveals the first card; it DOES NOT select an airport.
    }

    private fun updateConfirm(enabled: Boolean, busy: Boolean = false) {
        confirm.setText(when {
            busy -> R.string.pf_ff_land_confirming
            enabled -> R.string.pf_ff_land_confirm_landing
            else -> R.string.pf_ff_land_please_select_landing_airport
        })
        confirm.isEnabled = enabled; confirm.alpha = if (enabled) 1f else .5f
    }

    private inner class CardHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name = view.findViewById<TextView>(R.id.pf_ff_card_tv_airport_name)
        val time = view.findViewById<TextView>(R.id.pf_ff_card_tv_airport_city)
        val code = view.findViewById<TextView>(com.projectfocus.feature.airport.R.id.pf_airport_airport_code)
        var option: FreeFlightLandingOption? = null
        var selected = false
        private val material = LandingAirportCardDrawable(resources.displayMetrics.density,
            ambience.getColor(UiR.color.sky_card_solid), ambience.getColor(UiR.color.sky_canvas),
            ambience.getColor(UiR.color.sky_divider), ambience.getColor(UiR.color.sky_primary), night)
        private var backgroundAnimation: ValueAnimator? = null
        init {
            view.stateListAnimator = null; view.alpha = 1f
            view.background = material; view.clipToOutline = true
            SkyPressFeedback.install(view)
            view.setOnClickListener {
                if (cards.isEnabled && cards.isShown)
                    option?.takeIf { chosen -> options.any { it == chosen } }?.let(adapter.onSelect)
            }
        }
        fun stop() {
            val running = backgroundAnimation; backgroundAnimation = null; running?.cancel()
        }
        fun releasePresentation() {
            stop();itemView.animate().cancel()
            itemView.scaleX = 1f;itemView.scaleY = 1f;itemView.alpha = 1f
        }
        fun bind(value: FreeFlightLandingOption) {
            val same = option?.airport?.code == value.airport.code
            val from = material.selectionFraction
            stop(); option = value; selected = value.airport.code == selectedCode
            code.text = value.airport.code.trim().uppercase(Locale.ENGLISH)
            name.text = value.airport.displayName
            time.text = if (value.minutes < 60) "${value.minutes}m" else "${value.minutes / 60}h${value.minutes % 60}m"
            name.setTextColor(ambience.getColor(UiR.color.sky_ink))
            time.setTextColor(ambience.getColor(UiR.color.sky_ink_secondary))
            val target = if (selected) 1f else 0f
            if (same && from != target && itemView.isAttachedToWindow && ValueAnimator.areAnimatorsEnabled()) {
                val animation = ValueAnimator.ofFloat(from, target)
                backgroundAnimation = animation; animation.duration = 200L
                animation.addUpdateListener {
                    if (backgroundAnimation === it && option == value)
                        material.selectionFraction = it.animatedValue as Float
                }
                animation.start()
            } else material.selectionFraction = target
            itemView.elevation = dp(if (selected) 4 else 2).toFloat()
            itemView.isSelected = selected
            itemView.contentDescription = "${value.airport.code}, ${value.airport.displayName}, ${time.text}"
        }
    }

    private inner class CardsAdapter(val onSelect: (FreeFlightLandingOption) -> Unit) : RecyclerView.Adapter<CardHolder>() {
        override fun getItemCount() = options.size
        override fun getItemViewType(position: Int): Int = if (cardWidthStyle != "vertical") 0 else
            if (resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE && arrivalMarkStyle == "none") 1 else 2
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder {
            val layout = when (viewType) { 1 -> R.layout.pf_ff_landing_card_mobile_vertical; 2 -> R.layout.pf_ff_landing_card_mobile_horizon; else -> R.layout.pf_ff_landing_card }
            val view = LayoutInflater.from(context).inflate(layout, parent, false)
            if (viewType != 0) view.layoutParams = RecyclerView.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) }
            return CardHolder(view)
        }
        override fun onBindViewHolder(holder: CardHolder, position: Int) = holder.bind(options[position])
        override fun onViewRecycled(holder: CardHolder) { holder.releasePresentation(); holder.option = null }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.getRootWindowInsets(this)?.let {
            applySafeInsets(it.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()))
        }
        ViewCompat.requestApplyInsets(this)
    }

    private fun applySafeInsets(insets: Insets) {
        if (paddingLeft != insets.left || paddingTop != insets.top || paddingRight != insets.right || paddingBottom != insets.bottom)
            setPadding(insets.left, insets.top, insets.right, insets.bottom)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (View.MeasureSpec.getMode(widthMeasureSpec) != View.MeasureSpec.UNSPECIFIED) {
            val width = (View.MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight - dp(32)).coerceAtLeast(dp(48))
            val desired = minOf(dp(300), width)
            if (confirm.layoutParams.width != desired) confirm.layoutParams = confirm.layoutParams.apply { this.width = desired }
        }
        val boundedHeight = View.MeasureSpec.getMode(heightMeasureSpec) != View.MeasureSpec.UNSPECIFIED
        val availableHeight = View.MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom
        val compact = boundedHeight && availableHeight < dp(420)
        val top = dp(if (compact) 12 else 16)
        val bottom = dp(if (compact) 12 else 28)
        if (panel.paddingTop != top || panel.paddingBottom != bottom) panel.setPadding(0, top, 0, bottom)
        val rulerHeight = dp(if (compact) 74 else 93)
        val cardsHeight = dp(if (compact) 96 else 120)
        if (ruler.layoutParams.height != rulerHeight)
            ruler.layoutParams = ruler.layoutParams.apply { height = rulerHeight }
        if (cardFrame.layoutParams.height != cardsHeight)
            cardFrame.layoutParams = cardFrame.layoutParams.apply { height = cardsHeight }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onDetachedFromWindow() {
        queryGeneration++
        cards.animate().cancel()
        for (i in 0 until cards.childCount) (cards.getChildViewHolder(cards.getChildAt(i)) as? CardHolder)?.releasePresentation()
        super.onDetachedFromWindow()
    }
}
