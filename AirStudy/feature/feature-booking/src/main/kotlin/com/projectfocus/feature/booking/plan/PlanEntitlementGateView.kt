package com.projectfocus.feature.booking.plan

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.button.MaterialButton
import com.projectfocus.feature.booking.R
import com.projectfocus.core.ui.R as CoreUiR

/** Original x5.d.e dynamic denial overlay; eligibility and navigation are host-owned. */
internal class PlanEntitlementGateView(context: Context) : LinearLayout(context) {
    private val action: MaterialButton
    var onAccountAction: () -> Unit = {}

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        val padding = pixels(16)
        setPadding(padding, padding, padding, padding)
        val night = when (AppCompatDelegate.getDefaultNightMode()) {
            AppCompatDelegate.MODE_NIGHT_YES -> true
            AppCompatDelegate.MODE_NIGHT_NO -> false
            else -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        setBackgroundColor(if (night) 0xA0000000.toInt() else 0x8CFFFFFF.toInt())
        val ink = if (night) Color.WHITE else Color.BLACK
        addView(TextView(context).apply {
            text = context.getString(R.string.pf_pg_premium_flight_plan_gate_message)
            textSize = 14f
            setTextColor(ink)
            gravity = Gravity.CENTER
            try { ResourcesCompat.getFont(context, CoreUiR.font.pf_apk2_harmonyos_sans_sc_medium)?.let { typeface = it } }
            catch (_: Exception) { /* Source retains the default when font loading fails. */ }
        }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = pixels(12) })
        action = MaterialButton(context, null).apply {
            textSize = 12f
            insetTop = 0
            insetBottom = 0
            minHeight = 0
            minimumHeight = 0
            backgroundTintList = ColorStateList.valueOf(context.getColor(
                if (night) R.color.pf_pg_white_10 else R.color.pf_pg_control_bg_light))
            setTextColor(ink)
            setOnClickListener { onAccountAction() }
        }
        addView(action, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun bind(authenticated: Boolean) { action.setText(if (authenticated)
        R.string.pf_pg_flight_settings_white_noise_gate_subscribe else R.string.pf_pg_rank_login_action) }

    private fun pixels(dp: Int) = (dp * resources.displayMetrics.density).toInt()
}
