package com.projectfocus.feature.completion.arrival

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.feature.completion.R
import com.projectfocus.feature.completion.pass.HudFmt


class FlightCompletionBinder(root: View) {

    private val root: View = root
    private val codeOnMedia: TextView = root.findViewById(R.id.arrival_airport_code_on_media)
    private val welcomeText: TextView = root.findViewById(R.id.welcome_text)
    private val cityTitle: TextView = root.findViewById(R.id.arrival_city_title)
    private val airportLink: TextView = root.findViewById(R.id.arrival_airport_link)
    private val subtitle: TextView = root.findViewById(R.id.welcome_subtitle)
    private val statsContainer: LinearLayout = root.findViewById(R.id.stats_container)
    private val mileage: TextView = root.findViewById(R.id.nsv_mileage)
    private val focus: TextView = root.findViewById(R.id.nsv_focus)
    private val shareButton: View = root.findViewById(R.id.restart_flight_button)
    private val reportButton: View = root.findViewById(R.id.report_airport_button)
    private val completeButton: View = root.findViewById(R.id.complete_flight_button)

    var onShare: (() -> Unit)? = null
    var onReport: (() -> Unit)? = null
    var onComplete: (() -> Unit)? = null
    var onAirport: (() -> Unit)? = null

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
        mileage.text = HudFmt.km(todayDistanceKm)
        focus.text = todayFocusMinutes.toString()

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
