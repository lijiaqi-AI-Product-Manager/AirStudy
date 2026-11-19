package com.projectfocus.feature.profile.ui

import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.RadioGroup
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatRadioButton
import com.projectfocus.feature.profile.R

/** Native radio controls retain focus, keyboard and single-selection semantics. */
class SegmentedControlView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RadioGroup(context, attrs) {
    data class Segment(val label: CharSequence, val value: String)
    private val choices = linkedMapOf<Int, Segment>()
    private var applyingValue = false
    var onSegmentSelected: ((String) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.pref_segment_track)
        val inset = resources.getDimensionPixelSize(R.dimen.segment_track_inset)
        setPadding(inset, inset, inset, inset)
        setOnCheckedChangeListener { _, checkedId ->
            if (!applyingValue) choices[checkedId]?.let { onSegmentSelected?.invoke(it.value) }
        }
    }

    fun setSegments(items: List<Segment>, selectedValue: String? = null, animate: Boolean = false) {
        applyingValue = true
        removeAllViews()
        choices.clear()
        items.forEach { segment ->
            val button = AppCompatRadioButton(context).apply {
                id = View.generateViewId()
                buttonDrawable = null
                text = segment.label
                gravity = Gravity.CENTER
                setTextAppearance(R.style.TextAppearance_Pf_SettingsSegment)
                setTextColor(AppCompatResources.getColorStateList(context, R.color.pref_segment_text))
                setBackgroundResource(R.drawable.pref_segment_item)
                isSingleLine = false
                maxLines = 2
                ellipsize = null
                breakStrategy = android.text.Layout.BREAK_STRATEGY_BALANCED
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                val horizontal = com.projectfocus.core.ui.sky.SkySurfaces.dp(context, 8f)
                setPadding(horizontal, 0, horizontal, 0)
                isEnabled = this@SegmentedControlView.isEnabled
                com.projectfocus.core.ui.sky.SkyPressFeedback.install(this)
            }
            choices[button.id] = segment
            addView(button, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        applyingValue = false
        setSelectedValue(selectedValue ?: items.firstOrNull()?.value.orEmpty(), animate)
    }

    fun setSelectedValue(value: String, animate: Boolean = true) {
        val id = choices.entries.firstOrNull { it.value.value == value }?.key ?: return
        if (checkedRadioButtonId == id) return
        applyingValue = true
        check(id)
        applyingValue = false
        if (!animate) jumpDrawablesToCurrentState()
    }

    fun selectedValue(): String? = choices[checkedRadioButtonId]?.value

    fun preferredHeight(): Int {
        val lines = if (resources.configuration.fontScale > 1.25f) 2 else 1
        var lineHeight = 0
        for (index in 0 until childCount) {
            lineHeight = maxOf(lineHeight, (getChildAt(index) as android.widget.TextView).lineHeight)
        }
        return maxOf(resources.getDimensionPixelSize(R.dimen.pref_control_height),
            lineHeight * lines + paddingTop + paddingBottom + com.projectfocus.core.ui.sky.SkySurfaces.dp(context, 12f))
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        for (index in 0 until childCount) getChildAt(index).isEnabled = enabled
    }

    fun refreshAccessibility() {
        for (index in 0 until childCount) getChildAt(index).sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    companion object {
        const val THUMB_ANIM_MS = 180L
        fun options(vararg pairs: Pair<Int, String>, context: Context): List<Segment> =
            pairs.map { (label, value) -> Segment(context.getString(label), value) }
    }
}
