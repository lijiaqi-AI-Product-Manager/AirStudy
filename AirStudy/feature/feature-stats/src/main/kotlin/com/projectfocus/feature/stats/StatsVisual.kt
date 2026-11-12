package com.projectfocus.feature.stats

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.projectfocus.core.ui.R as UiR
import kotlin.math.roundToInt

internal class StatsVisual(val context: Context) {
    fun dp(value: Float) = (value * context.resources.displayMetrics.density).toInt()
    fun dp(value: Int) = dp(value.toFloat())
    fun color(id: Int) = ContextCompat.getColor(context, id)
    val background get() = color(UiR.color.sky_canvas)
    val surface get() = color(UiR.color.sky_card_solid)
    val secondarySurface get() = color(UiR.color.sky_selected)
    val border get() = color(UiR.color.sky_divider)
    val primaryText get() = color(UiR.color.sky_ink)
    val secondaryText get() = mix(color(UiR.color.sky_ink_secondary), primaryText, 0.16f)
    val tertiaryText get() = secondaryText
    val accent get() = color(UiR.color.sky_primary)
    val selectedSurface get() = color(UiR.color.sky_selected)
    val onAccent get() = if (androidx.core.graphics.ColorUtils.calculateContrast(Color.WHITE, accent) >=
        androidx.core.graphics.ColorUtils.calculateContrast(Color.BLACK, accent)) Color.WHITE else Color.BLACK
    fun alpha(color: Int, amount: Float) = androidx.core.graphics.ColorUtils.setAlphaComponent(color, (255 * amount).roundToInt())
    fun mix(first: Int, second: Int, amount: Float): Int {
        val f = amount.coerceIn(0f, 1f)
        fun channel(a: Int, b: Int) = (a + (b - a) * f).roundToInt()
        return Color.argb(channel(Color.alpha(first), Color.alpha(second)), channel(Color.red(first), Color.red(second)),
            channel(Color.green(first), Color.green(second)), channel(Color.blue(first), Color.blue(second)))
    }
    fun rounded(radius: Int, colors: IntArray, stroke: Int? = null,
                orientation: GradientDrawable.Orientation = GradientDrawable.Orientation.TOP_BOTTOM) =
        GradientDrawable(orientation, colors).apply {
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }
    fun card(radius: Int = 24) = rounded(radius, intArrayOf(color(UiR.color.sky_card_top), color(UiR.color.sky_card_bottom)), color(UiR.color.sky_edge))
    fun secondary(radius: Int) = StatsSurfaceDrawable(this, radius.toFloat())
    fun selected(radius: Int) = rounded(radius, intArrayOf(accent, mix(accent, onAccent, 0.1f)), alpha(onAccent, 0.46f), GradientDrawable.Orientation.LEFT_RIGHT)
    fun dayEndpoint() = rounded(6, intArrayOf(mix(accent, onAccent, 0.1f), accent), alpha(onAccent, 0.44f))
    fun confirmButton() = rounded(8, intArrayOf(accent, mix(accent, onAccent, 0.1f)), alpha(onAccent, 0.42f), GradientDrawable.Orientation.LEFT_RIGHT)
    fun text(size: Float, color: Int = primaryText, medium: Boolean = false) = TextView(context).apply {
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        typeface = ResourcesCompat.getFont(context, if (medium) UiR.font.harmonyos_sans_sc_medium else UiR.font.harmonyos_sans_sc_light)
    }
}
