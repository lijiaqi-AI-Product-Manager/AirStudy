package com.projectfocus.core.ui.sky

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.View
import com.projectfocus.core.ui.R

/** Shared lit surfaces. Text always sits on a sufficiently solid lower layer. */
object SkySurfaces {
    fun dp(context: Context, value: Float): Int = (value * context.resources.displayMetrics.density + .5f).toInt()

    fun shape(context: Context, radius: Float = 26f, solid: Boolean = false): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
            context.getColor(if (solid) R.color.sky_card_solid else R.color.sky_card_top),
            context.getColor(if (solid) R.color.sky_card_solid else R.color.sky_card_bottom),
        )).apply {
            cornerRadius = radius * context.resources.displayMetrics.density
            setStroke(dp(context, 1f), context.getColor(R.color.sky_edge))
        }

    fun card(view: View, radius: Float = 26f, elevation: Float = 8f, solid: Boolean = false) {
        view.background = shape(view.context, radius, solid)
        view.elevation = dp(view.context, elevation).toFloat()
        view.clipToOutline = true
        if (Build.VERSION.SDK_INT >= 28) {
            view.outlineAmbientShadowColor = Color.rgb(56, 79, 140)
            view.outlineSpotShadowColor = Color.rgb(56, 79, 140)
        }
    }

    fun action(view: View) {
        val context = view.context
        // A deeper gradient keeps white action text readable in either theme.
        val fill = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.rgb(34, 99, 212), Color.rgb(93, 74, 193))).apply {
            cornerRadius = dp(context, 24f).toFloat()
            setStroke(dp(context, 1f), 0x65FFFFFF)
        }
        view.background = RippleDrawable(ColorStateList.valueOf(0x36FFFFFF), fill, null)
        view.elevation = dp(context, 8f).toFloat()
    }

    fun secondary(view: View, radius: Float = 18f) {
        view.background = RippleDrawable(ColorStateList.valueOf(0x186A8FE8), shape(view.context, radius), null)
    }
}
