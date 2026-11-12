package com.projectfocus.feature.record.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.projectfocus.feature.record.R
import com.projectfocus.core.ui.R as UiR
import com.projectfocus.feature.record.list.FlightStatus
import com.projectfocus.feature.record.list.RecordItem
import com.projectfocus.feature.scene.source.SourceSceneCatalog
import com.projectfocus.service.focus.history.FlightRecordKind

class RecordCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialCardViewStyle,
) : MaterialCardView(context, attrs, defStyleAttr) {
    init {
        LayoutInflater.from(context).inflate(R.layout.pf_record_card_content, this, true)
        radius = 24f * resources.displayMetrics.density
        useCompatPadding = true
        strokeWidth = (resources.displayMetrics.density + 0.5f).toInt()
        isClickable = true
        isFocusable = true
        layoutParams = RecyclerView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = (4f * resources.displayMetrics.density + 0.5f).toInt()
            bottomMargin = topMargin
        }
    }

    private val distance = text(R.id.pf_record_card_text_distance)
    private val mode = text(R.id.pf_record_card_text_flight_type_badge)
    private val fromCity = text(R.id.pf_record_card_text_departure_city)
    private val toCity = text(R.id.pf_record_card_text_arrival_city)
    private val fromCode = text(R.id.pf_record_card_text_departure_code)
    private val toCode = text(R.id.pf_record_card_text_arrival_code)
    private val flightNumber = text(R.id.pf_record_card_text_flight_number)
    private val departureTime = text(R.id.pf_record_card_text_departure_time)
    private val date = text(R.id.pf_record_card_text_date)
    private val focus = text(R.id.pf_record_card_text_focusTime)
    private val seat = text(R.id.pf_record_card_text_seat)
    private val status = text(R.id.pf_record_card_text_status_desc)
    private val scene = text(R.id.pf_record_card_text_focus_scene)
    private val sceneIcon = findViewById<ImageView>(R.id.pf_record_card_img_focus_icon)
    private val statusDot = findViewById<ImageView>(R.id.pf_record_card_img_status_dot)
    private val focusContainer = findViewById<View>(R.id.pf_record_card_container_focus_duration)
    private val sceneEdit = findViewById<View>(R.id.pf_record_card_img_focus_scene_edit)
    private val cloud = findViewById<View>(R.id.pf_record_card_img_archive_cloud_icon)

    init {
        RecordSkyPresentation.ticket(this)
        bind(null)
    }

    fun bind(item: RecordItem?, onSceneEdit: ((View, RecordItem) -> Unit)? = null,
             sceneEditingEnabled: Boolean = true) {
        val unknown = context.getString(R.string.pf_record_card_common_na)
        setCardBackgroundColor(color(UiR.color.sky_card_solid))
        strokeColor = color(UiR.color.sky_divider)
        listOf(distance, fromCode, toCode, flightNumber, departureTime, date, focus, seat)
            .forEach { it.setTextColor(color(UiR.color.sky_ink)) }
        listOf(fromCity, toCity).forEach { it.setTextColor(RecordSkyPresentation.ticketSecondaryInk(context)) }
        cloud.visibility = View.GONE
        sceneEdit.visibility = View.GONE
        sceneEdit.setOnClickListener(null)
        sceneEdit.isEnabled = false
        contentDescription = item?.spokenSummary.orEmpty()
        visibility = if (item == null) View.INVISIBLE else View.VISIBLE
        if (item == null) {
            listOf(distance, fromCity, toCity, fromCode, toCode, flightNumber, departureTime, date, focus, seat, status)
                .forEach { it.text = unknown }
            mode.text = ""
            scene.text = ""
            scene.visibility = View.GONE
            sceneIcon.visibility = View.GONE
            focusContainer.visibility = View.GONE
            return
        }
        distance.text = item.distanceText
        mode.setText(if (item.kind == FlightRecordKind.COUNT_UP) R.string.pf_record_card_archive_badge_countup
            else R.string.pf_record_card_archive_badge_countdown)
        mode.visibility = View.VISIBLE
        fromCode.text = item.fromCode
        val missingAirport = context.getString(R.string.pf_record_card_missing_airport_code)
        toCode.text = if (item.arrivalSelected) item.toCode else missingAirport
        fromCity.text = item.fromCity
        toCity.text = if (item.arrivalSelected) item.toCity.ifBlank { unknown } else missingAirport
        flightNumber.text = item.flightNumber?.takeIf { it.isNotBlank() } ?: unknown
        departureTime.text = item.startTime
        date.text = item.dateText?.takeIf { it.isNotBlank() } ?: unknown
        seat.text = item.seat?.takeIf { it.isNotBlank() } ?: unknown
        val statusColor = when (item.status) {
            FlightStatus.COMPLETED -> RecordSkyPresentation.ticketCompletedInk(context)
            FlightStatus.ONGOING -> color(UiR.color.sky_primary)
            FlightStatus.CANCELLED, FlightStatus.UNKNOWN -> RecordSkyPresentation.ticketSecondaryInk(context)
        }
        statusDot.setColorFilter(statusColor)
        status.setTextColor(statusColor)
        status.text = " " + context.getString(when (item.status) {
            FlightStatus.COMPLETED -> R.string.pf_record_card_flight_status_arrived
            FlightStatus.ONGOING -> R.string.pf_record_card_record_status_in_progress
            FlightStatus.CANCELLED -> R.string.pf_record_card_record_status_cancelled
            FlightStatus.UNKNOWN -> R.string.pf_record_summary_pending
        })
        focus.text = item.focusMinutes?.let { "$it ${context.getString(R.string.pf_record_card_common_min)}" } ?: item.focusText
        focusContainer.visibility = if (item.status == FlightStatus.COMPLETED) View.VISIBLE else View.GONE
        val title = item.sceneTitle?.takeIf { it.isNotBlank() }
        scene.text = title.orEmpty()
        scene.visibility = if (title == null) View.GONE else View.VISIBLE
        sceneIcon.visibility = scene.visibility
        val builtin = SourceSceneCatalog.builtins.firstOrNull { it.iconKey == item.sceneIconKey }
            ?: SourceSceneCatalog.builtins.firstOrNull { it.type == item.sceneType }
        sceneIcon.setImageResource(builtin?.iconRes ?: R.drawable.pf_record_card_ic_focus_mode)
        val sceneColor = builtin?.color ?: color(UiR.color.sky_ink)
        sceneIcon.setColorFilter(sceneColor)
        scene.setTextColor(color(UiR.color.sky_ink))
        if (item.status == FlightStatus.COMPLETED && (item.cloudSummary != null || item.recordedAtWallMs != null) && onSceneEdit != null) {
            sceneEdit.visibility = View.VISIBLE
            (sceneEdit as ImageView).setColorFilter(color(UiR.color.sky_primary))
            sceneEdit.isEnabled = sceneEditingEnabled
            sceneEdit.setOnClickListener { anchor -> if (sceneEdit.isEnabled) onSceneEdit(anchor, item) }
        }
    }

    private fun text(id: Int): TextView = findViewById(id)
    private fun color(id: Int): Int = ContextCompat.getColor(context, id)
}
