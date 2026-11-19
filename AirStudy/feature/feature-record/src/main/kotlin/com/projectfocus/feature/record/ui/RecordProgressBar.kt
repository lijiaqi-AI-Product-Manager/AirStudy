package com.projectfocus.feature.record.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.projectfocus.feature.record.R
import kotlin.math.floor
import kotlin.math.roundToInt

class RecordProgressBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var progress: Int = 0
        set(value) { field = value.coerceIn(0, 100); invalidate() }
    private val density = resources.displayMetrics.density
    private val segmentWidth = 6f * density
    private val gap = 4f * density
    private val corner = 3f * density
    private var segments = 20
    private val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, if (night) R.color.pf_record_page_capsule_fill_green_night else R.color.pf_record_page_capsule_fill_green_day)
    }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, if (night) R.color.pf_record_page_capsule_track_grey else R.color.pf_record_page_capsule_track_light_grey)
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wanted = (16f * density).toInt()
        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(wanted, MeasureSpec.getSize(heightMeasureSpec))
            else -> wanted
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }
    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val available = width - paddingLeft - paddingRight
        if (available > 0) segments = floor((available + gap) / (segmentWidth + gap)).toInt().coerceIn(10, 40)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val filled = (progress / 100f * segments).roundToInt()
        repeat(segments) { index ->
            val x = paddingLeft + index * (segmentWidth + gap)
            canvas.drawRoundRect(x, paddingTop.toFloat(), x + segmentWidth, (height - paddingBottom).toFloat(),
                corner, corner, if (index < filled) fill else track)
        }
    }
}
