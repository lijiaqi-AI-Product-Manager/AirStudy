package com.projectfocus.app.home

import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import com.projectfocus.app.R
import com.projectfocus.feature.flight.hud.FlightHudView
import java.util.WeakHashMap

/** Keep map and preparation coordinates full-window while protecting normal page controls. */
internal object HomeWindowInsets {
    private data class Margins(val left: Int, val top: Int, val right: Int, val bottom: Int,
        val start: Int, val end: Int, val relative: Boolean)
    private val margins = WeakHashMap<View, Margins>()

    fun apply(root: View?, safe: Insets) {
        root ?: return
        val ground = root.findViewById<ViewGroup>(R.id.groundHomeContent) ?: return
        ground.clipToPadding = false
        if (ground.paddingLeft != safe.left || ground.paddingTop != safe.top ||
            ground.paddingRight != safe.right || ground.paddingBottom != safe.bottom)
            ground.setPadding(safe.left, safe.top, safe.right, safe.bottom)
        shift(root.findViewById(R.id.homeSkyBackdrop), -safe.left, -safe.top, -safe.right, -safe.bottom)
        shift(root.findViewById(R.id.homeMapClose), safe.left, safe.top, safe.right, 0)
        shift(root.findViewById(R.id.mapStyleSelectorContainer), safe.left, safe.top, safe.right, 0)
        root.findViewById<FlightHudView>(R.id.flightHudOverlay)?.applySafeInsets(safe.left, safe.top, safe.right, safe.bottom)
    }

    private fun shift(view: View?, left: Int, top: Int, right: Int, bottom: Int) {
        view ?: return
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val base = margins.getOrPut(view) { Margins(params.leftMargin, params.topMargin, params.rightMargin,
            params.bottomMargin, params.marginStart, params.marginEnd, params.isMarginRelative) }
        val rtl = view.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val start = base.start + if (rtl) right else left
        val end = base.end + if (rtl) left else right
        if (params.leftMargin == base.left + left && params.topMargin == base.top + top &&
            params.rightMargin == base.right + right && params.bottomMargin == base.bottom + bottom &&
            (!base.relative || params.marginStart == start && params.marginEnd == end)) return
        params.setMargins(base.left + left, base.top + top, base.right + right, base.bottom + bottom)
        if (base.relative) { params.marginStart = start; params.marginEnd = end }
        view.layoutParams = params
    }
}
