package com.projectfocus.feature.booking.seat

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.ColorUtils
import androidx.core.content.res.ResourcesCompat
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.projectfocus.core.common.seat.SeatMapGeometry
import com.projectfocus.feature.booking.R
import com.projectfocus.core.ui.R as CoreUiR
import java.util.Locale


class SeatSelectionMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    var onSeatSelected: ((String) -> Unit)? = null
    var onSeatTapped: ((String, View) -> Unit)? = null
    var interactionEnabled: Boolean = true
    private var selectedScene: SeatSceneChoice? = null
    var selectedSeat: String? = null
        private set
    private val seats = LinkedHashMap<String, TextView>()
    private val occupied = mutableSetOf<String>()
    private var lightCabin = false

    init {
        clipChildren = false
        clipToPadding = false
        LayoutInflater.from(context).inflate(R.layout.pf_seat_selection_airframe, this, true)
        findViewById<ImageView>(R.id.pf_seat_dynamicSpaceshipBody).apply {
            layoutParams = layoutParams.apply {
                width = (3200f * resources.displayMetrics.density).toInt()
                height = (1850f * resources.displayMetrics.density).toInt()
            }
        }
        val area = findViewById<LinearLayout>(R.id.pf_seat_seatsArea)
        val inflater = LayoutInflater.from(context)
        // x4.u.h/k overrides the template font for column labels and row numbers.
        val mono = ResourcesCompat.getFont(context, CoreUiR.font.jetbrainsmono_medium)
        val headers = inflater.inflate(R.layout.pf_seat_seat_column_headers, area, false) as LinearLayout
        for (index in 0 until headers.childCount) {
            (headers.getChildAt(index) as? TextView)?.typeface = mono
        }
        area.addView(headers)
        repeat(SeatMapGeometry.ROWS) { rowIndex ->
            val row = inflater.inflate(R.layout.pf_seat_seat_row_layout, area, false)
            row.findViewById<TextView>(R.id.pf_seat_rowNumberText).apply {
                text = String.format(Locale.US, "%02d", rowIndex + 1)
                typeface = mono
            }
            val ids = intArrayOf(R.id.pf_seat_seatA, R.id.pf_seat_seatC, R.id.pf_seat_seatD, R.id.pf_seat_seatF)
            ids.forEachIndexed { columnIndex, id ->
                val label = SeatMapGeometry.label(rowIndex, columnIndex)
                val seat = row.findViewById<TextView>(id)
                seats[label] = seat
                seat.tag = label
                seat.isClickable = true
                seat.isFocusable = true
                seat.setOnClickListener {
                    if (interactionEnabled && label !in occupied) {
                        val listener = onSeatTapped
                        if (listener != null) listener(label, seat) else {
                            select(label)
                            onSeatSelected?.invoke(label)
                        }
                    }
                }
                seat.accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = "android.widget.RadioButton"
                        info.isCheckable = true
                        info.isChecked = label == selectedSeat
                    }
                }
            }
            area.addView(row)
        }
        refreshSeats()
    }

    fun select(seat: String, scene: SeatSceneChoice? = null) {
        val canonical = canonicalSeat(seat) ?: return
        selectedSeat = canonical
        selectedScene = scene
        refreshSeats()
    }

    fun clearSelection() {
        selectedSeat = null
        selectedScene = null
        refreshSeats()
    }

    fun setOccupied(values: Set<String>) {
        occupied.clear()
        occupied.addAll(values.mapNotNull(::canonicalSeat))
        refreshSeats()
    }


    fun setLightCabin(enabled: Boolean) {
        lightCabin = enabled
        // Retained source onViewCreated/onResume sets the overlay with the same theme.
        findViewById<View>(R.id.pf_seat_rootLayout).setBackgroundColor(
            if (enabled) 0x1AFFFFFF else 0x80000000.toInt(),
        )
        findViewById<ImageView>(R.id.pf_seat_dynamicSpaceshipBody).setImageResource(
            if (enabled) R.drawable.pf_seat_dynamic_spaceship_body_white else R.drawable.pf_seat_dynamic_spaceship_body,
        )
        refreshSeats()
    }

    private fun applyScene(seat: TextView) {
        val scene = selectedScene ?: return
        val sourceDrawable = when (scene.iconKey) {
            "meditation" -> R.drawable.pf_seat_seat_selected_meditation
            "work" -> R.drawable.pf_seat_seat_selected_work
            "music" -> R.drawable.pf_seat_seat_selected_music
            "relax" -> R.drawable.pf_seat_seat_selected_relax
            "study" -> R.drawable.pf_seat_seat_selected_study
            "reading" -> R.drawable.pf_seat_seat_selected_reading
            "creative" -> R.drawable.pf_seat_seat_selected_creative
            "exercise" -> R.drawable.pf_seat_seat_selected_exercise
            else -> 0
        }
        if (sourceDrawable != 0) {
            seat.setBackgroundResource(sourceDrawable)
            return
        }
        if (scene.iconRes == 0 || scene.color == 0) return
        val icon = AppCompatResources.getDrawable(context, scene.iconRes)?.mutate() ?: return
        icon.setTint(Color.WHITE)
        val density = resources.displayMetrics.density
        val background = GradientDrawable().apply {
            cornerRadius = 8f * density
            setColor(scene.color)
            // Recovered e0.c.c is per-channel C2*r+C1*(1-r), with float-to-int
            // truncation including alpha. The actual Core 1.17 blendARGB bytecode
            // matches this operation for the source's .2f ratio.
            setStroke((2f * density).toInt(), ColorUtils.blendARGB(scene.color, Color.BLACK, 0.2f))
        }
        seat.background = LayerDrawable(arrayOf(background, icon)).apply {
            val inset = (10f * density).toInt()
            setLayerInset(1, inset, inset, inset, inset)
        }
    }

    private fun refreshSeats() {
        seats.forEach { (label, seat) ->
            seat.isSelected = label == selectedSeat
            seat.isEnabled = label !in occupied
            seat.setBackgroundResource(
                when {
                    label == selectedSeat -> R.drawable.pf_seat_seat_selected
                    label in occupied -> R.drawable.pf_seat_seat_occupied
                    lightCabin -> R.drawable.pf_seat_seat_available_white
                    else -> R.drawable.pf_seat_seat_available
                },
            )
            if (label == selectedSeat) applyScene(seat)
            seat.text = ""
            seat.setCompoundDrawables(null, null, null, null)
            seat.contentDescription = context.getString(
                when {
                    label == selectedSeat -> R.string.pf_seat_selected_accessibility
                    label in occupied -> R.string.pf_seat_occupied_accessibility
                    else -> R.string.pf_seat_available_accessibility
                }, label,
            )
        }
    }
}
