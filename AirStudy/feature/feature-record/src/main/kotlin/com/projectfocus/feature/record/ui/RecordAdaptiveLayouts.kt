package com.projectfocus.feature.record.ui

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.LinearLayout
import com.google.android.flexbox.FlexboxLayout

/** Filters retain usable targets when width or text size requires a second row. */
class RecordFilterLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight) /
            resources.displayMetrics.density
        val stacked = available < 416f || resources.configuration.fontScale >= 1.3f
        val target = if (stacked) VERTICAL else HORIZONTAL
        if (orientation != target) orientation = target
        for (index in 0 until childCount) {
            val params = getChildAt(index).layoutParams as LayoutParams
            params.width = if (stacked) ViewGroup.LayoutParams.MATCH_PARENT else 0
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            params.weight = if (stacked) 0f else 1f
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

/** Ticket details wrap as whole fields, preserving each label and actual value. */
class RecordMetadataLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : FlexboxLayout(context, attrs, defStyleAttr) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight) /
            resources.displayMetrics.density
        val columns = when {
            available < 260f && resources.configuration.fontScale >= 1.8f -> 1
            available < 330f || resources.configuration.fontScale >= 1.3f -> 2
            else -> 3
        }
        val pixels = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(1)
        // Whole pixel columns keep the final field on its row at fractional divisions.
        val basis = (pixels / columns).coerceAtLeast(1).toFloat() / pixels
        for (index in 0 until childCount) {
            val params = getChildAt(index).layoutParams as LayoutParams
            params.width = 0
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            params.flexBasisPercent = basis
            params.flexGrow = 0f
            params.flexShrink = 0f
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
