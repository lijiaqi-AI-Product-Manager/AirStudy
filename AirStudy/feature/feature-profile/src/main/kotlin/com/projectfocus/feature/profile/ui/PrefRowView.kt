package com.projectfocus.feature.profile.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.projectfocus.feature.profile.R

/** Inline labels/actions and a separate full-width slot for native controls. */
class PrefRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {
    private val titleView = TextView(context).apply {
        setTextAppearance(R.style.TextAppearance_Pf_SettingsTitle)
        setTextColor(context.getColor(com.projectfocus.core.ui.R.color.sky_ink))
    }
    private val subtitleView = TextView(context).apply {
        setTextAppearance(R.style.TextAppearance_Pf_SettingsDescription)
        setTextColor(context.getColor(com.projectfocus.core.ui.R.color.sky_ink_secondary))
        isVisible = false
        setPadding(0, resources.getDimensionPixelSize(R.dimen.pref_row_subtitle_gap), 0, 0)
    }
    val trailingContainer = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val belowContainer = LinearLayout(context).apply {
        orientation = VERTICAL
        isVisible = false
    }

    init {
        orientation = VERTICAL
        minimumHeight = resources.getDimensionPixelSize(R.dimen.pref_row_min_height)
        val horizontal = resources.getDimensionPixelSize(R.dimen.pref_row_padding_h)
        val vertical = resources.getDimensionPixelSize(R.dimen.pref_row_padding_v)
        setPadding(horizontal, vertical, horizontal, vertical)
        val backgroundAttrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        try {
            background = backgroundAttrs.getDrawable(0)
        } finally {
            backgroundAttrs.recycle()
        }
        val textColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(subtitleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(textColumn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(trailingContainer, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = resources.getDimensionPixelSize(R.dimen.pref_control_gap)
            })
        }
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(belowContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = resources.getDimensionPixelSize(R.dimen.pref_control_gap)
        })
    }

    fun setTitle(text: CharSequence) { titleView.text = text }

    fun setSubtitle(text: CharSequence?) {
        subtitleView.text = text
        subtitleView.isVisible = !text.isNullOrBlank()
    }

    fun setTrailing(view: View?) {
        trailingContainer.removeAllViews()
        if (view != null) trailingContainer.addView(view)
        trailingContainer.isVisible = view != null
    }

    /** The control is measured against the row, not a wrap-content trailing slot. */
    fun setBelow(view: View?, height: Int = LayoutParams.WRAP_CONTENT) {
        belowContainer.removeAllViews()
        if (view != null) belowContainer.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, height))
        belowContainer.isVisible = view != null
    }

    fun setRowClickable(onClick: (() -> Unit)?) {
        isClickable = onClick != null
        isFocusable = onClick != null
        setOnClickListener(onClick?.let { { it() } })
    }
}
