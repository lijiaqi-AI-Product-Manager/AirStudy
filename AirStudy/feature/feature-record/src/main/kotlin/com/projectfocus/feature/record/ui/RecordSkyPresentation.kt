package com.projectfocus.feature.record.ui

import android.content.Context
import android.content.res.ColorStateList
import androidx.core.graphics.ColorUtils
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputLayout
import com.projectfocus.core.ui.sky.SkyPressFeedback
import com.projectfocus.core.ui.sky.SkySurfaces
import com.projectfocus.feature.record.R
import com.projectfocus.core.ui.R as UiR

/** Presentation only: record ownership, totals, paging and editing remain with their owners. */
object RecordSkyPresentation {
    fun host(root: View) {
        val context = root.context
        val back = root.findViewById<ImageView>(R.id.pf_record_page_btn_back)
        back.imageTintList = ColorStateList.valueOf(context.getColor(UiR.color.sky_ink))
        SkySurfaces.secondary(back, 25f)
        SkyPressFeedback.install(back)
        root.findViewById<TextView>(R.id.pf_record_page_text_title).setTextColor(context.getColor(UiR.color.sky_ink))
        root.findViewById<TextView>(R.id.pf_record_page_text_subtitle).setTextColor(ticketSecondaryInk(context))
        segmented(root.findViewById(R.id.pf_record_page_main_tabs), true)
    }

    fun page(root: View) {
        val context = root.context
        val status = root.findViewById<TextInputLayout>(R.id.pf_record_page_spinner_status_container)
        status.boxBackgroundColor = context.getColor(UiR.color.sky_card_solid)
        status.boxStrokeColor = context.getColor(UiR.color.sky_divider)
        status.boxStrokeWidth = 0
        status.boxStrokeWidthFocused = 0
        val radius = SkySurfaces.dp(context, 20f).toFloat()
        status.setBoxCornerRadii(radius, radius, radius, radius)
        status.setEndIconTintList(ColorStateList.valueOf(context.getColor(UiR.color.sky_ink_secondary)))
        root.findViewById<TextView>(R.id.pf_record_page_spinner_status).apply {
            setTextColor(context.getColor(UiR.color.sky_ink))
            minimumHeight = SkySurfaces.dp(context, 52f)
        }
        segmented(root.findViewById(R.id.pf_record_page_record_tabs), false)
        SkySurfaces.card(root.findViewById(R.id.pf_record_recovery_notice), 20f, 1f, true)
        val hint = root.findViewById<TextView>(R.id.pf_record_page_empty_records_hint)
        SkySurfaces.card(hint, 22f, 1f, true)
        SkyPressFeedback.install(hint)
        for (id in listOf(R.id.pf_record_page_btn_prev_page, R.id.pf_record_page_btn_next_page)) {
            val button = root.findViewById<TextView>(id)
            button.minimumHeight = SkySurfaces.dp(context, 48f)
            button.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(context.getColor(UiR.color.sky_ink_secondary), context.getColor(UiR.color.sky_primary))))
            SkySurfaces.secondary(button, 20f)
            SkyPressFeedback.install(button)
        }
        root.findViewById<TextView>(R.id.pf_record_page_text_page_indicator)
            .setTextColor(context.getColor(UiR.color.sky_ink_secondary))
    }

    fun state(root: View, ready: Boolean, failed: Boolean) {
        root.findViewById<View>(R.id.pf_record_page_loading_icon).visibility =
            if (!ready && !failed) View.VISIBLE else View.GONE
        root.findViewById<TextView>(R.id.pf_record_page_empty_records_hint).apply {
            setTextColor(root.context.getColor(if (failed) UiR.color.sky_primary else UiR.color.sky_ink_secondary))
            isClickable = failed
            isFocusable = failed
        }
    }

    internal fun ticket(card: MaterialCardView) {
        val context = card.context
        card.radius = SkySurfaces.dp(context, 24f).toFloat()
        card.cardElevation = SkySurfaces.dp(context, 4f).toFloat()
        card.maxCardElevation = card.cardElevation
        card.preventCornerOverlap = false
        for (text in textViews(card)) text.setTextColor(ticketSecondaryInk(context))
        val mode = card.findViewById<TextView>(R.id.pf_record_card_text_flight_type_badge)
        mode.background = GradientDrawable().apply {
            setColor(context.getColor(UiR.color.sky_selected))
            cornerRadius = SkySurfaces.dp(context, 8f).toFloat()
        }
        mode.setTextColor(context.getColor(UiR.color.sky_primary))
        val edit = card.findViewById<ImageView>(R.id.pf_record_card_img_focus_scene_edit)
        SkySurfaces.secondary(edit, 14f)
        SkyPressFeedback.install(edit)
        SkyPressFeedback.install(card)
    }

    internal fun ticketSecondaryInk(context: Context): Int = ColorUtils.blendARGB(
        context.getColor(UiR.color.sky_ink_secondary), context.getColor(UiR.color.sky_ink), .16f)

    internal fun ticketCompletedInk(context: Context): Int = ColorUtils.blendARGB(
        context.getColor(UiR.color.sky_success), context.getColor(UiR.color.sky_ink), .16f)

    private fun segmented(tabs: TabLayout, primary: Boolean) {
        val context = tabs.context
        fun dp(value: Float) = SkySurfaces.dp(context, value)
        tabs.minimumHeight = dp(52f)
        tabs.tabMode = TabLayout.MODE_FIXED
        tabs.tabGravity = TabLayout.GRAVITY_FILL
        SkySurfaces.card(tabs, 22f, 1f, true)
        val indicator = if (primary) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xFF2263D4.toInt(), 0xFF5D4AC1.toInt())) else GradientDrawable().apply {
            setColor(context.getColor(UiR.color.sky_selected))
        }
        indicator.cornerRadius = dp(18f).toFloat()
        tabs.setSelectedTabIndicator(InsetDrawable(indicator, dp(4f), dp(4f), dp(4f), dp(4f)))
        tabs.setSelectedTabIndicatorColor(Color.TRANSPARENT)
        tabs.setSelectedTabIndicatorGravity(TabLayout.INDICATOR_GRAVITY_STRETCH)
        tabs.setTabIndicatorFullWidth(true)
        tabs.setTabTextColors(context.getColor(UiR.color.sky_ink_secondary),
            if (primary) Color.WHITE else context.getColor(UiR.color.sky_primary))
        for (index in 0 until tabs.tabCount) tabs.getTabAt(index)?.view?.let {
            it.minimumHeight = dp(48f)
            it.minimumWidth = dp(48f)
            SkyPressFeedback.install(it)
        }
    }

    private fun textViews(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
        else -> emptyList()
    }
}
