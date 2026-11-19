package com.projectfocus.app.freeflight

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.button.MaterialButton
import com.projectfocus.app.R
import com.projectfocus.core.ui.sky.SkyPressFeedback
import com.projectfocus.core.ui.sky.SkySurfaces
import com.projectfocus.core.ui.R as CoreUiR

class FreeFlightDepartureButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : MaterialButton(context, attrs) {
    init {
        setText(R.string.pf_ff_departure_button_text)
        setTextColor(Color.WHITE)
        typeface = ResourcesCompat.getFont(context, CoreUiR.font.harmonyos_sans_sc_bold)
        textSize = 18f
        isAllCaps = false
        minHeight = context.ffDp(56f)
        minimumHeight = minHeight
        insetTop = 0
        insetBottom = 0
        backgroundTintList = null
        SkySurfaces.action(this)
        SkyPressFeedback.install(this)
    }
}
