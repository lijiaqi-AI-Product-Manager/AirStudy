package com.projectfocus.feature.scene.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView
import kotlin.math.min

/** Implements the max-height contract passed by original x4.h.j(). */
class SourceSceneMaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {
    private var maximumHeight = Int.MAX_VALUE

    fun setMaxHeight(height: Int) {
        if (maximumHeight == height) return
        maximumHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val bounded = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) maximumHeight
        else min(MeasureSpec.getSize(heightMeasureSpec), maximumHeight)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(bounded, MeasureSpec.AT_MOST))
    }
}
