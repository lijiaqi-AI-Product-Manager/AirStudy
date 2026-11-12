package com.projectfocus.feature.airport.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import com.projectfocus.feature.airport.R
import com.projectfocus.feature.airport.search.AirportDisplayFormatter
import com.projectfocus.feature.airport.search.AirportSearchIndex


class AirportRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val card = LayoutInflater.from(context).inflate(R.layout.pf_airport_card_vertical, this, false)
    private val code = card.findViewById<TextView>(R.id.pf_airport_airport_code)
    private val name = card.findViewById<TextView>(R.id.pf_airport_tv_airport_name)
    // The source ID says "city", but a5.w6 actually binds the flight duration here.
    private val flightTime = card.findViewById<TextView>(R.id.pf_airport_tv_airport_city)
    private var hit: AirportSearchIndex.Hit? = null

    // Kept for adapter compatibility. The source card background replaces the old drawn divider.
    var showDivider: Boolean = true

    init {
        addView(card)
        card.visibility = View.INVISIBLE
        card.stateListAnimator = null
        code.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
        (code.layoutParams as android.widget.RelativeLayout.LayoutParams).apply {
            marginStart = 0; topMargin = 0
            addRule(android.widget.RelativeLayout.CENTER_IN_PARENT)
        }
        name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
        name.maxLines = if (resources.configuration.fontScale > 1.25f) 4 else 3
        name.gravity = android.view.Gravity.START
        flightTime.gravity = android.view.Gravity.START
        (name.parent as android.widget.LinearLayout).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(com.projectfocus.core.ui.sky.SkySurfaces.dp(context, 14f), 0, 0, 0)
        }
        name.typeface = androidx.core.content.res.ResourcesCompat.getFont(context,
            com.projectfocus.core.ui.R.font.harmonyos_sans_sc_bold)
        card.minimumHeight = com.projectfocus.core.ui.sky.SkySurfaces.dp(context, 84f)
        com.projectfocus.core.ui.sky.SkyPressFeedback.install(this)
    }

    @Suppress("UNUSED_PARAMETER")
    fun bind(
        hit: AirportSearchIndex.Hit?,
        keyword: String,
        typeLabel: String? = null,
        flightMinutes: Int? = null,
    ) {
        this.hit = hit
        card.visibility = if (hit == null) View.INVISIBLE else View.VISIBLE
        code.text = hit?.airport?.displayCode.orEmpty()

        name.text = hit?.airport?.let {
            it.nameZh?.takeIf(String::isNotBlank) ?: it.name.takeIf(String::isNotBlank) ?: "--"
        }.orEmpty()
        flightTime.text = if (hit == null) "" else AirportCardFlightTime.format(flightMinutes)
        flightTime.visibility = if (hit != null && flightMinutes != null) View.VISIBLE else View.GONE
        val icon = card.findViewById<android.widget.ImageView>(R.id.pf_airport_airport_icon)
        icon.setImageDrawable(android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = com.projectfocus.core.ui.sky.SkySurfaces.dp(context, 16f).toFloat()
            setColor(context.getColor(com.projectfocus.core.ui.R.color.sky_selected))
        })
        icon.layoutParams = icon.layoutParams.apply {
            width = maxOf(com.projectfocus.core.ui.sky.SkySurfaces.dp(context, 72f),
                code.paint.measureText(code.text.toString()).toInt() + com.projectfocus.core.ui.sky.SkySurfaces.dp(context, 24f))
            height = com.projectfocus.core.ui.sky.SkySurfaces.dp(context, 52f)
        }
        val badge = card.findViewById<View>(R.id.pf_airport_tv_arrived_badge)
        val stamp = card.findViewById<View>(R.id.pf_airport_iv_arrived_stamp)
        (badge.parent as View).visibility = if (badge.visibility == View.GONE && stamp.visibility == View.GONE) View.GONE else View.VISIBLE

        // a5.v6.a replaces the XML selector with a solid background, radius in raw px.
        // No selected-code contract is supplied by this adapter; query exactness is not selection.
        card.isSelected = false
        card.background = com.projectfocus.core.ui.sky.SkySurfaces.shape(context, radius = 24f, solid = true)
        name.setTextColor(context.getColor(com.projectfocus.core.ui.R.color.sky_ink))
        code.setTextColor(context.getColor(com.projectfocus.core.ui.R.color.sky_primary))
        flightTime.setTextColor(context.getColor(com.projectfocus.core.ui.R.color.sky_ink_secondary))
        // Arrival badge/stamp stay at the source's initial GONE state. Exact query
        // matches do not prove a past visit, a direct flight or a selected card.
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val current = hit
        info.contentDescription = current?.let {
            buildString {
                append(AirportDisplayFormatter.primaryTitle(
                    it.airport.cityZh, it.airport.city, it.airport.nameZh, it.airport.name,
                ))
                if (name.text.isNotBlank()) append("，").append(name.text)
                if (code.text.isNotBlank()) append("，代码 ").append(code.text.toString().toCharArray().joinToString(" "))
                if (flightTime.text.isNotBlank() && flightTime.text.toString() != "--") {
                    append("，").append(context.getString(R.string.airport_card_flight_time_accessibility, flightTime.text))
                }
                if (it.exactCode) append("，精确匹配")
            }
        }
        info.isClickable = isClickable
        info.className = "android.widget.Button"
    }
}
