package com.projectfocus.feature.completion.arrival

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout


class ArrivalSquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 先按“宽度 = 父容器给的最大宽度”量一遍，拿到最终边长；
        // 再用同一个边长作为高度约束，避免先按 wrap_content 量成 0。
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
        val side = measuredWidth
        setMeasuredDimension(side, side)
    }
}
