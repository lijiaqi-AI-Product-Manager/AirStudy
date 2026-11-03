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
    val background get() = color(UiR.color.stats_background)
    val surface get() = color(UiR.color.stats_surface)
    val secondarySurface get() = color(UiR.color.stats_secondary_surface)
    val border get() = color(UiR.color.stats_border)
    val primaryText get() = color(UiR.color.stats_text_primary)
    val secondaryText get() = color(UiR.color.stats_text_secondary)
    val tertiaryText get() = color(UiR.color.stats_text_tertiary)
    val accent get() = color(UiR.color.stats_accent)
    val selectedSurface get() = color(UiR.color.stats_selected_surface)
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
    fun card(radius: Int = 16) = rounded(radius, intArrayOf(mix(surface, background, 0.015f), mix(surface, background, 0.075f)), alpha(border, 0.3f))
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
