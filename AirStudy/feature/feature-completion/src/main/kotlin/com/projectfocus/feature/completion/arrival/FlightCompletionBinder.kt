package com.projectfocus.feature.completion.arrival

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.feature.completion.R
import com.projectfocus.core.model.flight.freeflight.CompletedFreeFlight
import com.projectfocus.core.ui.widget.RollingNumberView
import androidx.core.content.res.ResourcesCompat
import android.graphics.Color


class FlightCompletionBinder(root: View) {

    private val root: View = root
    private val codeOnMedia: TextView = root.findViewById(R.id.arrival_airport_code_on_media)
    private val welcomeText: TextView = root.findViewById(R.id.welcome_text)
    private val cityTitle: TextView = root.findViewById(R.id.arrival_city_title)
    private val airportLink: TextView = root.findViewById(R.id.arrival_airport_link)
    private val subtitle: TextView = root.findViewById(R.id.welcome_subtitle)
    private val statsContainer: LinearLayout = root.findViewById(R.id.stats_container)
    private val mileage: RollingNumberView = root.findViewById(R.id.nsv_mileage)
    private val focus: RollingNumberView = root.findViewById(R.id.nsv_focus)
    private val shareButton: View = root.findViewById(R.id.restart_flight_button)
    private val reportButton: View = root.findViewById(R.id.report_airport_button)
    private val completeButton: View = root.findViewById(R.id.complete_flight_button)

    var onShare: (() -> Unit)? = null
    var onReport: (() -> Unit)? = null
    var onComplete: (() -> Unit)? = null
    var onAirport: (() -> Unit)? = null

    init {
        val tablet = root.resources.configuration.smallestScreenWidthDp >= 600
        val typeface = ResourcesCompat.getFont(root.context,if (tablet) R.font.source_han_serif_cn_semibold else R.font.source_han_serif_cn_regular)
        for (counter in listOf(mileage,focus)) {
            counter.setTextColor(Color.WHITE)
            counter.setTextSize(if (tablet) 46 else 30)
            counter.setTypeface(typeface)
        }
        shareButton.setOnClickListener { onShare?.invoke() }
        reportButton.setOnClickListener { onReport?.invoke() }
        completeButton.setOnClickListener { onComplete?.invoke() }
        airportLink.isClickable=true;airportLink.isFocusable=true
        airportLink.setOnClickListener { onAirport?.invoke() }
    }

    fun bindFreeFlight(result:CompletedFreeFlight) {
        val airport = result.destination
        codeOnMedia.text=airport.code
        welcomeText.setText(R.string.arrival_welcome)
        val city=airport.displayCity.trim { it<='\u0020' }.ifBlank { airport.city.trim { it<='\u0020' } }
            .ifBlank { airport.displayName.trim { it<='\u0020' } }.ifBlank { airport.name.trim { it<='\u0020' } }.ifBlank { airport.code }
        val name=airport.displayName.trim { it<='\u0020' }.ifBlank { airport.name.trim { it<='\u0020' } }.ifBlank { city }
        cityTitle.text=city
        airportLink.text="$name >"
        subtitle.text=""
        statsContainer.visibility=View.GONE
        setCompleteEnabled(true)
    }

    fun bindTotals(distanceKm:Double,focusMinutes:Int) {
        require(distanceKm.isFinite() && distanceKm>=0.0 && focusMinutes>=0)
        mileage.setValue(Math.max(0,Math.round(distanceKm).toInt()))
        focus.setValue(focusMinutes)
        statsContainer.visibility=View.VISIBLE
    }

    /**
     * @param record 已完成飞行记录
     * @param todayDistanceKm 今日累计里程；由调用方按“今日 00:00–24:00”聚合
     * @param todayFocusMinutes 今日累计专注分钟
     */
    fun bind(
        record: FlightHistoryEntity,
        todayDistanceKm: Double,
        todayFocusMinutes: Int,
    ) {        root.visibility = View.VISIBLE

        codeOnMedia.text = record.destinationAirportCode
        cityTitle.text = record.destinationCity.ifBlank { record.destinationAirportCode }
        airportLink.text = buildAirportLink(record)
        subtitle.text = buildSubtitle(record)

        statsContainer.visibility =
            if (todayDistanceKm > 0.0 || todayFocusMinutes > 0) View.VISIBLE else View.GONE
        mileage.setValue(Math.max(0,Math.round(todayDistanceKm).toInt()))
        focus.setValue(Math.max(0,todayFocusMinutes))

        shareButton.setOnClickListener { onShare?.invoke() }
        reportButton.setOnClickListener { onReport?.invoke() }
        completeButton.setOnClickListener { onComplete?.invoke() }
        airportLink.isClickable = true
        airportLink.isFocusable = true
        airportLink.setOnClickListener { onAirport?.invoke() }
    }







    fun setCompleteEnabled(enabled: Boolean) {
        completeButton.isEnabled = enabled
        completeButton.alpha = if (enabled) 1f else 0.5f
    }

    private fun buildAirportLink(record: FlightHistoryEntity): String {
        val name = record.destinationAirportName.trim()
        val code = record.destinationAirportCode.trim()
        return when {
            name.isNotEmpty() && code.isNotEmpty() && !name.endsWith(code) -> "$name · $code"
            name.isNotEmpty() -> name
            else -> code
        }
    }


    private fun buildSubtitle(record: FlightHistoryEntity): String {
        val quote = record.quote?.trim().orEmpty()
        val author = record.quoteAuthor?.trim().orEmpty()
        if (quote.isNotEmpty()) {
            return if (author.isNotEmpty()) "“$quote” — $author" else "“$quote”"
        }
        val from = record.departureCity.ifBlank { record.departureAirportCode }
        val to = record.destinationCity.ifBlank { record.destinationAirportCode }
        return "$from → $to"
    }
}
