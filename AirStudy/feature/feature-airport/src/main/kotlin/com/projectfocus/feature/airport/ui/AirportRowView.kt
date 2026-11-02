package com.projectfocus.feature.airport.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
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

        // a5.v6.a replaces the XML selector with a solid background, radius in raw px.
        // No selected-code contract is supplied by this adapter; query exactness is not selection.
        card.isSelected = false
        card.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(ContextCompat.getColor(context, R.color.pf_airport_card_normal_background))
            cornerRadius = 24f
        }
        name.setTextColor(ContextCompat.getColor(context, R.color.pf_airport_card_text_color))
        flightTime.setTextColor(ContextCompat.getColor(context, R.color.pf_airport_source_subtitle))
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
