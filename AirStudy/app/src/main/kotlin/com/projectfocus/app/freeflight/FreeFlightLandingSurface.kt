package com.projectfocus.app.freeflight

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.projectfocus.app.R
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
    val ruler = FreeFlightLandingRulerView(context, feedback, night)
    val cards = RecyclerView(context)
    val confirm = MaterialButton(context)
    private val status = TextView(context)
    private val adapter = CardsAdapter(onSelect)
    private var options: List<FreeFlightLandingOption> = emptyList()
    private var selectedCode: String? = null
    private val dp: (Int) -> Int = { (it * resources.displayMetrics.density).toInt() }

    init {
        clipChildren = false; clipToPadding = false
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
            setPadding(0, dp(16), 0, dp(28)); elevation = dp(16).toFloat()
        }
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        ruler.id = R.id.pf_ff_land_ruler
        panel.addView(ruler, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(93)))
        ruler.onTimeChanged = onRange
        val cardFrame = FrameLayout(context)
        panel.addView(cardFrame, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(120)).apply { topMargin = dp(8) })
        cards.id = R.id.pf_ff_land_cards
        cards.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        cards.adapter = adapter; cards.clipToPadding = false
        cardFrame.addView(cards, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        status.apply {
            id = R.id.pf_ff_land_query_status; gravity = Gravity.CENTER; textSize = 13f
            setTextColor(if (night) Color.WHITE else Color.BLACK); setOnClickListener { onRetry() }
        }
        cardFrame.addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        confirm.apply {
            id = R.id.pf_ff_land_confirm; textSize = 16f; isAllCaps = false; includeFontPadding = false
            typeface = androidx.core.content.res.ResourcesCompat.getFont(context, com.projectfocus.core.ui.R.font.harmonyos_sans_sc_medium)
            setTextColor(Color.WHITE); backgroundTintList = ColorStateList.valueOf(Color.BLACK)
            cornerRadius = dp(28); elevation = dp(15).toFloat(); insetTop = 0; insetBottom = 0
            setPadding(dp(60), 0, dp(60), 0); setOnClickListener { onConfirm() }
        }
        panel.addView(confirm, LinearLayout.LayoutParams(dp(300), dp(56)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8); bottomMargin = dp(8); marginStart = dp(16); marginEnd = dp(16)
        })
        updateConfirm(false)
    }

    fun render(minutes: Int, selected: String?, busy: Boolean) {
        if (ruler.currentTime() != minutes) ruler.setCurrentTime(minutes)
        if (selectedCode != selected) { selectedCode = selected; adapter.notifyDataSetChanged() }
        ruler.isEnabled = !busy; cards.isEnabled = !busy
        updateConfirm(!busy && selected != null && options.any { it.airport.code == selected })
    }

    fun loading() {
        options = emptyList(); selectedCode = null; adapter.notifyDataSetChanged()
        status.visibility = View.VISIBLE; status.setText(R.string.pf_ff_land_loading); status.isClickable = false
        updateConfirm(false)
    }
    fun failed() { status.visibility = View.VISIBLE; status.setText(R.string.pf_ff_land_retry); status.isClickable = true; updateConfirm(false) }
    fun showOptions(values: List<FreeFlightLandingOption>) {
        options = values.toList(); adapter.notifyDataSetChanged()
        status.visibility = if (values.isEmpty()) View.VISIBLE else View.GONE
        status.setText(R.string.pf_ff_land_empty); status.isClickable = false
        if (values.isNotEmpty()) cards.post { centerFirstCard(0) }
    }

    private fun centerFirstCard(attempt: Int) {
        if (!isAttachedToWindow || adapter.itemCount == 0) return
        val first = cards.findViewHolderForAdapterPosition(0)?.itemView
        if (cards.width <= 0 || first == null || first.width <= 0) {
            if (attempt < 3) cards.post { centerFirstCard(attempt + 1) }
            return
        }
        val padding = ((cards.width - first.width) / 2).coerceAtLeast(0)
        cards.setPaddingRelative(padding, 0, padding, 0); cards.scrollToPosition(0)
        cards.animate().cancel(); cards.alpha = 0f; cards.translationX = dp(40).toFloat()
        cards.animate().alpha(1f).translationX(0f).setDuration(300).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        // e4.e(0) centres/reveals the first card; it DOES NOT select an airport.
    }

    private fun updateConfirm(enabled: Boolean) {
        confirm.setText(if (enabled) R.string.pf_ff_land_confirm_landing else R.string.pf_ff_land_please_select_landing_airport)
        confirm.isEnabled = enabled; confirm.alpha = if (enabled) 1f else .5f
    }

    private inner class CardHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name = view.findViewById<TextView>(R.id.pf_ff_card_tv_airport_name)
        val time = view.findViewById<TextView>(R.id.pf_ff_card_tv_airport_city)
        val code = view.findViewById<TextView>(com.projectfocus.feature.airport.R.id.pf_airport_airport_code)
        var option: FreeFlightLandingOption? = null
        var selected = false
        var backgroundAnimation: ValueAnimator? = null
        var textAnimation: ValueAnimator? = null
        init { view.setOnClickListener { if (cards.isEnabled) option?.let(adapter.onSelect) } }
        fun stop() { backgroundAnimation?.cancel(); textAnimation?.cancel(); backgroundAnimation = null; textAnimation = null }
        fun bind(value: FreeFlightLandingOption) {
            val wasSelected = selected
            option = value; stop(); selected = value.airport.code == selectedCode
            code.text = value.airport.code.trim().uppercase(Locale.ENGLISH)
            name.text = value.airport.displayName
            time.text = if (value.minutes < 60) "${value.minutes}m" else "${value.minutes / 60}h${value.minutes % 60}m"
            val normal = ContextCompat.getColor(context, com.projectfocus.feature.airport.R.color.pf_airport_card_text_color)
            time.setTextColor(ContextCompat.getColor(context, com.projectfocus.feature.airport.R.color.pf_airport_source_subtitle))
            fun background(color: Int) { itemView.background = GradientDrawable().apply { setColor(color); cornerRadius = 24f } }
            if (selected && !wasSelected) {
                backgroundAnimation = ValueAnimator.ofObject(ArgbEvaluator(), 0xff0f0f0f.toInt(), Color.WHITE).apply {
                    duration = 200; addUpdateListener { background(it.animatedValue as Int) }; start()
                }
                textAnimation = ValueAnimator.ofObject(ArgbEvaluator(), normal, Color.BLACK).apply {
                    duration = 200; addUpdateListener { name.setTextColor(it.animatedValue as Int) }; start()
                }
            } else {
                background(if (selected) Color.WHITE else 0xff0f0f0f.toInt())
                name.setTextColor(if (selected) Color.BLACK else normal)
                if (selected) time.setTextColor(Color.BLACK)
            }
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
        override fun onViewRecycled(holder: CardHolder) { holder.stop(); holder.option = null }
    }

    override fun onDetachedFromWindow() {
        cards.animate().cancel()
        for (i in 0 until cards.childCount) (cards.getChildViewHolder(cards.getChildAt(i)) as? CardHolder)?.stop()
        super.onDetachedFromWindow()
    }
}
