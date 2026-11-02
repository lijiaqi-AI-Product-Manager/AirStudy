package com.projectfocus.feature.profile.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.projectfocus.feature.profile.R

class PrefSectionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {
    private val titleView = TextView(context).apply {
        setTextAppearance(R.style.TextAppearance_Pf_SettingsSection)
        val inset = resources.getDimensionPixelSize(R.dimen.pref_section_title_padding_h)
        setPadding(inset, 0, inset, resources.getDimensionPixelSize(R.dimen.pref_section_title_padding_bottom))
        isVisible = false
    }
    private val body = LinearLayout(context).apply { orientation = VERTICAL }
    private val items = mutableListOf<PrefRowView>()

    init {
        orientation = VERTICAL
        addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setSectionTitle(text: CharSequence?) {
        titleView.text = text
        titleView.isVisible = !text.isNullOrBlank()
    }

    fun addRow(row: PrefRowView) {
        if (items.isNotEmpty()) {
            body.addView(android.view.View(context).apply {
                val color = android.util.TypedValue()
                context.theme.resolveAttribute(com.google.android.material.R.attr.colorOutlineVariant, color, true)
                setBackgroundColor(if (color.resourceId != 0) context.getColor(color.resourceId) else color.data)
            }, LayoutParams(LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.pref_divider_height)).apply {
                marginStart = resources.getDimensionPixelSize(R.dimen.pref_row_padding_h)
                marginEnd = marginStart
            })
        }
        items += row
        body.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun rows(): List<PrefRowView> = items.toList()
}
