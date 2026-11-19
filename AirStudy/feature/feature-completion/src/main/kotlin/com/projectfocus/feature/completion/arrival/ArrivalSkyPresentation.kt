package com.projectfocus.feature.completion.arrival

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.projectfocus.core.ui.sky.SkyBackdropView
import com.projectfocus.core.ui.sky.SkyPressFeedback
import com.projectfocus.core.ui.sky.SkySurfaces
import com.projectfocus.core.ui.widget.RollingNumberView
import com.projectfocus.feature.completion.R
import com.projectfocus.core.ui.R as UiR

/** Visual treatment shared by the ordinary and free-flight arrival binders. */
internal class ArrivalSkyPresentation(private val root: View) {
    private val context = root.context
    private val scroll = root.findViewById<ScrollView>(R.id.arrival_completion_scroll)
    private val actions = root.findViewById<LinearLayout>(R.id.flight_completion_actions)
    private val stats = root.findViewById<LinearLayout>(R.id.stats_container)
    private val secondaryActions = LinearLayout(root.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val postcard = ArrivalPostcardView(context)
    private var left = 0
    private var top = 0
    private var right = 0
    private var bottom = 0
    private val dp: (Int) -> Int = { SkySurfaces.dp(context, it.toFloat()) }

    init {
        val frame = root as FrameLayout
        root.setBackgroundColor(context.getColor(UiR.color.sky_canvas))
        frame.addView(SkyBackdropView(context), 0, FrameLayout.LayoutParams(-1, -1))
        root.findViewById<View>(R.id.gradient_overlay).visibility = View.GONE
        val media = root.findViewById<ArrivalSquareFrameLayout>(R.id.arrival_media_frame)
        SkySurfaces.card(media, radius = 28f, elevation = 6f, solid = true)
        media.setContentCornerRadius(dp(28).toFloat())
        root.findViewById<FrameLayout>(R.id.arrival_media_pager)
            .addView(postcard, 0, FrameLayout.LayoutParams(-1, -1))
        val ink = context.getColor(UiR.color.sky_ink)
        val secondary = context.getColor(UiR.color.sky_ink_secondary)
        root.findViewById<TextView>(R.id.welcome_text).setTextColor(secondary)
        root.findViewById<TextView>(R.id.arrival_city_title).setTextColor(ink)
        root.findViewById<TextView>(R.id.arrival_city_title).setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        root.findViewById<TextView>(R.id.welcome_subtitle).setTextColor(secondary)
        val airport = root.findViewById<TextView>(R.id.arrival_airport_link)
        airport.setTextColor(context.getColor(UiR.color.sky_primary))
        airport.minimumHeight = dp(48)
        airport.setPadding(0, dp(8), 0, dp(8))
        SkyPressFeedback.install(airport)
        SkySurfaces.card(stats, radius = 24f, elevation = 3f, solid = true)
        stats.setPadding(dp(16), dp(16), dp(16), dp(16))
        stats.getChildAt(1).setBackgroundColor(context.getColor(UiR.color.sky_divider))
        textViews(stats).forEach { it.setTextColor(secondary) }
        for (id in listOf(R.id.nsv_mileage, R.id.nsv_focus))
            root.findViewById<RollingNumberView>(id).setTextColor(ink)
        val complete = root.findViewById<TextView>(R.id.complete_flight_button)
        complete.setTextColor(Color.WHITE)
        complete.minimumHeight = dp(56)
        complete.minimumWidth = dp(48)
        complete.includeFontPadding = false
        complete.maxLines = 2
        complete.setPadding(dp(12), dp(12), dp(12), dp(12))
        complete.layoutParams = complete.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
        SkySurfaces.action(complete);SkyPressFeedback.install(complete)
        for (id in listOf(R.id.restart_flight_button, R.id.report_airport_button)) {
            val action = root.findViewById<ImageView>(id)
            action.imageTintList = ColorStateList.valueOf(ink)
            SkySurfaces.secondary(action, radius = 26f);SkyPressFeedback.install(action)
            actions.removeView(action)
            secondaryActions.addView(action)
        }
        actions.addView(secondaryActions, 0, LinearLayout.LayoutParams(-2, dp(52)))
        val canvas = context.getColor(UiR.color.sky_canvas)
        root.findViewById<View>(R.id.arrival_completion_bottom_scrim).background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(canvas and 0x00FFFFFF, canvas, canvas))
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            left = safe.left;top = safe.top;right = safe.right;bottom = safe.bottom
            applySafeSpacing();insets
        }
        actions.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyActionLayout();applyScrimHeight();applyScrollSpacing() }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyMediaGeometry() }
        stats.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyStatsLayout() }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { ViewCompat.requestApplyInsets(view) }
            override fun onViewDetachedFromWindow(view: View) = Unit
        })
        applySafeSpacing()
        ViewCompat.requestApplyInsets(root)
    }

    fun showArrival() {
        val subtitle = root.findViewById<TextView>(R.id.welcome_subtitle)
        subtitle.visibility = if (subtitle.text.isNullOrBlank()) View.GONE else View.VISIBLE
        postcard.showArrival()
    }

    private fun applySafeSpacing() {
        actions.setPadding(dp(28) + left, 0, dp(28) + right, dp(30) + bottom)
        applyScrimHeight()
        applyActionLayout()
        applyMediaGeometry()
        applyScrollSpacing()
    }

    private fun applyScrimHeight() {
        val scrim = root.findViewById<View>(R.id.arrival_completion_bottom_scrim)
        val desired = maxOf(dp(140) + bottom, actions.height + dp(20))
        if (scrim.layoutParams.height != desired) scrim.layoutParams = scrim.layoutParams.apply { height = desired }
    }

    private fun applyActionLayout() {
        val available = actions.width - actions.paddingLeft - actions.paddingRight
        if (available <= 0) return
        val stacked = available < dp(240) || (context.resources.configuration.fontScale >= 1.5f && available < dp(300))
        val orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        if (actions.orientation == orientation) return
        actions.orientation = orientation
        secondaryActions.layoutParams = LinearLayout.LayoutParams(-2, dp(52)).apply {
            if (stacked) gravity = Gravity.CENTER_HORIZONTAL
        }
        root.findViewById<View>(R.id.complete_flight_button).layoutParams =
            LinearLayout.LayoutParams(if (stacked) -1 else 0, -2, if (stacked) 0f else 1f).apply {
                if (stacked) topMargin = dp(12) else leftMargin = dp(18)
            }
    }

    private fun applyMediaGeometry() {
        val available = scroll.width - scroll.paddingLeft - scroll.paddingRight
        if (available <= 0 || root.height <= 0) return
        val desired = minOf(available, dp(if (root.width > root.height) 260 else 480))
        val media = root.findViewById<View>(R.id.arrival_media_frame)
        if (media.layoutParams.width != desired) media.layoutParams = (media.layoutParams as LinearLayout.LayoutParams).apply {
            width = desired
            gravity = Gravity.CENTER_HORIZONTAL
        }
    }

    private fun applyScrollSpacing() {
        val lower = maxOf(dp(106) + bottom, actions.height + dp(20))
        if (scroll.paddingLeft != dp(28) + left || scroll.paddingTop != dp(32) + top ||
            scroll.paddingRight != dp(28) + right || scroll.paddingBottom != lower)
            scroll.setPadding(dp(28) + left, dp(32) + top, dp(28) + right, lower)
    }

    private fun applyStatsLayout() {
        if (stats.width <= 0) return
        val stacked = stats.width < dp(340) || context.resources.configuration.fontScale >= 1.5f
        val orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        if (stats.orientation == orientation) return
        stats.orientation = orientation
        for (index in intArrayOf(0, 2)) {
            val column = stats.getChildAt(index)
            column.layoutParams = LinearLayout.LayoutParams(if (stacked) -1 else 0, -2, if (stacked) 0f else 1f)
            column.setPadding(0, if (stacked) dp(10) else 0, 0, if (stacked) dp(10) else 0)
        }
        val divider = stats.getChildAt(1)
        divider.layoutParams = LinearLayout.LayoutParams(if (stacked) -1 else dp(1), if (stacked) dp(1) else dp(30))
        divider.setBackgroundColor(context.getColor(UiR.color.sky_divider))
    }

    private fun textViews(view: View): Sequence<TextView> = sequence {
        if (view is TextView) yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(textViews(view.getChildAt(index)))
    }
}
