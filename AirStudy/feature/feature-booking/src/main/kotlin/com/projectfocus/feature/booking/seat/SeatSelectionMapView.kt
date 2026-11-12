package com.projectfocus.feature.booking.seat

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.LayerDrawable
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.res.ResourcesCompat
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import androidx.core.graphics.Insets
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import com.projectfocus.core.ui.sky.SkyBackdropView
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
    private lateinit var selectionHint: TextView
    private lateinit var heading: TextView
    private lateinit var legend: TextView

    init {
        clipChildren = false
        clipToPadding = false
        LayoutInflater.from(context).inflate(R.layout.pf_seat_selection_airframe, this, true)
        addView(SkyBackdropView(context), 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val ship = findViewById<LinearLayout>(R.id.pf_seat_spaceshipContainer)
        val density = resources.displayMetrics.density
        ship.setPadding(0, (16f * density).toInt(), 0, (128f * density).toInt())
        val intro = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22f * density).toInt(), 0, (22f * density).toInt(), (18f * density).toInt())
        }
        heading = TextView(context).apply {
            setText(R.string.seat_sky_title);textSize = 23f;setTypeface(typeface, Typeface.BOLD)
            ViewCompat.setAccessibilityHeading(this, true)
        }
        selectionHint = TextView(context).apply { textSize = 13f;setPadding(0, (5f * density).toInt(), 0, 0) }
        legend = TextView(context).apply { setText(R.string.seat_sky_legend);textSize = 11f;setPadding(0, (9f * density).toInt(), 0, 0) }
        intro.addView(heading);intro.addView(selectionHint);intro.addView(legend)
        ship.addView(intro, 0, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
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
        setLightCabin(resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES)
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
        findViewById<View>(R.id.pf_seat_rootLayout).setBackgroundColor(Color.TRANSPARENT)
        heading.setTextColor(if (enabled) 0xFF17243F.toInt() else 0xFFEFF4FF.toInt())
        selectionHint.setTextColor(if (enabled) 0xFF526582.toInt() else 0xFFB5C4DD.toInt())
        legend.setTextColor(if (enabled) 0xFF62718B.toInt() else 0xFFA8BCD8.toInt())
        findViewById<ImageView>(R.id.pf_seat_dynamicSpaceshipBody).setImageResource(
            if (enabled) R.drawable.pf_seat_dynamic_spaceship_body_white else R.drawable.pf_seat_dynamic_spaceship_body,
        )
        refreshSeats()
    }

    /** Full-window preparation calls this; ordinary navigation already has parent insets. */
    fun applySafeInsets(safe: Insets) {
        findViewById<ScrollView>(R.id.pf_seat_scroll).setPadding(safe.left, safe.top, safe.right, safe.bottom)
    }

    private fun applyScene(seat: TextView) {
        val scene = selectedScene ?: return
        val fallbackIcon = when (scene.iconKey) {
            "meditation" -> R.drawable.pf_seat_ic_focus_meditation
            "work" -> R.drawable.pf_seat_ic_focus_work
            "music" -> R.drawable.pf_seat_ic_focus_music
            "relax" -> R.drawable.pf_seat_ic_focus_relax
            "study" -> R.drawable.pf_seat_ic_focus_study
            "reading" -> R.drawable.pf_seat_ic_focus_reading
            "creative" -> R.drawable.pf_seat_ic_focus_creative
            "exercise" -> R.drawable.pf_seat_ic_focus_exercise
            else -> 0
        }
        val iconId = scene.iconRes.takeIf { it != 0 } ?: fallbackIcon
        if (iconId == 0) return
        val icon = AppCompatResources.getDrawable(context, iconId)?.mutate() ?: return
        val accent = scene.color.takeIf { it != 0 } ?: Color.rgb(44, 100, 205)
        val upperColor = ColorUtils.blendARGB(accent, Color.WHITE, if (lightCabin) .48f else .12f)
        icon.setTint(if (ColorUtils.calculateLuminance(upperColor) > .35) 0xFF17243F.toInt() else Color.WHITE)
        val density = resources.displayMetrics.density
        val background = SkySeatDrawable(lightCabin, selected = true, occupied = false,
            accent = accent)
        seat.background = LayerDrawable(arrayOf(background, icon)).apply {
            val inset = (17f * density).toInt()
            setLayerInset(1, inset, (12f * density).toInt(), inset, (22f * density).toInt())
        }
    }

    private fun refreshSeats() {
        selectionHint.text = selectedSeat?.let { seat -> selectedScene?.let { scene ->
            context.getString(R.string.seat_sky_selection, seat, scene.title)
        } ?: context.getString(R.string.seat_sky_selection_without_scene, seat) }
            ?: context.getString(R.string.seat_sky_hint)
        val area = findViewById<LinearLayout>(R.id.pf_seat_seatsArea)
        (area.getChildAt(0) as? LinearLayout)?.let { headers ->
            for (i in 0 until headers.childCount) (headers.getChildAt(i) as? TextView)?.setTextColor(
                if (lightCabin) 0xFF526582.toInt() else 0xFFA8BCD8.toInt())
        }
        for (i in 0 until area.childCount) {
            val row = area.getChildAt(i)
            row.findViewById<TextView>(R.id.pf_seat_rowNumberText)?.setTextColor(
                if (lightCabin) 0xFF526582.toInt() else 0xFFA8BCD8.toInt())
        }
        seats.forEach { (label, seat) ->
            seat.isSelected = label == selectedSeat
            seat.isEnabled = label !in occupied
            seat.background = SkySeatDrawable(lightCabin, label == selectedSeat, label in occupied)
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
